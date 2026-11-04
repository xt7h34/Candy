package com.candy

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    /** A one-time code from the Kanade app, to sign in with the same account. */
    private var handoff by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handoff = handoffCode(intent)
        setContent {
            CandyTheme {
                CandyRoot(handoff) {
                    handoff = null
                    intent.removeExtra(HANDOFF_EXTRA)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handoff = handoffCode(intent)
    }
}

private const val HANDOFF_EXTRA = "handoff_code"
private val HANDOFF_FORMAT = Regex("^[a-f0-9]{24}$")

private fun handoffCode(i: Intent?): String? =
    i?.getStringExtra(HANDOFF_EXTRA)?.takeIf { HANDOFF_FORMAT.matches(it) }

/** Signed out: the sign-in screen. Signed in: the app. */
@Composable
fun CandyRoot(handoff: String?, onHandoffDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(SessionStore.load(ctx)) }
    var exchanging by remember { mutableStateOf(false) }
    var handoffError by remember { mutableStateOf("") }

    // The Kanade app opened Candy with a one-time code: trade it for a sign-in of our own.
    LaunchedEffect(handoff) {
        val code = handoff ?: return@LaunchedEffect
        exchanging = true
        handoffError = ""
        val r = CandyApi.call(
            "/auth/handoff/exchange",
            JSONObject().put("code", code).put("device", Build.MODEL),
        )
        exchanging = false
        onHandoffDone()
        val token = r.body?.optString("token").orEmpty()
        val profile = r.body?.optJSONObject("profile")
        if (r.ok && token.isNotEmpty() && profile != null) {
            val signedIn = Session(token, profile)
            SessionStore.save(ctx, signedIn)
            AvatarCache.clear()
            MediaCache.clear()
            StickerCache.clear()
            session = signedIn
        } else {
            handoffError = r.message(ctx)
        }
    }

    val s = session
    if (exchanging) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.handoff_signing_in))
            }
        }
    } else if (s == null) {
        AuthScreen(onSignedIn = { session = it }, initialError = handoffError)
    } else {
        val net = remember(s.token) {
            Net(s.token) {
                // The bot says this sign-in is over (signed out from another device, for example).
                SessionStore.clear(ctx)
                AvatarCache.clear()
                MediaCache.clear()
                StickerCache.clear()
                session = null
            }
        }
        Shell(
            session = s,
            net = net,
            onSignOut = {
                scope.launch {
                    net.post("/auth/logout")
                    SessionStore.clear(ctx)
                    AvatarCache.clear()
                    MediaCache.clear()
                    StickerCache.clear()
                    session = null
                }
            },
        )
    }
}

/** Screens that open on top of the tabs. */
sealed class Dest {
    class Profile(val hash: String) : Dest()
    class Users(val kind: String) : Dest()   // "followers" or "following"
    object Bubbles : Dest()
    object Visits : Dest()
    object Growth : Dest()
    class OpenPost(val id: String) : Dest()
    object NewPost : Dest()
    class Story(val hash: String) : Dest()
    class Chat(val hash: String) : Dest()
    object Camera : Dest()
    object StageEdit : Dest()
    object LoopEdit : Dest()
    object FlareEdit : Dest()
    class LoopPlayer(val hash: String, val startId: String) : Dest()
    class FlareView(val hash: String) : Dest()
    object Memories : Dest()
}

