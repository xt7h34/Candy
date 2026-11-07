package com.candy

import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** How you like the chat to look and behave. Kept on this phone. */
object ChatPrefs {
    private const val FILE = "candy_chat_prefs"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** "bar": press and hold selects the message and a bar appears on top. "popup": a small menu next to it. */
    fun menuStyle(c: Context): String = if (sp(c).getString("menu", "bar") == "popup") "popup" else "bar"
    fun setMenuStyle(c: Context, v: String) = sp(c).edit().putString("menu", v).apply()

    /** "text": the words under the name. "bubble": three dots at the end of the chat. */
    fun typingStyle(c: Context): String = if (sp(c).getString("typing", "text") == "bubble") "bubble" else "text"
    fun setTypingStyle(c: Context, v: String) = sp(c).edit().putString("typing", v).apply()
}

@Composable
internal fun ChoiceRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** The "Message settings" window in your account. */
@Composable
fun MessageSettingsDialog(net: Net, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var hideSeen by remember { mutableStateOf(false) }
    var seenLoaded by remember { mutableStateOf(false) }
    var pushEnabled by remember { mutableStateOf(true) }
    var pushPreview by remember { mutableStateOf(true) }
    var pushReady by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val r = net.get("/candy/me")
        if (r.ok) {
            hideSeen = r.body?.optBoolean("hideSeen") == true
            pushEnabled = r.body?.optBoolean("pushEnabled", true) != false
            pushPreview = r.body?.optBoolean("pushPreview", true) != false
            pushReady = r.body?.optBoolean("pushReady", true) != false
            seenLoaded = true
        }
    }
    var menu by remember { mutableStateOf(ChatPrefs.menuStyle(ctx)) }
    var typing by remember { mutableStateOf(ChatPrefs.typingStyle(ctx)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.msg_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                SectionTitle(stringResource(R.string.msg_menu_style))
                ChoiceRow(menu == "bar", stringResource(R.string.msg_menu_bar)) {
                    menu = "bar"
                    ChatPrefs.setMenuStyle(ctx, "bar")
                }
                ChoiceRow(menu == "popup", stringResource(R.string.msg_menu_popup)) {
                    menu = "popup"
                    ChatPrefs.setMenuStyle(ctx, "popup")
                }
                Spacer(Modifier.padding(top = 8.dp))
                SectionTitle(stringResource(R.string.msg_typing_style))
                ChoiceRow(typing == "text", stringResource(R.string.msg_typing_text)) {
                    typing = "text"
                    ChatPrefs.setTypingStyle(ctx, "text")
                }
                ChoiceRow(typing == "bubble", stringResource(R.string.msg_typing_bubble)) {
                    typing = "bubble"
                    ChatPrefs.setTypingStyle(ctx, "bubble")
                }
                Spacer(Modifier.padding(top = 8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.msg_seen_share), Modifier.weight(1f))
                    Switch(
                        checked = !hideSeen,
                        enabled = seenLoaded,
                        onCheckedChange = { on ->
                            hideSeen = !on
                            scope.launch { net.post("/candy/seen/default", JSONObject().put("hide", !on)) }
                        },
                    )
                }
                Spacer(Modifier.padding(top = 8.dp))
                SectionTitle(stringResource(R.string.msg_push_title))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.msg_push_enabled), Modifier.weight(1f))
                    Switch(
                        checked = pushEnabled,
                        enabled = seenLoaded,
                        onCheckedChange = { on ->
                            pushEnabled = on
                            scope.launch { net.post("/candy/push/prefs", JSONObject().put("enabled", on)) }
                        },
                    )
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.msg_push_preview), Modifier.weight(1f))
                    Switch(
                        checked = pushPreview,
                        enabled = seenLoaded && pushEnabled,
                        onCheckedChange = { on ->
                            pushPreview = on
                            scope.launch { net.post("/candy/push/prefs", JSONObject().put("preview", on)) }
                        },
                    )
                }
                if (!Push.firebaseReady(ctx)) {
                    Muted(stringResource(R.string.msg_push_app_off))
                } else if (!pushReady) {
                    Muted(stringResource(R.string.msg_push_server_off))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
