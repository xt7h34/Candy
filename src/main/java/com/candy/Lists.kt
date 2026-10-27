package com.candy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** Followers or following ([kind] is "followers" or "following"). */
@Composable
fun UsersScreen(net: Net, kind: String, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var users by remember { mutableStateOf<List<CUser>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(kind) {
        loading = true
        val r = net.get("/candy/$kind")
        loading = false
        if (r.ok) {
            users = r.body?.optJSONArray("users").toUsers()
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            stringResource(if (kind == "followers") R.string.prof_followers else R.string.prof_following),
            onBack,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { ErrorText(error) }
            if (loading && users.isEmpty()) item { Loading() }
            if (!loading && error.isEmpty() && users.isEmpty()) {
                item { Muted(stringResource(R.string.users_empty), Modifier.padding(vertical = 16.dp)) }
            }
            items(users) { UserRow(it, net.token, onOpen) }
        }
    }
}

/** Create bubbles and choose who is in them. People you add must be people you follow. */
@Composable
fun BubblesScreen(net: Net, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bubbles by remember { mutableStateOf<List<Bubble>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var newName by remember { mutableStateOf("") }
    val addText = remember { mutableStateMapOf<String, String>() }

    LaunchedEffect(reload) {
        val r = net.get("/candy/bubbles")
        loading = false
        if (r.ok) {
            bubbles = r.body?.optJSONArray("bubbles").toBubbles()
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    fun act(path: String, body: JSONObject, onOk: () -> Unit = {}) {
        scope.launch {
            busy = true
            val r = net.post(path, body)
            busy = false
            if (r.ok) {
                error = ""
                onOk()
                reload++
            } else {
                error = r.message(ctx)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.bub_title), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(
                    stringResource(R.string.bub_new), newName, { newName = it.take(40) },
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { act("/candy/bubble/create", JSONObject().put("name", newName.trim())) { newName = "" } },
                    enabled = !busy && newName.isNotBlank(),
                ) { Text(stringResource(R.string.bub_create)) }
            }
            ErrorText(error)
            if (loading) Loading()
            if (!loading && bubbles.isEmpty()) Muted(stringResource(R.string.bub_empty))

            for (b in bubbles) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Column {
                                Text(b.name, style = MaterialTheme.typography.titleMedium)
                                Muted(stringResource(R.string.bub_members, b.members.size))
                            }
                            TextButton(
                                onClick = { act("/candy/bubble/delete", JSONObject().put("name", b.name)) },
                                enabled = !busy,
                            ) { Text(stringResource(R.string.bub_delete)) }
                        }
                        for (m in b.members) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(m.name.ifBlank { m.hash })
                                    Muted(m.hash)
                                }
                                TextButton(
                                    onClick = {
                                        act(
                                            "/candy/bubble/remove",
                                            JSONObject().put("name", b.name).put("hash", bareHash(m.hash)),
                                        )
                                    },
                                    enabled = !busy,
                                ) { Text(stringResource(R.string.bub_remove)) }
                            }
                        }
                        val typed = addText[b.name].orEmpty()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Field(
                                stringResource(R.string.bub_add_hint), typed, { addText[b.name] = it },
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedButton(
                                onClick = {
                                    act(
                                        "/candy/bubble/add",
                                        JSONObject().put("name", b.name).put("hash", bareHash(typed)),
                                    ) { addText[b.name] = "" }
                                },
                                enabled = !busy && typed.isNotBlank(),
                            ) { Text(stringResource(R.string.bub_add)) }
                        }
                    }
                }
            }
        }
    }
}

private class Visit(val name: String, val hash: String, val at: Long)

/** Who looked at your profile recently. Candy Plus only: the bot says so if you are not a member. */
@Composable
fun VisitsScreen(net: Net, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var visits by remember { mutableStateOf<List<Visit>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val r = net.get("/candy/visits")
        loading = false
        if (r.ok) {
            val arr = r.body?.optJSONArray("visits")
            val out = ArrayList<Visit>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(Visit(o.optString("name"), o.optString("hash"), o.optLong("at")))
                }
            }
            visits = out
        } else {
            error = r.message(ctx)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.visits_title), onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { ErrorText(error) }
            if (loading) item { Loading() }
            if (!loading && error.isEmpty() && visits.isEmpty()) {
                item { Muted(stringResource(R.string.visits_empty), Modifier.padding(vertical = 16.dp)) }
            }
            items(visits) { v ->
                Card(Modifier.fillMaxWidth().clickable(enabled = v.hash.isNotEmpty()) { onOpen(v.hash) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (v.hash.isNotEmpty()) Avatar(v.hash, net.token, 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(v.name.ifBlank { v.hash })
                            Muted(v.hash)
                        }
                        Muted(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(v.at)))
                    }
                }
            }
        }
    }
}

private fun signed(n: Int): String = if (n > 0) "+$n" else n.toString()

/** How many followers you gained today, this week and this month. Candy Plus only. */
@Composable
fun GrowthScreen(net: Net, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val r = net.get("/candy/growth")
        loading = false
        if (r.ok) data = r.body else error = r.message(ctx)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.growth_title), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ErrorText(error)
            if (loading) Loading()
            val d = data
            if (d != null) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatLine(stringResource(R.string.growth_now), d.optInt("current").toString())
                        if (d.optBoolean("hasHistory")) {
                            StatLine(stringResource(R.string.growth_today), signed(d.optInt("today")))
                            StatLine(stringResource(R.string.growth_week), signed(d.optInt("week")))
                            StatLine(stringResource(R.string.growth_month), signed(d.optInt("month")))
                        } else {
                            Muted(stringResource(R.string.growth_none))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }
}
