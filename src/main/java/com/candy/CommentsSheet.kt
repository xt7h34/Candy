package com.candy

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val COMMENT_MAX = 300

/** The comments of one track or loop, in a sheet that slides up over whatever you were looking at. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(
    net: Net,
    postId: String,
    onDismiss: () -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var comments by remember { mutableStateOf<List<Comment>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(postId, reload) {
        val r = net.get("/candy/post?id=" + Uri.encode(postId))
        loading = false
        if (r.ok) {
            comments = r.body?.optJSONArray("commentList").toComments()
            error = ""
        } else {
            error = r.message(ctx)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        fun send() {
            if (busy || text.isBlank()) return
            scope.launch {
                busy = true
                val r = net.post("/candy/comment", JSONObject().put("id", postId).put("text", text.trim()))
                busy = false
                if (r.ok) {
                    text = ""
                    error = ""
                    reload++
                } else {
                    error = r.message(ctx)
                }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = Space.lg),
        ) {
            Row(
                Modifier.padding(bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Text(stringResource(R.string.post_comments_title), style = MaterialTheme.typography.titleMedium)
                if (comments.isNotEmpty()) Muted(comments.size.toString())
            }
            ErrorText(error) { reload++ }
            if (notice.isNotEmpty()) Muted(notice)
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                if (loading) {
                    Loading()
                } else if (comments.isEmpty() && error.isEmpty()) {
                    EmptyState(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.post_no_comments))
                }
                for (c in comments) {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        Avatar(c.ownerHash, net.token, 36.dp, 0, Modifier.clickable { onOpenProfile(c.ownerHash) })
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                                Column(Modifier.padding(horizontal = Space.md, vertical = Space.sm)) {
                                    NameWithBadge(
                                        c.ownerName.ifBlank { c.ownerHash },
                                        c.ownerVerified,
                                        MaterialTheme.typography.labelLarge,
                                        FontWeight.Bold,
                                        badge = c.ownerBadge,
                                    )
                                    Text(
                                        taggedText(c.text, MaterialTheme.colorScheme.primary),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                            Muted(agoShort(ctx, c.at), Modifier.padding(start = Space.md))
                        }
                        AppIconButton(
                            if (c.canDelete) Icons.Rounded.Delete else Icons.Rounded.Flag,
                            null,
                            {
                                if (c.canDelete) {
                                    scope.launch {
                                        val r = net.post(
                                            "/candy/comment/delete",
                                            JSONObject().put("postId", postId).put("commentId", c.id),
                                        )
                                        if (r.ok) reload++ else error = r.message(ctx)
                                    }
                                } else {
                                    reporting = c.id
                                }
                            },
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            // the box to write in, like the one in a chat: a round field and a round send button
            Row(
                Modifier.fillMaxWidth().padding(vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(COMMENT_MAX) },
                    placeholder = { Text(stringResource(R.string.post_comment_hint)) },
                    maxLines = 4,
                    shape = MaterialTheme.shapes.extraLarge,
                    modifier = Modifier.weight(1f),
                )
                val ready = !busy && text.isNotBlank()
                Box(
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(enabled = ready) { send() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = stringResource(R.string.post_send),
                        tint = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    val reported = reporting
    if (reported != null) {
        ReportDialog(
            onSend = { reason ->
                reporting = null
                scope.launch {
                    val r = net.post(
                        "/candy/report",
                        JSONObject().put("type", "comment").put("id", reported).put("reason", reason).put("postId", postId),
                    )
                    if (r.ok) notice = ctx.getString(R.string.report_sent) else error = r.message(ctx)
                }
            },
            onDismiss = { reporting = null },
        )
    }
}
