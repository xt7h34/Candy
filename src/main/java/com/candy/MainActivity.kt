package com.candy

import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Lifecycle
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    /** A one-time code from the Kanade app, to sign in with the same account. */
    private var handoff by mutableStateOf<String?>(null)

    /** A chat to open, because a notification was tapped. */
    private var openChat by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        // the language you picked in Accessibility
        super.attachBaseContext(AppPrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppPrefs.init(this)
        enableEdgeToEdge()
        handoff = handoffCode(intent)
        openChat = intent.getStringExtra(Push.EXTRA_CHAT) ?: intent.getStringExtra(Push.EXTRA_PROFILE)?.let { "@profile:" + it }
        Push.ensureChannel(this)
        ApiCache.init(this)
        MediaCache.init(this)
        AvatarCache.init(this)
        ChatImageCache.init(this)
        setContent {
            CandyTheme {
                CandyRoot(
                    handoff = handoff,
                    openChat = openChat,
                    onChatOpened = {
                        openChat = null
                        intent.removeExtra(Push.EXTRA_CHAT)
                        intent.removeExtra(Push.EXTRA_PROFILE)
                    },
                ) {
                    handoff = null
                    intent.removeExtra(HANDOFF_EXTRA)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PushState.foreground = true
    }

    override fun onStop() {
        PushState.foreground = false
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handoff = handoffCode(intent)
        openChat = intent.getStringExtra(Push.EXTRA_CHAT) ?: intent.getStringExtra(Push.EXTRA_PROFILE)?.let { "@profile:" + it }
    }
}

private const val HANDOFF_EXTRA = "handoff_code"
private val HANDOFF_FORMAT = Regex("^[a-f0-9]{24}$")

private fun handoffCode(i: Intent?): String? =
    i?.getStringExtra(HANDOFF_EXTRA)?.takeIf { HANDOFF_FORMAT.matches(it) }

/** Signed out: the sign-in screen. Signed in: the app. */
@Composable
fun CandyRoot(handoff: String?, openChat: String?, onChatOpened: () -> Unit, onHandoffDone: () -> Unit) {
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
            ApiCache.clear()
            VideoCache.clear(ctx)
            ChatImageCache.clear()
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
                ApiCache.clear()
                VideoCache.clear(ctx)
                ChatImageCache.clear()
                MediaCache.clear()
                StickerCache.clear()
                session = null
            }
        }
        Shell(
            session = s,
            net = net,
            openChat = openChat,
            onChatOpened = onChatOpened,
            onSignOut = {
                scope.launch {
                    Push.unregister(ctx, net)
                    net.post("/auth/logout")
                    SessionStore.clear(ctx)
                    AvatarCache.clear()
                    ApiCache.clear()
                    VideoCache.clear(ctx)
                    ChatImageCache.clear()
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
    class ChatSettings(val hash: String) : Dest()
    object Camera : Dest()
    object StageEdit : Dest()
    object LoopEdit : Dest()
    object FlareEdit : Dest()
    class LoopPlayer(val hash: String, val startId: String) : Dest()
    class FlareView(val hash: String) : Dest()
    object Memories : Dest()
    object Settings : Dest()
    object KanadeAccount : Dest()
    object Subscriptions : Dest()
    object ManageAccount : Dest()
    object Accessibility : Dest()
    object Saved : Dest()
    object AccountPrivacy : Dest()
    object Blocks : Dest()
}

@Composable
fun Shell(session: Session, net: Net, openChat: String?, onChatOpened: () -> Unit, onSignOut: () -> Unit) {
    val myHash = session.profile.optString("hash")
    val pushCtx = LocalContext.current
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

    // a notification was tapped: open that chat
    LaunchedEffect(openChat) {
        val h = openChat ?: return@LaunchedEffect
        stack.clear()
        // "@profile:<hash>" comes from a notification about a person (a new follower, a new track or story); anything else is a chat
        if (h.startsWith("@profile:")) stack.add(Dest.Profile(h.removePrefix("@profile:"))) else stack.add(Dest.Chat(h))
        onChatOpened()
    }

    // notifications: ask once (Android 13 and up), then tell the bot where to send them
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(pushCtx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            val prefs = pushCtx.getSharedPreferences("candy_push", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("asked", false)) {
                prefs.edit().putBoolean("asked", true).apply()
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // tell the bot where to send notifications; if the bot cannot be reached right now, try again a few times
        if (Push.firebaseReady(pushCtx)) {
            var tries = 0
            while (!Push.register(pushCtx, net) && tries < 4) {
                tries++
                delay(15_000L * tries)
            }
        }
    }

    LaunchedEffect(refreshTick, tab, stack.size) {
        val r = net.get("/candy/dm/unread")
        if (r.ok) unread = (r.body?.optInt("count") ?: 0) + (r.body?.optInt("flares") ?: 0)
    }
    // the badge on the chats tab also refreshes the moment the bot taps us (see Events)
    PollingEffect(Events.dmTick, 60_000L) {
        val r = net.get("/candy/dm/unread")
        if (r.ok) unread = (r.body?.optInt("count") ?: 0) + (r.body?.optInt("flares") ?: 0)
    }

    // the live line to the bot: open while the app is on screen, closed when it is not
    val liveScope = rememberCoroutineScope()
    val liveOwner = pushCtx as LifecycleOwner
    DisposableEffect(net.token) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) Events.start(liveScope, net.token)
            else if (event == Lifecycle.Event.ON_STOP) Events.stop()
        }
        liveOwner.lifecycle.addObserver(observer)   // if the app is already on screen this starts it right away
        onDispose {
            liveOwner.lifecycle.removeObserver(observer)
            Events.stop()
        }
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
                                onOpenSettings = { stack.add(Dest.ChatSettings(it)) },
                                onFlare = { h -> openCamera("flare", h) },
                            )
                            is Dest.ChatSettings -> ChatSettingsScreen(net, top.hash, back, ::openProfile)
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
                            Dest.Settings -> SettingsScreen(
                                net = net,
                                onBack = back,
                                onKanadeAccount = { stack.add(Dest.KanadeAccount) },
                                onSaved = { stack.add(Dest.Saved) },
                                onMemories = { stack.add(Dest.Memories) },
                                onPrivacy = { stack.add(Dest.AccountPrivacy) },
                                onBubbles = { stack.add(Dest.Bubbles) },
                                onBlocks = { stack.add(Dest.Blocks) },
                                onAccessibility = { stack.add(Dest.Accessibility) },
                                onSignOut = onSignOut,
                            )
                            Dest.KanadeAccount -> KanadeAccountScreen(
                                onBack = back,
                                onSubscriptions = { stack.add(Dest.Subscriptions) },
                                onManage = { stack.add(Dest.ManageAccount) },
                            )
                            Dest.Subscriptions -> SubscriptionScreen(net, back)
                            Dest.ManageAccount -> ManageAccountScreen(
                                net = net,
                                onBack = back,
                                onVisits = { stack.add(Dest.Visits) },
                                onGrowth = { stack.add(Dest.Growth) },
                            )
                            Dest.Accessibility -> AccessibilityScreen(back)
                            Dest.Saved -> SavedScreen(
                                net = net,
                                onBack = back,
                                onOpenPost = { stack.add(Dest.OpenPost(it)) },
                                onOpenLoop = { stack.add(Dest.LoopPlayer("@saved", it)) },
                            )
                            Dest.AccountPrivacy -> AccountPrivacyScreen(net, back, ::openProfile)
                            Dest.Blocks -> BlocksScreen(net, back, ::openProfile)
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
                                onOpenSettings = { stack.add(Dest.Settings) },
                                refreshKey = refreshTick,
                                onOpenPost = { stack.add(Dest.OpenPost(it)) },
                                onNewStage = { openCamera("stage") },
                                onOpenLoop = { h, id -> stack.add(Dest.LoopPlayer(h, id)) },
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
