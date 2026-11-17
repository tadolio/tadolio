// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MinerPayeesTest {
    @Test fun optionalNumbersAndLegacyDefaults() {
        assertEquals(listOf("12345678" to ""),MinerPayees.rows(buildJsonObject {},"12345678"))
        assertEquals(listOf("87654321" to ""),MinerPayees.rows(buildJsonObject { put("number","87654321") },"12345678"))
        assertEquals(listOf("" to ""),MinerPayees.rows(buildJsonObject { put("numbers",JsonArray(emptyList())) },"12345678"))
        assertTrue(MinerPayees.normalize(listOf("" to "")).isEmpty())
        assertTrue(MinerPolicy.canEnable("https://example.invalid/v1","model","synthetic",100,20,"",requireNumber=false))
        assertFalse(MinerPolicy.canEnable("https://example.invalid/v1","model","synthetic",100,20,"",requireNumber=true))
    }
    @Test fun sharesAndValidation() {
        val equal=MinerPayees.normalize(listOf("11111111" to "","22222222" to "","33333333" to ""))
        assertEquals(listOf(34,33,33),equal.map { it.jsonObject.getValue("percent").jsonPrimitive.int })
        assertEquals(60,MinerPayees.normalize(listOf("11111111" to "60","22222222" to "40"))[0].jsonObject.getValue("percent").jsonPrimitive.int)
        for(rows in listOf(listOf("11111111" to "99"),listOf("11111111" to "","11111111" to ""),
            listOf("123" to ""),listOf("11111111" to "20","22222222" to ""),List(6) { "11111111" to "" }))
            assertTrue(runCatching { MinerPayees.normalize(rows) }.isFailure)
    }
}
