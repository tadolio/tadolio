// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.core.coins.CoinEntry
import com.wangzan101.mika.core.coins.CoinEscrowGoals
import com.wangzan101.mika.task.TaskService
import com.wangzan101.mika.ui.MainActivity
import com.wangzan101.mika.ui.theme.MikaTheme
import com.wangzan101.mika.ui.theme.systemBarsFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A notification opens details. Neither opening nor tapping a button transfers funds. */
internal class CoinEscrowActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val receipt = intent.getStringExtra("receipt").orEmpty()
        if (!Regex("[a-f0-9-]{36}").matches(receipt)) { finish(); return }
        setContent {
            val dark = com.wangzan101.mika.ui.theme.appearanceDark()
            LaunchedEffect(dark) { systemBarsFor(dark) }
            var entry by remember { mutableStateOf<CoinEntry?>(null) }
            var failed by remember { mutableStateOf(false) }
            var opened by remember { mutableStateOf(false) }
            LaunchedEffect(receipt) {
                try {
                    entry = withContext(Dispatchers.IO) {
                        NumberClient.get(this@CoinEscrowActivity).coins.balance().entries.firstOrNull {
                            it.receipt == receipt && it.kind in setOf("pay", "receive")
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) { failed = true }
            }
            fun start(action: String, milestone: Int? = null) {
                if (opened) return
                opened = true
                val text = getString(if (action == "dispute") R.string.coins_dispute_goal else when (action) {
                    "accept" -> R.string.coins_confirm_accept
                    "decline" -> R.string.coins_confirm_decline
                    "cancel" -> R.string.coins_confirm_cancel
                    else -> R.string.coins_release_goal
                })
                val goal = if (action == "dispute") CoinEscrowGoals.dispute(receipt, text)
                    else "$text\nhivo_coins_$action receipt=$receipt" + (milestone?.let { " milestone=$it" } ?: "")
                val conversation = (application as com.wangzan101.mika.MikaApp).conversations.newConversation()
                TaskService.start(this, goal, conversation)
                startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_CONVERSATION, conversation))
                finish()
            }
            MikaTheme(dark = dark) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { finish() }) { Text(stringResource(R.string.number_back)) }
                        Text(stringResource(R.string.coins_wallet), style = MaterialTheme.typography.titleLarge)
                        if (failed) Text(stringResource(R.string.number_failed))
                        entry?.let { payment ->
                            Text(payment.title.orEmpty())
                            Text(payment.counterparty.orEmpty())
                            Text(stringResource(R.string.post_price, payment.heldAmount ?: -payment.delta))
                            Text(stringResource(when {
                                payment.status == "proposed" -> R.string.coins_proposed_status
                                payment.status in setOf("refunded", "cancelled", "declined") -> R.string.coins_refunded
                                payment.status in setOf("completed", "released") -> R.string.coins_released
                                payment.dispute != null -> R.string.coins_disputed
                                payment.kind == "pay" -> R.string.coins_buyer_pending
                                else -> R.string.coins_seller_pending
                            }))
                            payment.milestones.forEach { m ->
                                Text(stringResource(R.string.coins_stage, m.milestone + 1, m.amount, m.what))
                                Text(stringResource(R.string.coins_stage_status, m.milestone + 1,
                                    stringResource(when (m.status) {
                                        "released" -> R.string.coins_released
                                        "refunded" -> R.string.coins_refunded
                                        else -> R.string.coins_buyer_pending
                                    })))
                                if (m.due != null && m.atDue != null) Text(stringResource(R.string.coins_due_rule,
                                    java.time.Instant.ofEpochMilli(checkNotNull(m.due)).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString(),
                                    stringResource(if (m.atDue == "release") R.string.coins_due_release else R.string.coins_due_refund)))
                                if (payment.status == "active" && payment.kind == "pay" && m.status == "pending") {
                                    TextButton(enabled = !opened, onClick = { start("release", m.milestone) }) {
                                        Text(stringResource(R.string.coins_release))
                                    }
                                }
                            }
                            if (payment.status == "proposed") {
                                if (payment.kind == "receive") {
                                    TextButton(enabled = !opened, onClick = { start("accept") }) { Text(stringResource(R.string.coins_accept)) }
                                    TextButton(enabled = !opened, onClick = { start("decline") }) { Text(stringResource(R.string.coins_decline)) }
                                } else {
                                    TextButton(enabled = !opened, onClick = { start("cancel") }) { Text(stringResource(R.string.coins_cancel)) }
                                }
                            }
                            if (payment.status == "active" && payment.kind == "pay") {
                                TextButton(enabled = !opened && payment.dispute == null, onClick = { start("dispute") }) {
                                    Text(stringResource(R.string.coins_problem))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
