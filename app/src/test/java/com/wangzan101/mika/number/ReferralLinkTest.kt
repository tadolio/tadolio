// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.number

import org.junit.Assert.*
import org.junit.Test

class ReferralLinkTest {
    @Test fun acceptsOnlyTheExactSchemeAndEightDigitNumber() {
        assertEquals("12345678", ReferralLink.number("tadolio://invite/12345678"))
        for (link in listOf(null, "https://tadolio.com/i/12345678", "hivo://invite/12345678",
            "tadolio://invite/12345678?bind=true", "tadolio://invite/1234567", "tadolio://invite/12345678/extra"))
            assertNull(ReferralLink.number(link))
    }
}
