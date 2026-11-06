package com.candy

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
private fun SettingRow(icon: String, title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(icon, style = MaterialTheme.typography.titleLarge)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (value.isNotEmpty()) Muted(value)
        }
    }
}

/**
 * The settings of one chat, opened by tapping the bar on top of it: the other person, the theme,
 * disappearing messages, how deleting works, who sees that you read, and your private nickname for them.
 */
@Composable
fun ChatSettingsScreen(net: Net, hash: String, onBack: () -> Unit, onOpenProfile: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var peerHash by remember { mutableStateOf(hash) }
    var verified by remember { mutableStateOf(false) }
    var theme by remember { mutableStateOf("default") }
    var disappear by remember { mutableStateOf(0L) }
    var nick by remember { mutableStateOf("") }
    var seen by remember { mutableStateOf("default") }
    var deleteMode by remember { mutableStateOf("announce") }
    var proposal by remember { mutableStateOf<Boolean?>(null) }
    var hideSeen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf("") }
    var nickDraft by remember { mutableStateOf("") }

    fun apply(st: JSONObject?) {
        if (st == null) return
        theme = st.optString("theme", "default")
        disappear = st.optLong("disappear", 0L)
        nick = st.optString("nick")
        seen = st.optString("seen", "default")
        deleteMode = st.optString("deleteMode", "announce")
        val pr = st.optJSONObject("proposal")
        proposal = if (pr == null) null else pr.optBoolean("mine")
    }

    LaunchedEffect(hash) {
        val r = net.get("/candy/dm/settings?hash=" + Uri.encode(bareHash(hash)))
        loading = false
        if (r.ok) {
            val peer = r.body?.optJSONObject("peer")
            name = peer?.optString("name").orEmpty()
            peerHash = peer?.optString("hash").orEmpty().ifEmpty { hash }
            verified = peer?.optBoolean("verified") == true
            hideSeen = r.body?.optBoolean("hideSeen") == true
            apply(r.body?.optJSONObject("settings"))
        } else {
            error = r.message(ctx)
        }
    }

    fun post(path: String, body: JSONObject) {
        scope.launch {
            body.put("hash", bareHash(hash))
            val r = net.post(path, body)
            if (r.ok) {
                apply(r.body?.optJSONObject("settings"))
                error = ""
            } else {
                error = r.message(ctx)
            }
        }
    }

    val shown = nick.ifBlank { name.ifBlank { peerHash } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenHeader(stringResource(R.string.chat_options_title), onBack)
        if (loading) Loading()
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Avatar(peerHash, net.token, 112.dp, 0, Modifier.border(3.dp, MaterialTheme.colorScheme.primary, CircleShape))
            NameWithBadge(shown, verified, MaterialTheme.typography.headlineSmall, FontWeight.SemiBold)
            Muted(peerHash)
            if (nick.isNotBlank() && name.isNotBlank()) Muted(name)
            OutlinedButton(onClick = { onOpenProfile(peerHash) }, shape = CircleShape) {
                Text("👤  " + stringResource(R.string.cs_profile))
            }
            ErrorText(error)
        }

        SettingRow("🎨", stringResource(R.string.cs_theme), stringResource(chatThemeOf(theme).label)) { dialog = "theme" }
        SettingRow("⏳", stringResource(R.string.cs_disappear), stringResource(disappearLabel(disappear))) { dialog = "disappear" }
        SettingRow(
            "🗑",
            stringResource(R.string.cs_delete),
            stringResource(if (deleteMode == "silent") R.string.cs_mode_silent else R.string.cs_mode_announce) +
                (if (proposal != null) " · ⏳" else ""),
        ) { dialog = "delete" }
        SettingRow(
            "👁",
            stringResource(R.string.cs_seen),
            when (seen) {
                "hide" -> stringResource(R.string.cs_seen_hide)
                "show" -> stringResource(R.string.cs_seen_show)
                else -> stringResource(if (hideSeen) R.string.cs_seen_default_hidden else R.string.cs_seen_default_shown)
            },
        ) { dialog = "seen" }
        SettingRow("✏️", stringResource(R.string.cs_nick), nick.ifBlank { stringResource(R.string.cs_nick_hint) }) {
            nickDraft = nick
            dialog = "nick"
        }
    }

    when (dialog) {
        "theme" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(stringResource(R.string.cs_theme)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (th in ChatThemes) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    post("/candy/dm/settings", JSONObject().put("theme", th.id))
                                    dialog = ""
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(32.dp).clip(CircleShape).background(th.mine ?: MaterialTheme.colorScheme.primary))
                            Spacer(Modifier.width(12.dp))
                            Text(stringResource(th.label), Modifier.weight(1f))
                            if (th.id == theme) Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.close)) } },
        )

        "disappear" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(stringResource(R.string.cs_disappear)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (ms in DisappearOptions) {
                        ChoiceRow(disappear == ms, stringResource(disappearLabel(ms))) {
                            post("/candy/dm/settings", JSONObject().put("disappear", ms))
                            dialog = ""
                        }
                    }
                    Muted(stringResource(R.string.cs_disappear_hint))
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.close)) } },
        )

        "delete" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(stringResource(R.string.msg_delete_mode)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ChoiceRow(deleteMode == "announce", stringResource(R.string.msg_delete_announce)) {
                        post("/candy/dm/deletemode", JSONObject().put("mode", "announce"))
                    }
                    ChoiceRow(deleteMode == "silent", stringResource(R.string.msg_delete_silent)) {
                        post("/candy/dm/deletemode", JSONObject().put("mode", "silent"))
                    }
                    val who = name.ifBlank { peerHash }
                    if (proposal == true) {
                        Muted(stringResource(R.string.msg_delete_proposal_mine, who))
                        TextButton(onClick = { post("/candy/dm/deletemode/answer", JSONObject().put("accept", false)) }) {
                            Text(stringResource(R.string.msg_cancel_request))
                        }
                    } else if (proposal == false) {
                        Text(stringResource(R.string.msg_delete_proposal_theirs, who), style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { post("/candy/dm/deletemode/answer", JSONObject().put("accept", true)) }) {
                                Text(stringResource(R.string.msg_accept))
                            }
                            TextButton(onClick = { post("/candy/dm/deletemode/answer", JSONObject().put("accept", false)) }) {
                                Text(stringResource(R.string.msg_decline))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.close)) } },
        )

        "seen" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(stringResource(R.string.cs_seen)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ChoiceRow(seen == "default", stringResource(if (hideSeen) R.string.cs_seen_default_hidden else R.string.cs_seen_default_shown)) {
                        post("/candy/dm/settings", JSONObject().put("seen", "default"))
                        dialog = ""
                    }
                    ChoiceRow(seen == "hide", stringResource(R.string.cs_seen_hide)) {
                        post("/candy/dm/settings", JSONObject().put("seen", "hide"))
                        dialog = ""
                    }
                    ChoiceRow(seen == "show", stringResource(R.string.cs_seen_show)) {
                        post("/candy/dm/settings", JSONObject().put("seen", "show"))
                        dialog = ""
                    }
                    Muted(stringResource(R.string.cs_seen_note))
                }
            },
            confirmButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.close)) } },
        )

        "nick" -> AlertDialog(
            onDismissRequest = { dialog = "" },
            title = { Text(stringResource(R.string.cs_nick)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(stringResource(R.string.cs_nick_label), nickDraft, { nickDraft = it.take(30) })
                    Muted(stringResource(R.string.cs_nick_hint))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    post("/candy/dm/settings", JSONObject().put("nick", nickDraft.trim()))
                    dialog = ""
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { dialog = "" }) { Text(stringResource(R.string.cancel)) } },
        )

        else -> Unit
    }
}
