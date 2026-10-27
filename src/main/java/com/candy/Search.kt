package com.candy

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(net: Net, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CUser>?>(null) }   // null until the first search
    var newAccounts by remember { mutableStateOf<List<CUser>>(emptyList()) }
    var popular by remember { mutableStateOf<List<CUser>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val r = net.get("/candy/suggestions")
        if (r.ok) {
            newAccounts = r.body?.optJSONArray("newAccounts").toUsers()
            popular = r.body?.optJSONArray("popular").toUsers()
        }
    }

    fun search() {
        scope.launch {
            busy = true
            error = ""
            val r = net.get("/candy/search?q=" + Uri.encode(bareHash(query)))
            busy = false
            if (r.ok) results = r.body?.optJSONArray("results").toUsers() else error = r.message(ctx)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Field(
                    stringResource(R.string.search_hint), query, { query = it },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { search() }, enabled = !busy && query.isNotBlank()) {
                    Text(stringResource(R.string.search_go))
                }
            }
        }
        item { ErrorText(error) }
        val found = results
        if (found != null) {
            if (found.isEmpty()) {
                item { Muted(stringResource(R.string.search_none), Modifier.padding(vertical = 16.dp)) }
            } else {
                items(found) { UserRow(it, net.token, onOpen) }
            }
        } else {
            if (newAccounts.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.search_new)) }
                items(newAccounts) { UserRow(it, net.token, onOpen) }
            }
            if (popular.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.search_popular)) }
                items(popular) { UserRow(it, net.token, onOpen) }
            }
        }
        item { Column(Modifier.padding(bottom = 16.dp)) {} }
    }
}

/** A person in a list: photo, name, hash and a line of their vibe. */
@Composable
fun UserRow(u: CUser, token: String, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpen(u.hash) }) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Avatar(u.hash, token, 48.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    u.name.ifBlank { u.hash },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Muted(u.hash)
                val vibe = u.vibe
                if (vibe != null) {
                    Text(
                        vibe,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
