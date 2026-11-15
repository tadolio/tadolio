// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.components.ListRow
import com.wangzan101.mika.ui.theme.MikaIcons

@Composable
internal fun ElderModeRow(enabled: Boolean, setEnabled: (Boolean) -> Unit) {
    var confirmEnable by remember { mutableStateOf(false) }
    ListRow(MikaIcons.Heart, stringResource(R.string.elder_mode), stringResource(R.string.elder_mode_sub), first = true,
        onClick = {
            if (elderModeNeedsConfirmation(enabled)) confirmEnable = true else setEnabled(false)
        }) {
        Switch(enabled, onCheckedChange = null)
    }
    if (confirmEnable) AlertDialog(
        onDismissRequest = { confirmEnable = false },
        title = { Text(stringResource(R.string.elder_enable_title)) },
        text = { Text(stringResource(R.string.elder_enable_body)) },
        confirmButton = { TextButton(onClick = { confirmEnable = false; setEnabled(true) }) {
            Text(stringResource(R.string.elder_enable_confirm))
        } },
        dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Keep the input and its action together when the keyboard reduces the viewport. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ElderTaskInput(text: String, onText: (String) -> Unit, send: () -> Unit,
    imeBottom: Int = WindowInsets.ime.getBottom(LocalDensity.current)) {
    val requester = remember { BringIntoViewRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, imeBottom) {
        if (focused) {
            withFrameNanos { }
            requester.bringIntoView()
        }
    }
    Column(Modifier.fillMaxWidth().bringIntoViewRequester(requester), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        OutlinedTextField(text, onText, label = { Text(stringResource(R.string.elder_type), fontSize = 22.sp) },
            textStyle = TextStyle(fontSize = 24.sp, color = Color.Black),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.testTag("elder-input"))
        Button(send, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("elder-send")) {
            Text(stringResource(R.string.elder_send), fontSize = 24.sp)
        }
    }
}

@Composable
internal fun ElderRecentResult(text: String) {
    MarkdownText(text, TextStyle(fontSize = 24.sp, lineHeight = 34.sp, color = Color.Black))
}

internal fun elderModeNeedsConfirmation(enabled: Boolean): Boolean = !enabled
