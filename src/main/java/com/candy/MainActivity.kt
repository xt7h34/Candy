package com.candy

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CandyTheme {
                CandyRoot()
            }
        }
    }
}

/** Signed out: the sign-in screen. Signed in: the app. */
@Composable
fun CandyRoot() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf(SessionStore.load(ctx)) }

    val s = session
    if (s == null) {
        AuthScreen(onSignedIn = { session = it })
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
