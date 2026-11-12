package com.candy

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

// ───────────────────────── what the bot says about this account's security ─────────────────────────

class KeyInfo(val id: String, val name: String, val createdAt: Long, val lastUsed: Long)

class SecInfo(
    val canEdit: Boolean,
    val email: String,
    val phone: String,
    val heir: String,
    val hasQuestion: Boolean,
    val question: String,
    val keys: List<KeyInfo>,
)

private fun JSONObject.toSec(): SecInfo {
    val arr = optJSONArray("keys")
    val keys = ArrayList<KeyInfo>()
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val k = arr.optJSONObject(i) ?: continue
            keys.add(KeyInfo(k.optString("id"), k.optString("name"), k.optLong("createdAt"), k.optLong("lastUsed")))
        }
    }
    return SecInfo(
        canEdit = optBoolean("canEdit", true),
        email = optString("email"),
        phone = optString("phone"),
        heir = optString("heir"),
        hasQuestion = optBoolean("hasQuestion"),
        question = optString("question"),
        keys = keys,
    )
}

/** The country code plus the number, digits only: what the bot wants for a WhatsApp number. */
private fun fullNumber(cc: String, number: String): String =
    (cc.filter { it.isDigit() } + number.filter { it.isDigit() }.trimStart('0'))

/**
 * Anything that changes how you get into the account asks for your current password first,
 * and for the answer to your security question if you set one. Same rules as in the bot.
 */
@Composable
internal fun SecureDialog(
    title: String,
    confirmLabel: String,
    question: String,
    busy: Boolean,
    error: String,
    onDismiss: () -> Unit,
    onSubmit: (password: String, answer: String) -> Unit,
    danger: Boolean = false,
    canSubmit: Boolean = true,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    var password by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
                Field(stringResource(R.string.sec_current_password), password, { password = it }, secret = true)
                if (question.isNotBlank()) {
                    Muted(question)
                    Field(stringResource(R.string.sec_answer), answer, { answer = it })
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(password, answer) },
                enabled = !busy && canSubmit && password.isNotEmpty() && (question.isBlank() || answer.isNotBlank()),
            ) {
                Text(confirmLabel, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
    )
}

// ───────────────────────── Login & Security ─────────────────────────

@Composable
fun LoginSecurityScreen(onBack: () -> Unit, onContact: () -> Unit, onPassword: () -> Unit, onKey: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.sec_title), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SettingsRow(stringResource(R.string.sec_contact), emoji = "📇", onClick = onContact)
            SettingsRow(stringResource(R.string.sec_password), emoji = "🔑", onClick = onPassword)
            SettingsRow(stringResource(R.string.sec_key), emoji = "🗝️", onClick = onKey)
            Box(Modifier.height(LocalBarInset.current + 16.dp))
        }
    }
}

// ───────────────────────── Contact Info: email and backup number ─────────────────────────

