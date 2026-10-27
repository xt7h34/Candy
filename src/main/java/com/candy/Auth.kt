package com.candy

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
fun AuthScreen(onSignedIn: (Session) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableStateOf(Step.Start) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Logo()
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        when (step) {
            Step.Start -> {
                Text(
                    stringResource(R.string.auth_intro),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(
                    onClick = { error = ""; step = Step.Login },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.auth_have)) }
                OutlinedButton(
                    onClick = { error = ""; step = Step.Register },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.auth_new)) }
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
                                    .put("device", Build.MODEL),
                            )
                            busy = false
                            finish(r)
                        }
                    },
                    enabled = !busy && ident.isNotBlank() && pass.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.auth_signin)) }
                TextButton(onClick = { error = ""; step = Step.Start }) { Text(stringResource(R.string.back)) }
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
                                .put("password", newPass).put("device", Build.MODEL)
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
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.auth_send_code)) }
                TextButton(onClick = { error = ""; step = Step.Start }) { Text(stringResource(R.string.back)) }
            }

            Step.Code -> {
                Text(stringResource(R.string.auth_code_title), style = MaterialTheme.typography.titleMedium)
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
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.auth_confirm)) }
                TextButton(onClick = { error = ""; step = Step.Register }) { Text(stringResource(R.string.back)) }
            }
        }
    }
}
