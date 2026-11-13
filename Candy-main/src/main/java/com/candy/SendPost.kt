package com.candy

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Send a track to someone in a message. The people you talked to last are on top. */
@Composable
fun SendPostDialog(net: Net, postId: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var recent by remember { mutableStateOf<List<Member>>(emptyList()) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val r = net.get("/candy/dm/inbox")
        if (r.ok) {
            val arr = r.body?.optJSONArray("threads")
            val list = ArrayList<Member>()
            if (arr != null) {
                for (i in 0 until minOf(arr.length(), 6)) {
                    val peer = arr.optJSONObject(i)?.optJSONObject("peer") ?: continue
                    list.add(Member(peer.optString("name"), peer.optString("hash")))
                }
            }
            recent = list
        }
    }

    fun sendTo(hash: String) {
        scope.launch {
            busy = true
            error = ""
            val r = net.post("/candy/dm/send", JSONObject().put("hash", bareHash(hash)).put("track", postId))
            busy = false
            if (r.ok) done = ctx.getString(R.string.loop_sent) else error = r.message(ctx)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.post_share_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in recent) {
                    TextButton(onClick = { sendTo(m.hash) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(m.name.ifBlank { m.hash })
                    }
                }
                Field(stringResource(R.string.loop_send_hash), typed, { typed = it })
                if (done.isNotEmpty()) Text(done, color = MaterialTheme.colorScheme.primary)
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(onClick = { sendTo(typed) }, enabled = !busy && typed.isNotBlank()) { Text(stringResource(R.string.post_send)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
