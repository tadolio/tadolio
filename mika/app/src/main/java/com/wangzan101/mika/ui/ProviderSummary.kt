// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.R
import com.wangzan101.mika.number.ReferralLink
import kotlinx.serialization.json.*

@Composable internal fun ProviderSummary(card: JsonObject) {
    val context = LocalContext.current
    val invite = card.operatorText("invite")
    val shareText = stringResource(R.string.operator_share_link, invite, ReferralLink.operatorWeb(invite))
    Text(stringResource(R.string.operator_mine), style = MaterialTheme.typography.titleLarge)
    Text(card.operatorText("name"))
    Text(stringResource(R.string.operator_share, card["share"]?.jsonPrimitive?.intOrNull ?: 0), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.operator_invite_value, invite))
    TextButton(enabled = invite.isNotBlank(), onClick = {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tadolio", invite))
    }) { Text(stringResource(R.string.number_copy)) }
    TextButton(enabled = invite.isNotBlank(), onClick = {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareText), null))
    }) { Text(stringResource(R.string.operator_share_action)) }
    Text(stringResource(R.string.operator_counts, card["users"]?.jsonPrimitive?.intOrNull ?: 0, card["boundUsers"]?.jsonPrimitive?.intOrNull ?: 0, card["maxUsers"]?.jsonPrimitive?.intOrNull ?: 0))
    Text(stringResource(if (card.operatorFlag("listed")) R.string.operator_listed else R.string.operator_unlisted))
    Text(stringResource(when(card["examStatus"]?.jsonObject?.operatorText("status")) {
        "passed" -> R.string.miner_state_online
        "exam_running" -> R.string.miner_state_exam
        "failed" -> R.string.miner_state_failed_exam
        "needs_review" -> R.string.miner_state_needs_review
        else -> R.string.miner_state_waiting_exam
    }))
    Text(stringResource(R.string.operator_share_rule))
    Text(stringResource(R.string.operator_manage))
}
