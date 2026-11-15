// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.wangzan101.mika.R
import com.wangzan101.mika.points.PointsBalance
import com.wangzan101.mika.points.PointsClient
import com.wangzan101.mika.ui.components.*

@Composable
fun PointsScreen(client: PointsClient, participating: Boolean, onBack: () -> Unit) {
    var balance by remember { mutableStateOf<PointsBalance?>(null) }
    var error by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    var wallet by remember { mutableStateOf<com.wangzan101.mika.core.coins.CoinBalance?>(null) }
    LaunchedEffect(participating, refresh) {
        balance = null
        error = false
        runCatching { withContext(Dispatchers.IO) {
            com.wangzan101.mika.number.NumberClient.get(context).coins.balance()
        } }.onSuccess { wallet = it }.onFailure { error = true }
        if (participating) runCatching { client.balance() }.onSuccess { balance = it }.onFailure { error = true }
    }
    PageScaffold(stringResource(R.string.coins_wallet), onBack) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
            Text(
                stringResource(R.string.points_disclaimer),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
        val b = balance
        Text(stringResource(R.string.coins_wallet) + ": " + (wallet?.balance ?: "—"))
        Text(stringResource(R.string.points_pending) + ": " + (b?.pending ?: "—"), style = MaterialTheme.typography.bodySmall)
        wallet?.entries?.forEach { entry ->
            val label = when {
                entry.status == "proposed" -> R.string.coins_proposed_status
                entry.status in setOf("proposed", "active") && entry.dispute != null -> R.string.coins_disputed
                entry.status in setOf("proposed", "active") && entry.kind == "pay" -> R.string.coins_buyer_pending
                entry.status in setOf("proposed", "active") -> R.string.coins_seller_pending
                entry.status in setOf("refunded", "cancelled", "declined") -> R.string.coins_refunded
                entry.status in setOf("completed", "released") -> R.string.coins_released
                else -> when (entry.kind) {
                "pay" -> R.string.coins_pay
                "receive" -> R.string.coins_receive
                "share" -> R.string.coins_share
                else -> R.string.coins_deposit
                }
            }
            entry.milestones.forEach { m ->
                Text(stringResource(R.string.coins_stage, m.milestone + 1, m.amount, m.what))
                Text(stringResource(when (m.status) {
                    "released" -> R.string.coins_released
                    "refunded" -> R.string.coins_refunded
                    else -> label
                }))
            }
            Text("${stringResource(label)} ${if (entry.delta > 0) "+" else ""}${entry.delta} · ${entry.counterparty.orEmpty()} · ${java.time.Instant.ofEpochMilli(entry.at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}")
            if (entry.status in setOf("proposed", "active") && entry.receipt != null) {
                TextButton(onClick = {
                    context.startActivity(android.content.Intent(context, com.wangzan101.mika.number.CoinEscrowActivity::class.java)
                        .putExtra("receipt", entry.receipt))
                }) { Text(stringResource(R.string.coins_buyer_pending)) }
            }
        }
        if (error) FooterNote(stringResource(R.string.points_error))
        else if (participating && b == null) FooterNote(stringResource(R.string.points_empty))
        TextButton(onClick = { refresh++; client.retry() }) { Text(stringResource(R.string.points_refresh)) }
    }
}