@Composable
fun Shell(session: Session, net: Net, onSignOut: () -> Unit) {
    val myHash = session.profile.optString("hash")
    var tab by rememberSaveable { mutableStateOf(0) }
    val stack = remember { mutableStateListOf<Dest>() }
    // goes up whenever something is posted or deleted, so the feed and the profile reload
    var refreshTick by remember { mutableStateOf(0) }
    // the photo or video picked in the camera, waiting for its editor
    var pending by remember { mutableStateOf<Picked?>(null) }
    // which mode the camera opens in, and the friend when it was opened to send a Flare to one person
    var cameraStart by remember { mutableStateOf("stage") }
    var flareTo by remember { mutableStateOf<String?>(null) }
    // unread messages and unopened Flares, for the badge on the Chats tab
    var unread by remember { mutableStateOf(0) }
    // the track or loop whose comments sheet is open
    var commentsFor by remember { mutableStateOf<String?>(null) }

    UpdatePrompt()

    LaunchedEffect(refreshTick, tab, stack.size) {
        val r = net.get("/candy/dm/unread")
        if (r.ok) unread = (r.body?.optInt("count") ?: 0) + (r.body?.optInt("flares") ?: 0)
    }
    PollingEffect(Unit, 60_000L) {
        val r = net.get("/candy/dm/unread")
        if (r.ok) unread = (r.body?.optInt("count") ?: 0) + (r.body?.optInt("flares") ?: 0)
    }

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isEmpty() && tab != 0) { tab = 0 }

    fun openProfile(hash: String) {
        if (bareHash(hash).equals(bareHash(myHash), ignoreCase = true)) {
            stack.clear()
            tab = 4
        } else {
            stack.add(Dest.Profile(hash))
        }
    }

    fun openCamera(start: String, to: String? = null) {
        cameraStart = start
        flareTo = to
        stack.add(Dest.Camera)
    }

    fun goEditor(p: Picked, dest: String) {
        pending = p
        stack.add(
            when (dest) {
                "stage" -> Dest.StageEdit
                "loop" -> Dest.LoopEdit
                "flare" -> Dest.FlareEdit
                else -> Dest.NewPost
            },
        )
    }

    val top = if (stack.isEmpty()) null else stack[stack.lastIndex]
    val back: () -> Unit = { stack.removeAt(stack.lastIndex) }
    val fullScreen = top is Dest.Camera || top is Dest.StageEdit || top is Dest.LoopEdit || top is Dest.FlareEdit ||
        top is Dest.Story || top is Dest.LoopPlayer || top is Dest.FlareView

    // the floating bar is on every screen except the chat and the new-track form, which have a keyboard
    val showBar = !fullScreen && (top == null || !(top is Dest.Chat || top is Dest.NewPost))
    var barPx by remember { mutableStateOf(0) }
    val barInset = if (showBar) with(LocalDensity.current) { barPx.toDp() } else 0.dp

    if (fullScreen) {
        // the camera, the editors and the viewers use the whole screen
        Box(Modifier.fillMaxSize()) {
            when (top) {
                is Dest.Camera -> CameraScreen(
                    startDest = cameraStart,
                    flareLocked = flareTo != null,
                    onBack = { flareTo = null; back() },
                    onMemories = { stack.add(Dest.Memories) },
                    onPicked = { picked, dest ->
                        stack.removeAt(stack.lastIndex)
                        goEditor(picked, dest)
                    },
                )
                is Dest.StageEdit -> {
                    val p = pending
                    if (p == null) {
                        LaunchedEffect(Unit) { back() }
                    } else {
                        StageEditorScreen(
                            net = net,
                            picked = p,
                            mode = "stage",
                            presetTo = null,
                            onBack = { pending = null; back() },
                            onPosted = { pending = null; refreshTick++; stack.removeAt(stack.lastIndex) },
                        )
                    }
                }
                is Dest.LoopEdit -> {
                    val p = pending
                    if (p == null) {
                        LaunchedEffect(Unit) { back() }
                    } else {
                        StageEditorScreen(
                            net = net,
                            picked = p,
                            mode = "loop",
                            presetTo = null,
                            onBack = { pending = null; back() },
                            onPosted = { pending = null; refreshTick++; tab = 1; stack.removeAt(stack.lastIndex) },
                        )
                    }
                }
                is Dest.FlareEdit -> {
                    val p = pending
                    if (p == null) {
                        LaunchedEffect(Unit) { back() }
                    } else {
                        StageEditorScreen(
                            net = net,
                            picked = p,
                            mode = "flare",
                            presetTo = flareTo,
                            onBack = { pending = null; flareTo = null; back() },
                            onPosted = { pending = null; flareTo = null; refreshTick++; stack.removeAt(stack.lastIndex) },
                        )
                    }
                }
                is Dest.LoopPlayer -> LoopsScreen(
                    net = net,
                    hash = top.hash,
                    startId = top.startId,
                    refreshKey = refreshTick,
                    onBack = back,
                    onOpenProfile = ::openProfile,
                    onOpenComments = { commentsFor = it },
                )
                is Dest.Story -> StoryViewer(
                    net = net,
                    hash = top.hash,
                    onBack = { refreshTick++; stack.removeAt(stack.lastIndex) },
                )
                is Dest.FlareView -> FlareViewer(
                    net = net,
                    hash = top.hash,
                    onBack = { refreshTick++; stack.removeAt(stack.lastIndex) },
                )
                else -> {}
            }
        }
    } else {
        Scaffold { pad ->
            // with the bar on screen the content runs underneath it, and each screen leaves room at its end
            val dir = LocalLayoutDirection.current
            val contentPad = if (showBar) {
                PaddingValues(
                    start = pad.calculateStartPadding(dir),
                    top = pad.calculateTopPadding(),
                    end = pad.calculateEndPadding(dir),
                )
            } else {
                pad
            }
            Box(Modifier.padding(contentPad).fillMaxSize()) {
                CompositionLocalProvider(LocalBarInset provides barInset) {
                    if (top != null) {
                        when (top) {
                            is Dest.Profile -> ProfileScreen(
                                net = net,
                                hash = top.hash,
                                onBack = back,
                                onOpenPost = { stack.add(Dest.OpenPost(it)) },
                                onMessage = { stack.add(Dest.Chat(it)) },
                                onOpenLoop = { h, id -> stack.add(Dest.LoopPlayer(h, id)) },
                                onMutuals = { stack.add(Dest.Users("mutuals:" + it)) },
                            )
                            is Dest.OpenPost -> PostScreen(
                                net = net,
                                postId = top.id,
                                onBack = back,
                                onOpenProfile = ::openProfile,
                                onOpenComments = { commentsFor = it },
                                onChanged = { refreshTick++; stack.removeAt(stack.lastIndex) },
                            )
                            Dest.NewPost -> NewPostScreen(
                                net = net,
                                initial = pending?.uri,
                                onBack = { pending = null; back() },
                                onPosted = { pending = null; refreshTick++; stack.removeAt(stack.lastIndex) },
                            )
                            is Dest.Chat -> ChatScreen(
                                net = net,
                                hash = top.hash,
                                onBack = back,
                                onOpenProfile = ::openProfile,
                                onOpenLoop = { h, id -> stack.add(Dest.LoopPlayer(h, id)) },
                                onOpenPost = { stack.add(Dest.OpenPost(it)) },
                                onFlare = { h -> openCamera("flare", h) },
                            )
                            Dest.Memories -> MemoriesScreen(
                                onBack = back,
                                onUse = { picked, dest ->
                                    // leave Memories (and the camera under it), then open the editor
                                    while (stack.isNotEmpty() && (stack[stack.lastIndex] is Dest.Memories || stack[stack.lastIndex] is Dest.Camera)) {
                                        stack.removeAt(stack.lastIndex)
                                    }
                                    goEditor(picked, dest)
                                },
                            )
                            is Dest.Users -> UsersScreen(net, top.kind, back, ::openProfile)
                            Dest.Bubbles -> BubblesScreen(net, back)
                            Dest.Visits -> VisitsScreen(net, back, ::openProfile)
                            Dest.Growth -> GrowthScreen(net, back)
                            is Dest.Story, Dest.Camera, Dest.StageEdit, Dest.LoopEdit, Dest.FlareEdit,
                            is Dest.LoopPlayer, is Dest.FlareView -> {}
                        }
                    } else {
                        when (tab) {
                            0 -> FeedScreen(
                                net = net,
                                refreshKey = refreshTick,
                                onOpen = ::openProfile,
                                onOpenPost = { stack.add(Dest.OpenPost(it)) },
                                onOpenComments = { commentsFor = it },
                                onNewPost = { pending = null; stack.add(Dest.NewPost) },
                                onOpenStory = { stack.add(Dest.Story(it)) },
                                onOpenCamera = { openCamera("stage") },
                            )
                            1 -> Box(Modifier.fillMaxSize().padding(bottom = barInset)) {
                                LoopsScreen(
                                    net = net,
                                    hash = null,
                                    startId = null,
                                    refreshKey = refreshTick,
                                    onBack = null,
                                    onOpenProfile = ::openProfile,
                                    onOpenComments = { commentsFor = it },
                                )
                            }
                            2 -> SearchScreen(net, ::openProfile)
                            3 -> ChatsScreen(
                                net = net,
                                refreshKey = refreshTick,
                                onOpenChat = { stack.add(Dest.Chat(it)) },
                                onOpenFlare = { stack.add(Dest.FlareView(it)) },
                                onSendFlare = { openCamera("flare", it) },
                            )
                            else -> MeScreen(
                                net = net,
                                onUsers = { stack.add(Dest.Users(it)) },
                                onBubbles = { stack.add(Dest.Bubbles) },
                                onVisits = { stack.add(Dest.Visits) },
                                onGrowth = { stack.add(Dest.Growth) },
                                onSignOut = onSignOut,
                                refreshKey = refreshTick,
                                onOpenPost = { stack.add(Dest.OpenPost(it)) },
                                onNewPost = { pending = null; stack.add(Dest.NewPost) },
                                onOpenLoop = { h, id -> stack.add(Dest.LoopPlayer(h, id)) },
                                onMemories = { stack.add(Dest.Memories) },
                            )
                        }
                    }
                }
                if (showBar) {
                    FloatingNavBar(
                        selected = tab,
                        unread = unread,
                        onSelect = { stack.clear(); tab = it },
                        modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { barPx = it.height },
                    )
                }
            }
        }
    }

    val sheetFor = commentsFor
    if (sheetFor != null) {
        CommentsSheet(
            net = net,
            postId = sheetFor,
            onDismiss = { commentsFor = null },
            onOpenProfile = { commentsFor = null; openProfile(it) },
        )
    }
}