@Composable
fun ContactInfoScreen(net: Net, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sec by remember { mutableStateOf<SecInfo?>(null) }
    var reload by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var dlg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dError by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var cc by remember { mutableStateOf("+20") }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val savedMsg = stringResource(R.string.sec_saved)

    LaunchedEffect(reload) {
        val r = net.get("/me/security")
        if (r.ok) sec = r.body?.toSec() else error = r.message(ctx)
    }

    fun close() {
        dlg = ""
        dError = ""
        email = ""
        phone = ""
        code = ""
    }

    suspend fun doPost(path: String, body: JSONObject, then: () -> Unit) {
        busy = true
        dError = ""
        val r = net.post(path, body)
        busy = false
        if (r.ok) then() else dError = r.message(ctx)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.sec_contact), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.padding(horizontal = 16.dp)) { ErrorText(error) }
            if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp))
            val s = sec
            if (s != null) {
                if (!s.canEdit) Muted(stringResource(R.string.sec_main_only), Modifier.padding(16.dp))
                ContactBlock(
                    title = stringResource(R.string.sec_email),
                    value = s.email.ifBlank { stringResource(R.string.sec_none) },
                    hasValue = s.email.isNotBlank(),
                    enabled = s.canEdit,
                    onSet = { dlg = "email" },
                    onRemove = { dlg = "emailRemove" },
                )
                GroupDivider()
                ContactBlock(
                    title = stringResource(R.string.sec_phone),
                    value = s.phone.ifBlank { stringResource(R.string.sec_none) },
                    hasValue = s.phone.isNotBlank(),
                    enabled = s.canEdit,
                    onSet = { dlg = "phone" },
                    onRemove = { dlg = "phoneRemove" },
                )
                Muted(stringResource(R.string.sec_contact_note), Modifier.padding(16.dp))
            }
            Box(Modifier.height(LocalBarInset.current + 16.dp))
        }
    }

    val q = sec?.question.orEmpty()
    when (dlg) {
        "email" -> SecureDialog(
            title = stringResource(R.string.sec_email),
            confirmLabel = stringResource(R.string.sec_send_code),
            question = q, busy = busy, error = dError, onDismiss = ::close,
            canSubmit = email.isNotBlank(),
            onSubmit = { pw, ans ->
                scope.launch {
                    doPost("/me/email/start", JSONObject().put("email", email.trim()).put("password", pw).put("answer", ans)) {
                        dlg = "emailCode"
                    }
                }
            },
        ) {
            Field(stringResource(R.string.sec_email_label), email, { email = it }, keyboard = KeyboardType.Email)
        }
        "emailCode" -> AlertDialog(
            onDismissRequest = { if (!busy) close() },
            title = { Text(stringResource(R.string.sec_code_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Muted(stringResource(R.string.sec_code_sent_email, email.trim()))
                    Field(stringResource(R.string.sec_code_label), code, { code = it.filter { c -> c.isDigit() }.take(6) }, keyboard = KeyboardType.Number)
                    ErrorText(dError)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            doPost("/me/email/confirm", JSONObject().put("code", code)) {
                                close(); notice = savedMsg; reload++
                            }
                        }
                    },
                    enabled = !busy && code.length == 6,
                ) { Text(stringResource(R.string.sec_verify)) }
            },
            dismissButton = { TextButton(onClick = ::close, enabled = !busy) { Text(stringResource(R.string.cancel)) } },
        )
        "emailRemove" -> SecureDialog(
            title = stringResource(R.string.sec_remove),
            confirmLabel = stringResource(R.string.sec_remove),
            question = q, busy = busy, error = dError, danger = true, onDismiss = ::close,
            onSubmit = { pw, ans ->
                scope.launch {
                    doPost("/me/email/remove", JSONObject().put("password", pw).put("answer", ans)) {
                        close(); notice = savedMsg; reload++
                    }
                }
            },
        )
        "phone" -> SecureDialog(
            title = stringResource(R.string.sec_phone),
            confirmLabel = stringResource(R.string.sec_save),
            question = q, busy = busy, error = dError, onDismiss = ::close,
            canSubmit = phone.isNotBlank(),
            onSubmit = { pw, ans ->
                scope.launch {
                    doPost("/me/emergency/phone", JSONObject().put("phone", fullNumber(cc, phone)).put("password", pw).put("answer", ans)) {
                        close(); notice = savedMsg; reload++
                    }
                }
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(stringResource(R.string.sec_cc), cc, { cc = it }, keyboard = KeyboardType.Phone, modifier = Modifier.width(96.dp))
                Field(stringResource(R.string.sec_phone_label), phone, { phone = it }, keyboard = KeyboardType.Phone)
            }
        }
        "phoneRemove" -> SecureDialog(
            title = stringResource(R.string.sec_remove),
            confirmLabel = stringResource(R.string.sec_remove),
            question = q, busy = busy, error = dError, danger = true, onDismiss = ::close,
            onSubmit = { pw, ans ->
                scope.launch {
                    doPost("/me/emergency/phone/remove", JSONObject().put("password", pw).put("answer", ans)) {
                        close(); notice = savedMsg; reload++
                    }
                }
            },
        )
    }
}

