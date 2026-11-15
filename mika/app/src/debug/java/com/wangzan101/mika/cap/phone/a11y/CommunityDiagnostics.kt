// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.cap.phone.a11y

internal object CommunityDiagnostics {
    fun hidden(reasons: List<String>, before: List<String?>, after: List<String?>, windows: List<String>) {
        if (reasons.isNotEmpty()) android.util.Log.d("HivoCommunity", "hidden reasons=$reasons before=$before after=$after windows=$windows")
    }
}
