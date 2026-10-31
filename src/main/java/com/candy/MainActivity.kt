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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
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
    class LoopPlayer(val hash: String, val startId: String) : Dest()
}

@Composable
fun Shell(session: Session, net: Net, onSignOut: () -> Unit) {
    val myHash = session.profile.optString("hash")
    var tab by rememberSaveable { mutableStateOf(0) }
    val stack = remember { mutableStateListOf<Dest>() }
    // goes up whenever a track or stage is created or deleted, so the feed and the profile reload
    var refreshTick by remember { mutableStateOf(0) }
    // the photo or video picked in the camera, waiting for its editor
    var pending by remember { mutableStateOf<Picked?>(null) }
    // unread messages, for the badge on the Chats tab
    var unread by remember { mutableStateOf(0) }

    UpdatePrompt()

    LaunchedEffect(refreshTick, tab, stack.size) {
        val r = net.get("/candy/dm/unread")
        if (r.ok) unread = r.body?.optInt("count") ?: 0
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            val r = net.get("/candy/dm/unread")
            if (r.ok) unread = r.body?.optInt("count") ?: 0
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

    val top = if (stack.isEmpty()) null else stack[stack.lastIndex]
    val back: () -> Unit = { stack.removeAt(stack.lastIndex) }
    val fullScreen = top is Dest.Camera || top is Dest.StageEdit || top is Dest.LoopEdit || top is Dest.Story || top is Dest.LoopPlayer

    if (fullScreen) {
        // the camera, the editor and the stage viewer use the whole screen
        Box(Modifier.fillMaxSize()) {
            when (top) {
                is Dest.Camera -> CameraScreen(
                    onBack = back,
                    onPicked = { picked, dest ->
                        pending = picked
                        stack.removeAt(stack.lastIndex)
                        stack.add(
                            when (dest) {
                                "stage" -> Dest.StageEdit
                                "loop" -> Dest.LoopEdit
                                else -> Dest.NewPost
                            },
                        )
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
                            loop = false,
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
                            loop = true,
                            onBack = { pending = null; back() },
                            onPosted = { pending = null; refreshTick++; tab = 1; stack.removeAt(stack.lastIndex) },
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
                    onOpenComments = { stack.add(Dest.OpenPost(it)) },
                )
                is Dest.Story -> StoryViewer(
                    net = net,
                    hash = top.hash,
                    onBack = { refreshTick++; stack.removeAt(stack.lastIndex) },
                )
                else -> {}
            }
        }
    } else {
        Scaffold(
            bottomBar = {
                if (stack.isEmpty()) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            icon = { Text("🍬") },
                            label = { Text(stringResource(R.string.nav_feed)) },
                        )
                        NavigationBarItem(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            icon = { Text("🎬") },
                            label = { Text(stringResource(R.string.nav_loops)) },
                        )
                        NavigationBarItem(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            icon = { Text("🔎") },
                            label = { Text(stringResource(R.string.nav_search)) },
                        )
                        NavigationBarItem(
                            selected = tab == 3,
                            onClick = { tab = 3 },
                            icon = {
                                BadgedBox(badge = { if (unread > 0) Badge { Text(unread.toString()) } }) { Text("💬") }
                            },
                            label = { Text(stringResource(R.string.nav_chats)) },
                        )
                        NavigationBarItem(
                            selected = tab == 4,
                            onClick = { tab = 4 },
                            icon = { Text("👤") },
                            label = { Text(stringResource(R.string.nav_me)) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (top != null) {
                    when (top) {
                        is Dest.Profile -> ProfileScreen(
                            net = net,
                            hash = top.hash,
                            onBack = back,
                            onOpenPost = { stack.add(Dest.OpenPost(it)) },
                            onMessage = { stack.add(Dest.Chat(it)) },
                            onOpenLoop = { h, id -> stack.add(Dest.LoopPlayer(h, id)) },
                        )
                        is Dest.OpenPost -> PostScreen(
                            net = net,
                            postId = top.id,
                            onBack = back,
                            onOpenProfile = ::openProfile,
                            onChanged = { refreshTick++; stack.removeAt(stack.lastIndex) },
                        )
                        Dest.NewPost -> NewPostScreen(
                            net = net,
                            initial = pending?.uri,
                            onBack = { pending = null; back() },
                            onPosted = { pending = null; refreshTick++; stack.removeAt(stack.lastIndex) },
                        )
                        is Dest.Chat -> ChatScreen(net, top.hash, back, ::openProfile)
                        is Dest.Users -> UsersScreen(net, top.kind, back, ::openProfile)
                        Dest.Bubbles -> BubblesScreen(net, back)
                        Dest.Visits -> VisitsScreen(net, back, ::openProfile)
                        Dest.Growth -> GrowthScreen(net, back)
                        is Dest.Story, Dest.Camera, Dest.StageEdit, Dest.LoopEdit, is Dest.LoopPlayer -> {}
                    }
                } else {
                    when (tab) {
                        0 -> FeedScreen(
                            net = net,
                            refreshKey = refreshTick,
                            onOpen = ::openProfile,
                            onOpenPost = { stack.add(Dest.OpenPost(it)) },
                            onNewPost = { pending = null; stack.add(Dest.NewPost) },
                            onOpenStory = { stack.add(Dest.Story(it)) },
                            onOpenCamera = { stack.add(Dest.Camera) },
                        )
                        1 -> LoopsScreen(
                            net = net,
                            hash = null,
                            startId = null,
                            refreshKey = refreshTick,
                            onBack = null,
                            onOpenProfile = ::openProfile,
                            onOpenComments = { stack.add(Dest.OpenPost(it)) },
                        )
                        2 -> SearchScreen(net, ::openProfile)
                        3 -> ChatsScreen(net, refreshTick) { stack.add(Dest.Chat(it)) }
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
                        )
                    }
                }
            }
        }
    }
}