@Composable
private fun ContactBlock(title: String, value: String, hasValue: Boolean, enabled: Boolean, onSet: () -> Unit, onRemove: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSet) {
                    Text(stringResource(if (hasValue) R.string.sec_change else R.string.sec_add))
                }
                if (hasValue) {
                    TextButton(onClick = onRemove) {
                        Text(stringResource(R.string.sec_remove), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

// ───────────────────────── Change Password ─────────────────────────

/** Prove it is you (a code, or your security question), then pick a new password. Like `.باسووردي تعديل`. */
@Composable
fun ChangePasswordScreen(net: Net, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var sec by remember { mutableStateOf<SecInfo?>(null) }
    var method by remember { mutableStateOf("") }
    var sentTo by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var random by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val r = net.get("/me/security")
        if (r.ok) sec = r.body?.toSec() else error = r.message(ctx)
    }

    suspend fun pick(m: String) {
        error = ""
        code = ""
        answer = ""
        if (m == "question") {
            method = m
            return
        }
        busy = true
        val r = net.post("/me/password/start", JSONObject().put("method", m))
        busy = false
        if (r.ok) {
            sentTo = r.body?.optString("to").orEmpty()
            method = m
        } else {
            error = r.message(ctx)
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.sec_password), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ErrorText(error)
            val s = sec
            if (result.isNotEmpty()) {
                Text(stringResource(R.string.pw_result_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(result, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                Muted(stringResource(R.string.pw_result_note))
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(result)) }) { Text(stringResource(R.string.pw_copy)) }
                Button(onClick = onBack) { Text(stringResource(R.string.sec_done)) }
            } else if (s != null && method.isEmpty()) {
                Text(stringResource(R.string.pw_choose), style = MaterialTheme.typography.titleMedium)
                Button(onClick = { scope.launch { pick("whatsapp") } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.pw_via_whatsapp))
                }
                if (s.email.isNotBlank()) {
                    OutlinedButton(onClick = { scope.launch { pick("email") } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.pw_via_email) + " · " + s.email)
                    }
                }
                if (s.phone.isNotBlank()) {
                    OutlinedButton(onClick = { scope.launch { pick("backup") } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.pw_via_backup) + " · " + s.phone)
                    }
                }
                if (s.hasQuestion) {
                    OutlinedButton(onClick = { scope.launch { pick("question") } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.pw_via_question))
                    }
                }
            } else if (s != null) {
                if (method == "question") {
                    Muted(s.question)
                    Field(stringResource(R.string.sec_answer), answer, { answer = it })
                } else {
                    Muted(stringResource(R.string.pw_code_sent, sentTo))
                    Field(stringResource(R.string.sec_code_label), code, { code = it.filter { c -> c.isDigit() }.take(6) }, keyboard = KeyboardType.Number)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { random = !random },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.pw_random), Modifier.weight(1f))
                    Switch(checked = random, onCheckedChange = { random = it })
                }
                if (!random) {
                    Field(stringResource(R.string.pw_new), newPass, { newPass = it.replace(" ", "").take(32) }, secret = true)
                    Muted(stringResource(R.string.pw_new_hint))
                }
                val proofOk = if (method == "question") answer.isNotBlank() else code.length == 6
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = ""
                            val body = JSONObject().put("method", method)
                            if (method == "question") body.put("answer", answer) else body.put("code", code)
                            if (random) body.put("random", true) else body.put("password", newPass)
                            val r = net.post("/me/password/confirm", body)
                            busy = false
                            if (r.ok) result = r.body?.optString("password").orEmpty() else error = r.message(ctx)
                        }
                    },
                    enabled = !busy && proofOk && (random || newPass.length >= 4),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.pw_change_btn)) }
                TextButton(onClick = { method = ""; error = "" }) { Text(stringResource(R.string.back)) }
            } else if (error.isEmpty()) {
                Loading()
            }
            Box(Modifier.height(LocalBarInset.current + 16.dp))
        }
    }
}

// ───────────────────────── KanadeKey ─────────────────────────

