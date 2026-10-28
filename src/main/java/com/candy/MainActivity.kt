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
}

@Composable
fun Shell(session: Session, net: Net, onSignOut: () -> Unit) {
    val myHash = session.profile.optString("hash")
    var tab by rememberSaveable { mutableStateOf(0) }
    val stack = remember { mutableStateListOf<Dest>() }
    // goes up whenever a post is created or deleted, so the feed and the profile reload
    var refreshTick by remember { mutableStateOf(0) }

    UpdatePrompt()

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isEmpty() && tab != 0) { tab = 0 }

    fun openProfile(hash: String) {
        if (bareHash(hash).equals(bareHash(myHash), ignoreCase = true)) {
            stack.clear()
            tab = 2
        } else {
            stack.add(Dest.Profile(hash))
        }
    }

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
                        icon = { Text("🔎") },
                        label = { Text(stringResource(R.string.nav_search)) },
                    )
                    NavigationBarItem(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        icon = { Text("👤") },
                        label = { Text(stringResource(R.string.nav_me)) },
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val top = if (stack.isEmpty()) null else stack[stack.lastIndex]
            val back: () -> Unit = { stack.removeAt(stack.lastIndex) }
            if (top != null) {
                when (top) {
                    is Dest.Profile -> ProfileScreen(net, top.hash, back) { stack.add(Dest.OpenPost(it)) }
                    is Dest.OpenPost -> PostScreen(
                        net = net,
                        postId = top.id,
                        onBack = back,
                        onOpenProfile = ::openProfile,
                        onChanged = { refreshTick++; stack.removeAt(stack.lastIndex) },
                    )
                    Dest.NewPost -> NewPostScreen(
                        net = net,
                        onBack = back,
                        onPosted = { refreshTick++; stack.removeAt(stack.lastIndex) },
                    )
                    is Dest.Users -> UsersScreen(net, top.kind, back, ::openProfile)
                    Dest.Bubbles -> BubblesScreen(net, back)
                    Dest.Visits -> VisitsScreen(net, back, ::openProfile)
                    Dest.Growth -> GrowthScreen(net, back)
                }
            } else {
                when (tab) {
                    0 -> FeedScreen(
                        net = net,
                        refreshKey = refreshTick,
                        onOpen = ::openProfile,
                        onOpenPost = { stack.add(Dest.OpenPost(it)) },
                        onNewPost = { stack.add(Dest.NewPost) },
                    )
                    1 -> SearchScreen(net, ::openProfile)
                    else -> MeScreen(
                        net = net,
                        onUsers = { stack.add(Dest.Users(it)) },
                        onBubbles = { stack.add(Dest.Bubbles) },
                        onVisits = { stack.add(Dest.Visits) },
                        onGrowth = { stack.add(Dest.Growth) },
                        onSignOut = onSignOut,
                        refreshKey = refreshTick,
                        onOpenPost = { stack.add(Dest.OpenPost(it)) },
                        onNewPost = { stack.add(Dest.NewPost) },
                    )
                }
            }
        }
    }
}
