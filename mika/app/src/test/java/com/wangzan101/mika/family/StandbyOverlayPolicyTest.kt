// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.family
import org.junit.Assert.*
import org.junit.Test
class StandbyOverlayPolicyTest {
    @Test fun requiresScreenUnlockServiceAndSafeForeground() {
        for (screen in listOf(false, true)) for (locked in listOf(false, true))
            for (service in listOf(false, true)) for (sensitive in listOf(false, true))
                assertEquals(screen && !locked && service && !sensitive,
                    StandbyOverlayPolicy.allowed(screen, locked, service, sensitive))
    }
    @Test fun systemCredentialsAndCallsAreDeferred() {
        for (pkg in listOf("", "com.android.systemui", "com.samsung.android.incallui", "com.google.android.dialer", "com.android.permissioncontroller", "com.android.credentials"))
            assertTrue(pkg, StandbyOverlayPolicy.sensitive(pkg))
        assertFalse(StandbyOverlayPolicy.sensitive("com.example.reader"))
    }
}
