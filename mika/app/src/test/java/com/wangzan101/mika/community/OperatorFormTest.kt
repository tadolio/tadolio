// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community
import org.junit.Assert.*
import org.junit.Test
class OperatorFormTest {
    @Test fun statusAndCopyRemainExplicit() {
        assertEquals(MinerState.WAITING_EXAM,MinerState.admission("waiting_exam",true,false))
        assertEquals(MinerState.EXAM,MinerState.admission("exam_running",true,false))
        assertEquals(MinerState.NEEDS_REVIEW,MinerState.admission("needs_review",true,true))
        assertEquals(MinerState.ONLINE,MinerState.admission("passed",true,true))
        assertEquals(MinerState.PLATFORM_PAUSED,MinerState.admission("passed",false,true))
        val en=com.wangzan101.mika.i18n.ResFileTexts.english.strings
        val zh=com.wangzan101.mika.i18n.ResFileTexts.chinese.strings
        assertEquals("你的服务商现在不在线",zh.getValue("phone_operator_offline"))
        assertEquals("这次先用随机社区算力",zh.getValue("phone_operator_once"))
    }
    @Test fun requiresDeclarationAndValidShareAndCapacity() {
        assertEquals(20,OperatorForm.DEFAULT_MAX_USERS)
        for(share in listOf(0,100)) assertTrue(OperatorForm.valid("Family","","",share,20,true))
        for(share in listOf(-1,101)) assertFalse(OperatorForm.valid("Family","","",share,20,true))
        assertFalse(OperatorForm.valid("Family","","",0,20,false))
        assertFalse(OperatorForm.valid("Family","","",0,0,true))
        assertFalse(OperatorForm.valid("","","",0,20,true))
        assertFalse(OperatorForm.valid("a".repeat(25),"","",0,20,true))
        assertEquals(listOf(5000000L,20000000L,50000000L),OperatorForm.presets)
    }
}
