// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.post.PostSuggestion
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.systemBarsFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Only the owner's selected cards, never a public directory. */
class PostCardsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Same system bar handling as the main screen, so the status bar icons stay visible.
            val dark = com.wangzan101.mika.ui.theme.appearanceDark()
            LaunchedEffect(dark) { systemBarsFor(dark) }
            val client = remember { NumberClient.get(this) }
            val scope = rememberCoroutineScope()
            val ownPosts = intent.getBooleanExtra("ownPosts", false)
            var published by remember { mutableStateOf(emptyList<com.wangzan101.mika.core.post.Post>()) }
            var cards by remember { mutableStateOf(emptyList<PostSuggestion>()) }
            var busy by remember { mutableStateOf(false) }
            var failed by remember { mutableStateOf(false) }
            var asking by remember { mutableStateOf<PostSuggestion?>(null) }
            var question by remember { mutableStateOf("") }
            val positionPage = intent.hasExtra("withdrawCosign")
            var withdrawPosition by rememberSaveable { mutableStateOf(intent.getStringExtra("withdrawCosign")) }
            var withdrawalError by remember { mutableStateOf<Throwable?>(null) }
            suspend fun withdrawCosign(id: String) {
                busy = true
                try {
                    client.posts.withdrawCosign(id)
                    withdrawPosition = null
                    withdrawalError = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { withdrawalError = e }
                finally { busy = false }
            }
            suspend fun load() {
                if (ownPosts) published = client.posts.mine() else cards = client.postCards()
            }
            LaunchedEffect(Unit) {
                busy = true
                try {
                    if (positionPage) withdrawPosition?.let { withdrawCosign(it) } else load()
                }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failed = true }
                finally { busy = false }
            }
            fun withdraw(id: String) {
                busy = true
                scope.launch {
                    try { client.posts.withdraw(id); load(); failed = false }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            }
            fun feedback(card: PostSuggestion, liked: Boolean) {
                busy = true
                scope.launch {
                    try {
                        client.postFeedback(card.post.id, liked)
                        cards = client.postCards()
                        failed = false
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { failed = true }
                    finally { busy = false }
                }
            }
            MikaTheme(dark = dark) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                        TextButton(onClick = { finish() }) { Text(stringResource(R.string.number_back)) }
                        Text(stringResource(if (positionPage) R.string.post_position else if (ownPosts) R.string.post_mine else R.string.post_suggestions), Modifier.padding(16.dp),
                            style = MaterialTheme.typography.titleLarge)
                        if (failed) Text(stringResource(R.string.number_failed), Modifier.padding(16.dp))
                        if (positionPage && withdrawPosition == null) {
                            Text(stringResource(R.string.post_cosign_withdrawn), Modifier.padding(16.dp))
                        }
                        withdrawalError?.let { error ->
                            val message = when {
                                error is java.io.IOException -> R.string.post_cosign_withdraw_network
                                error.message in setOf("family_request_rejected", "number_moved", "family_transport_failed") ->
                                    R.string.post_cosign_withdraw_rejected
                                else -> R.string.post_cosign_withdraw_failed
                            }
                            Text(stringResource(message), Modifier.padding(16.dp))
                        }
                        withdrawPosition?.let { id ->
                            TextButton(enabled = !busy, onClick = { scope.launch { withdrawCosign(id) } }) {
                                Text(stringResource(R.string.post_cosign_withdraw_retry))
                            }
                        }
                        if (!positionPage) {
                            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (!busy && (if (ownPosts) published.isEmpty() else cards.isEmpty())) item {
                                    Text(stringResource(if (ownPosts) R.string.post_mine_empty else R.string.post_cards_empty),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (ownPosts) items(published, key = { it.id }) { post ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(com.wangzan101.mika.core.number.OfficialNumber.display(post.number, post.official),
                                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                            Text(post.title, style = MaterialTheme.typography.titleLarge)
                                            Text(post.body)
                                            post.coins?.let { Text(stringResource(R.string.post_price, it)) }
                                            post.region?.let { Text(it) }
                                            post.url?.let { Text(it) }
                                            TextButton(enabled = !busy, onClick = { withdraw(post.id) }) {
                                                Text(stringResource(R.string.post_withdraw))
                                            }
                                        }
                                    }
                                }
                                items(if (ownPosts) emptyList() else cards, key = { it.post.id }) { card ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(com.wangzan101.mika.core.number.OfficialNumber.display(card.post.number, card.post.official),
                                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                                            Text(card.post.title, style = MaterialTheme.typography.titleLarge)
                                            Text(card.post.body)
                                            card.post.coins?.let { Text(stringResource(R.string.post_price, it)) }
                                            card.post.region?.let { Text(it) }
                                            card.post.url?.let { Text(it) }
                                            Text(card.why, style = MaterialTheme.typography.bodyLarge)
                                            TextButton(enabled = !busy, onClick = { asking = card; question = "" }) {
                                                Text(stringResource(R.string.post_ask))
                                            }
                                            Row {
                                                TextButton(enabled = !busy, onClick = { feedback(card, false) }) {
                                                    Text(stringResource(R.string.post_dislike))
                                                }
                                                TextButton(enabled = !busy, onClick = { feedback(card, true) }) {
                                                    Text(stringResource(R.string.post_like))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                asking?.let { card ->
                    AlertDialog(onDismissRequest = { asking = null },
                        title = { Text(stringResource(R.string.post_ask)) },
                        // Optional: one tap is enough, the agent asks the obvious when nothing is typed.
                        text = { OutlinedTextField(value = question, onValueChange = { question = it },
                            label = { Text(stringResource(R.string.post_question)) },
                            placeholder = { Text(stringResource(R.string.post_ask_default)) }) },
                        confirmButton = { TextButton(onClick = {
                            val asked = question.ifBlank { getString(R.string.post_ask_default) }
                            val goal = getString(R.string.post_contact_goal, card.post.number, card.post.id, asked) +
                                "\n" + card.post.untrusted()
                            val conversation = (application as com.wangzan101.mika.MikaApp).conversations.newConversation()
                            TaskService.start(this@PostCardsActivity, goal, conversation)
                            startActivity(Intent(this@PostCardsActivity, MainActivity::class.java)
                                .putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, conversation))
                            asking = null
                        }) { Text(stringResource(R.string.post_ask)) } },
                        dismissButton = { TextButton(onClick = { asking = null }) {
                            Text(stringResource(R.string.number_cancel))
                        } })
                }
            }
        }
    }
}
