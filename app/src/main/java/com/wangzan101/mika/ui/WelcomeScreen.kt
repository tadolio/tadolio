// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.res.stringResource
import com.wangzan101.mika.settings.ProviderStore
import com.wangzan101.mika.about.HivoConfig
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.wangzan101.mika.R

/** First greeting is local and works before a model is configured. */
@Composable
fun WelcomeScreen(store: ProviderStore, onStart: () -> Unit, onOwnModel: () -> Unit, onProviderSelected: () -> Unit) {
    var moving by remember { mutableStateOf(false) }
    var showingNumber by remember { mutableStateOf(false) }
    var invite by remember { mutableStateOf(false) }
    if (showingNumber) { NumberScreen(back = { showingNumber = false }); return }
    if (moving) { NumberMoveScreen(onTransferred = { moving = false; showingNumber = true }) { moving = false }; return }
    WelcomeContent(HivoConfig.communityEnabled,
        onStart = onStart, onOwnModel = onOwnModel, onNumber = { moving = true },
        invite = { invite = true })
    if (invite) SetupDetails({ invite = false }) { CommunityControls(store, onboarding = true, onChosen = onProviderSelected) }
}

@Composable
internal fun WelcomeContent(community: Boolean, onStart: () -> Unit, onOwnModel: () -> Unit, onNumber: () -> Unit, invite: () -> Unit) {
    SetupPage(
        title = stringResource(R.string.t60_welcome_title),
        body = stringResource(R.string.t60_welcome_body),
        primary = stringResource(if (community) R.string.t60_start_free else R.string.t60_connect_model),
        onPrimary = if (community) onStart else onOwnModel,
        notice = if (community) stringResource(R.string.t60_community_notice) else null,
        links = {
            TextButton(onOwnModel) { Text(stringResource(R.string.t60_own_model)) }
            TextButton(onNumber) { Text(stringResource(R.string.t42_have_number)) }
        },
        detailsLabel = stringResource(R.string.t60_free_details),
        details = if (community) ({ close ->
            Text(stringResource(R.string.t60_free_explanation))
            TextButton({ close(); invite() }) { Text(stringResource(R.string.operator_invite)) }
        }) else null,
    )
}
