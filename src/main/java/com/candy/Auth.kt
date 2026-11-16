package com.candy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private enum class Step { Start, Login, Register, Code }

@Composable
private fun Logo() {
    Box(
        Modifier
            .size(112.dp)
            .clip(CircleShape)
            .background(colorResource(R.color.ic_launcher_background)),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(112.dp)
                .scale(1.3f),
        )
    }
}

/** Sign in or create a Kanade account. It is the same account in both apps. */
@Composable
fun AuthScreen(onSignedIn: (Session) -> Unit, initialError: String = "") {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.Start) }
    var busy by remember { mutableStateOf(false) }
    var error by remember(initialError) { mutableStateOf(initialError) }

    var ident by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var birth by remember { mutableStateOf("") }
    var hash by remember { mutableStateOf("") }
    var cc by remember { mutableStateOf("+20") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
    var emailRequired by remember { mutableStateOf(false) }

    fun finish(r: ApiResult) {
        val token = r.body?.optString("token").orEmpty()
        val profile = r.body?.optJSONObject("profile")
        if (r.ok && token.isNotEmpty() && profile != null) {
            val s = Session(token, profile)
            SessionStore.save(ctx, s)
            onSignedIn(s)
        } else {
            error = r.message(ctx)
        }
    }

    Box(Modifier.fillMaxSize()) {
        // a soft rose glow at the top
        Box(
            Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), Color.Transparent))),
        )
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            // the first screen sits in the middle; the forms start at the top
            verticalArrangement = if (step == Step.Start) Arrangement.spacedBy(14.dp, Alignment.CenterVertically) else Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        if (step == Step.Start) {
            Logo()
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall.copy(brush = CandyBrand.gradient()),
            )
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppIconButton(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    stringResource(R.string.back),
                    { error = ""; step = if (step == Step.Code) Step.Register else Step.Start },
                )
            }
            Text(
                stringResource(
                    when (step) {
                        Step.Login -> R.string.auth_title_login
                        Step.Register -> R.string.auth_title_register
                        else -> R.string.auth_code_title
                    },
                ),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        when (step) {
            Step.Start -> {
                Text(
                    stringResource(R.string.auth_intro),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { error = ""; step = Step.Login },
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape,
                ) { Text(stringResource(R.string.auth_have)) }
                OutlinedButton(
                    onClick = { error = ""; step = Step.Register },
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape,
                ) { Text(stringResource(R.string.auth_new)) }
                // a KanadeKey made on this phone: fingerprint instead of typing the password
                var keyIdState by remember { mutableStateOf(KanadeKey.localId(ctx)) }
                val keyId = keyIdState
                if (keyId != null) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = ""
                                val ch = CandyApi.call("/auth/key/challenge", JSONObject().put("keyId", keyId))
                                val challenge = ch.body?.optString("challenge").orEmpty()
                                if (!ch.ok || challenge.isEmpty()) {
                                    busy = false
                                    error = ch.message(ctx)
                                    return@launch
                                }
                                KanadeKey.sign(
                                    ctx,
                                    ctx.getString(R.string.key_prompt),
                                    ctx.getString(R.string.key_prompt_desc),
                                    ctx.getString(R.string.cancel),
                                    "kanadekey:$challenge:$keyId".toByteArray(),
                                    done = { sig ->
                                        scope.launch {
                                            val r = CandyApi.call(
                                                "/auth/key/login",
                                                JSONObject().put("keyId", keyId).put("challenge", challenge)
                                                    .put("signature", KanadeKey.b64(sig))
                                                    .put("device", Build.MODEL).put("app", "candy"),
                                            )
                                            busy = false
                                            finish(r)
                                        }
                                    },
                                    failed = { why ->
                                        busy = false
                                        when (why) {
                                            is KanadeKey.Fail.Cancelled -> Unit
                                            is KanadeKey.Fail.Changed -> {
                                                // the phone locked this key for good: forget it so the button goes, and say why
                                                KanadeKey.forget(ctx)
                                                keyIdState = null
                                                error = ctx.getString(R.string.key_changed)
                                            }
                                            is KanadeKey.Fail.Other -> error = why.message?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.key_error)
                                        }
                                    },
                                )
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.key_signin))
                    }
                }
                ErrorText(error)
                Muted(stringResource(R.string.auth_from_kanade))
            }

            Step.Login -> {
                Field(stringResource(R.string.auth_ident), ident, { ident = it })
                Field(stringResource(R.string.auth_password), pass, { pass = it }, secret = true)
                ErrorText(error)
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = ""
                            val r = CandyApi.call(
                                "/auth/login",
                                JSONObject().put("identifier", ident.trim()).put("password", pass)
                                    .put("device", Build.MODEL).put("app", "candy"),
                            )
                            busy = false
                            finish(r)
                        }
                    },
                    enabled = !busy && ident.isNotBlank() && pass.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape,
                ) { Text(stringResource(R.string.auth_signin)) }
            }

            Step.Register -> {
                Field(stringResource(R.string.auth_name), name, { name = it })
                Field(stringResource(R.string.auth_birth), birth, { birth = it })
                Field(stringResource(R.string.auth_hash), hash, { hash = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Field(
                        stringResource(R.string.auth_cc), cc, { cc = it },
                        keyboard = KeyboardType.Phone,
                        modifier = Modifier.weight(0.35f),
                    )
                    Field(
                        stringResource(R.string.auth_phone), phone, { phone = it },
                        keyboard = KeyboardType.Phone,
                        modifier = Modifier.weight(0.65f),
                    )
                }
                Text(
                    stringResource(R.string.auth_phone_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field(stringResource(R.string.auth_email), email, { email = it }, keyboard = KeyboardType.Email)
                Text(
                    stringResource(R.string.auth_email_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field(stringResource(R.string.auth_pass_new), newPass, { newPass = it }, secret = true)
                ErrorText(error)
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = ""
                            // A number that starts with + already has its country code.
                            val digits = if (phone.trim().startsWith("+")) {
                                phone.filter { it.isDigit() }
                            } else {
                                cc.filter { it.isDigit() } + phone.filter { it.isDigit() }.trimStart('0')
                            }
                            val payload = JSONObject().put("name", name.trim()).put("birthdate", birth.trim())
                                .put("hash", hash.trim()).put("phone", digits)
                                .put("password", newPass).put("device", Build.MODEL).put("app", "candy")
                            if (email.isNotBlank()) payload.put("email", email.trim())
                            val r = CandyApi.call("/auth/register/start", payload)
                            busy = false
                            val id = r.body?.optString("requestId").orEmpty()
                            if (r.ok && id.isNotEmpty()) {
                                requestId = id
                                emailRequired = r.body?.optBoolean("emailRequired") == true
                                code = ""
                                emailCode = ""
                                step = Step.Code
                            } else {
                                error = r.message(ctx)
                            }
                        }
                    },
                    enabled = !busy && name.isNotBlank() && birth.isNotBlank() && hash.isNotBlank() &&
                        phone.isNotBlank() && newPass.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape,
                ) { Text(stringResource(R.string.auth_send_code)) }
            }

            Step.Code -> {
                Text(
                    stringResource(if (emailRequired) R.string.auth_code_hint_both else R.string.auth_code_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Field(stringResource(R.string.auth_cc), code, { code = it.filter { c -> c.isDigit() }.take(6) }, keyboard = KeyboardType.Number)
                if (emailRequired) {
                    Field(stringResource(R.string.auth_email_code), emailCode, { emailCode = it.filter { c -> c.isDigit() }.take(6) }, keyboard = KeyboardType.Number)
                }
                ErrorText(error)
                Button(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = ""
                            val payload = JSONObject().put("requestId", requestId).put("code", code)
                            if (emailRequired) payload.put("emailCode", emailCode)
                            val r = CandyApi.call("/auth/register/confirm", payload)
                            busy = false
                            finish(r)
                        }
                    },
                    enabled = !busy && code.length == 6 && (!emailRequired || emailCode.length == 6),
                    modifier = Modifier.fillMaxWidth().height(52.dp), shape = CircleShape,
                ) { Text(stringResource(R.string.auth_confirm)) }
            }
        }
        }
        }
    }
}
