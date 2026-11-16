// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import android.view.Gravity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wangzan101.mika.R
import com.wangzan101.mika.cap.phone.a11y.*
import com.wangzan101.mika.core.shell.GuidedSetup

/** Owned by the running guide; the existing host removes windows on service disconnect. */
internal class SetupHighlightOverlay {
    private var window: OverlayWindow? = null
    private var owner: ScreenAccessibility? = null
    private var current = emptyList<GuidedSetup.Highlight>()
    fun clear() { window?.close(); window = null; owner = null; current = emptyList() }
    fun show(service: ScreenAccessibility, hints: List<GuidedSetup.Highlight>) {
        if (hints.isEmpty()) { clear(); return }
        if (owner === service && current == hints && window?.isOpen == true) return
        clear()
        val area = service.overlays().area()
        val density = service.resources.displayMetrics.density
        window = service.overlays().open(OverlayPlacement(Gravity.TOP or Gravity.LEFT), touchable = false) {
            val tap = stringResource(R.string.setup_highlight_tap)
            val seven = stringResource(R.string.setup_highlight_build)
            Canvas(Modifier.size((area.width / density).dp, (area.height / density).dp)) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.RED
                    textSize = 32 * density
                    isFakeBoldText = true
                }
                for (hint in hints) {
                    val b = hint.bounds
                    drawRect(Color.Red, Offset(b.left.toFloat(), b.top.toFloat()),
                        Size((b.right - b.left).toFloat(), (b.bottom - b.top).toFloat()), style = Stroke(4 * density))
                    val text = if (hint.text == GuidedSetup.HintText.BuildSeven) seven else tap
                    val x = b.left.toFloat().coerceIn(0f, (size.width - paint.measureText(text)).coerceAtLeast(0f))
                    val y = if (b.top > 48 * density) b.top - 8 * density
                        else (b.bottom + 38 * density).coerceAtMost(size.height - 8 * density)
                    paint.style = android.graphics.Paint.Style.STROKE
                    paint.strokeWidth = 5 * density
                    paint.color = android.graphics.Color.WHITE
                    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
                    paint.style = android.graphics.Paint.Style.FILL
                    paint.color = android.graphics.Color.RED
                    drawContext.canvas.nativeCanvas.drawText(text, x, y, paint)
                }
            }
        }
        owner = service
        current = hints
    }
}