@Composable
fun KanadeKeyScreen(net: Net, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sec by remember { mutableStateOf<SecInfo?>(null) }
    var reload by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var dlg by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<KeyInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var dError by remember { mutableStateOf("") }
    val supported = remember { KanadeKey.supported(ctx) }
    val localId = remember(reload) { KanadeKey.localId(ctx) }
    val added = stringResource(R.string.key_added)
    val failedMsg = stringResource(R.string.key_failed)

    LaunchedEffect(reload) {
        val r = net.get("/me/security")
        if (r.ok) sec = r.body?.toSec() else error = r.message(ctx)
    }

    fun close() {
        dlg = ""
        deleting = null
        dError = ""
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.sec_key), onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("🗝️", style = MaterialTheme.typography.displaySmall)
            Muted(stringResource(R.string.key_intro))
            ErrorText(error)
            if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
            val s = sec
            if (s != null) {
                if (!supported) {
                    Muted(stringResource(R.string.key_unsupported))
                } else if (localId == null || s.keys.none { it.id == localId }) {
                    Button(onClick = { dlg = "add" }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.key_add)) }
                }
                if (s.keys.isEmpty()) Muted(stringResource(R.string.key_none))
                val date = DateFormat.getDateInstance(DateFormat.MEDIUM)
                for (k in s.keys) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            val label = if (k.id == localId) k.name + " · " + stringResource(R.string.key_this) else k.name
                            Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Muted(
                                if (k.lastUsed > 0) stringResource(R.string.key_last_used, date.format(Date(k.lastUsed)))
                                else stringResource(R.string.key_never),
                            )
                        }
                        TextButton(onClick = { deleting = k }) {
                            Text(stringResource(R.string.sec_remove), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else if (error.isEmpty()) {
                Loading()
            }
            Box(Modifier.height(LocalBarInset.current + 16.dp))
        }
    }

    val q = sec?.question.orEmpty()
    if (dlg == "add") {
        SecureDialog(
            title = stringResource(R.string.key_add),
            confirmLabel = stringResource(R.string.sec_add),
            question = q, busy = busy, error = dError, onDismiss = ::close,
            onSubmit = { pw, ans ->
                scope.launch {
                    busy = true
                    dError = ""
                    val pub = KanadeKey.createKey()
                    if (pub == null) {
                        busy = false
                        dError = failedMsg
                        return@launch
                    }
                    val r = net.post(
                        "/me/key/register",
                        JSONObject().put("pub", pub).put("name", Build.MODEL).put("password", pw).put("answer", ans),
                    )
                    busy = false
                    val id = r.body?.optString("id").orEmpty()
                    if (r.ok && id.isNotEmpty()) {
                        KanadeKey.saveId(ctx, id)
                        close()
                        notice = added
                        reload++
                    } else {
                        KanadeKey.forget(ctx)
                        dError = r.message(ctx)
                    }
                }
            },
        )
    }
    val del = deleting
    if (del != null) {
        SecureDialog(
            title = stringResource(R.string.sec_remove),
            confirmLabel = stringResource(R.string.sec_remove),
            question = "", busy = busy, error = dError, danger = true, onDismiss = ::close,
            onSubmit = { pw, _ ->
                scope.launch {
                    busy = true
                    dError = ""
                    val r = net.post("/me/key/delete", JSONObject().put("id", del.id).put("password", pw))
                    busy = false
                    if (r.ok) {
                        if (del.id == KanadeKey.localId(ctx)) KanadeKey.forget(ctx)
                        close()
                        reload++
                    } else {
                        dError = r.message(ctx)
                    }
                }
            },
        )
    }
}

// ───────────────────────── Manage Account: inheritance, merge, delete ─────────────────────────

/** The big decisions about the account, at the bottom of Manage Account. */
@Composable
fun AccountDangerSection(net: Net, onSignOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var sec by remember { mutableStateOf<SecInfo?>(null) }
    var reload by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf("") }
    var dlg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var dError by remember { mutableStateOf("") }
    var cc by remember { mutableStateOf("+20") }
    var phone by remember { mutableStateOf("") }
    var oldPassword by remember { mutableStateOf("") }
    var oldAnswer by remember { mutableStateOf("") }
    var oldQuestion by remember { mutableStateOf("") }

    LaunchedEffect(reload) {
        val r = net.get("/me/security")
        if (r.ok) sec = r.body?.toSec()
    }

    fun close() {
        dlg = ""
        dError = ""
        phone = ""
        oldPassword = ""
        oldAnswer = ""
        oldQuestion = ""
    }

    val s = sec ?: return
    if (!s.canEdit) return
    val q = s.question

    GroupLabel(stringResource(R.string.manage_big))
    if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    SettingsRow(
        title = stringResource(R.string.manage_heir),
        subtitle = if (s.heir.isNotBlank()) stringResource(R.string.manage_heir_set, s.heir) else stringResource(R.string.manage_heir_desc),
        emoji = "🏛️",
        onClick = { dlg = "heir" },
    )
    SettingsRow(
        title = stringResource(R.string.manage_merge),
        subtitle = stringResource(R.string.manage_merge_desc),
        emoji = "🔗",
        onClick = { dlg = "merge" },
    )
    SettingsRow(
        title = stringResource(R.string.manage_delete),
        subtitle = stringResource(R.string.manage_delete_desc),
        emoji = "🗑️",
        danger = true,
        onClick = { dlg = "delete" },
    )

    when (dlg) {
        "heir" -> SecureDialog(
            title = stringResource(R.string.manage_heir),
            confirmLabel = stringResource(R.string.sec_save),
            question = q, busy = busy, error = dError, onDismiss = ::close,
            canSubmit = phone.isNotBlank(),
            onSubmit = { pw, ans ->
                scope.launch {
                    busy = true
                    dError = ""
                    val r = net.post("/me/heir", JSONObject().put("phone", fullNumber(cc, phone)).put("password", pw).put("answer", ans))
                    busy = false
                    if (r.ok) { close(); notice = ctx.getString(R.string.sec_saved); reload++ } else dError = r.message(ctx)
                }
            },
        ) {
            Muted(stringResource(R.string.manage_heir_desc))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(stringResource(R.string.sec_cc), cc, { cc = it }, keyboard = KeyboardType.Phone, modifier = Modifier.width(96.dp))
                Field(stringResource(R.string.sec_phone_label), phone, { phone = it }, keyboard = KeyboardType.Phone)
            }
            if (s.heir.isNotBlank()) {
                TextButton(onClick = { dlg = "heirRemove"; dError = "" }) {
                    Text(stringResource(R.string.manage_heir_remove), color = MaterialTheme.colorScheme.error)
                }
            }
        }
        "heirRemove" -> SecureDialog(
            title = stringResource(R.string.manage_heir_remove),
            confirmLabel = stringResource(R.string.sec_remove),
            question = q, busy = busy, error = dError, danger = true, onDismiss = ::close,
            onSubmit = { pw, ans ->
                scope.launch {
                    busy = true
                    dError = ""
                    val r = net.post("/me/heir/remove", JSONObject().put("password", pw).put("answer", ans))
                    busy = false
                    if (r.ok) { close(); notice = ctx.getString(R.string.sec_saved); reload++ } else dError = r.message(ctx)
                }
            },
        )
        "merge" -> SecureDialog(
            title = stringResource(R.string.manage_merge),
            confirmLabel = stringResource(R.string.manage_merge_do),
            question = q, busy = busy, error = dError, danger = true, onDismiss = ::close,
            canSubmit = oldPassword.isNotBlank() && (oldQuestion.isBlank() || oldAnswer.isNotBlank()),
            onSubmit = { pw, ans ->
                scope.launch {
                    busy = true
                    dError = ""
                    val body = JSONObject().put("password", pw).put("answer", ans).put("oldPassword", oldPassword)
                    if (oldAnswer.isNotBlank()) body.put("oldAnswer", oldAnswer)
                    val r = net.post("/me/account/merge", body)
                    busy = false
                    if (r.ok) {
                        val level = r.body?.optInt("level") ?: 0
                        close()
                        notice = ctx.getString(R.string.manage_merge_done, level)
                        reload++
                    } else {
                        if (r.body?.optBoolean("needOldAnswer") == true) oldQuestion = r.body?.optString("oldQuestion").orEmpty()
                        dError = r.message(ctx)
                    }
                }
            },
        ) {
            Muted(stringResource(R.string.manage_merge_warn))
            Field(stringResource(R.string.manage_merge_old_password), oldPassword, { oldPassword = it }, secret = true)
            if (oldQuestion.isNotBlank()) {
                Muted(oldQuestion)
                Field(stringResource(R.string.manage_merge_old_answer), oldAnswer, { oldAnswer = it })
            }
        }
        "delete" -> SecureDialog(
            title = stringResource(R.string.manage_delete),
            confirmLabel = stringResource(R.string.manage_delete_do),
            question = q, busy = busy, error = dError, danger = true, onDismiss = ::close,
            onSubmit = { pw, ans ->
                scope.launch {
                    busy = true
                    dError = ""
                    val r = net.post("/me/account/delete", JSONObject().put("password", pw).put("answer", ans).put("confirm", true))
                    busy = false
                    if (r.ok) {
                        KanadeKey.forget(ctx)
                        close()
                        onSignOut()
                    } else {
                        dError = r.message(ctx)
                    }
                }
            },
        ) {
            Text(stringResource(R.string.manage_delete_warn), color = MaterialTheme.colorScheme.error)
        }
    }
}
