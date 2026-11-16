// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.access

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.calls.PhoneCallPort
import com.wangzan101.mika.core.calls.PhoneCallTask
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.run.ComputeMode
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.tool.ActionPass
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class CommunityCallsTest {
    private class Calls : PhoneCallPort {
        var requests = 0
        private fun result() = buildJsonObject { put("requests", ++requests) }
        override suspend fun state() = result()
        override suspend fun dial(number: String, simId: String?, task: PhoneCallTask?) = result()
        override suspend fun answer(callId: String) = result()
        override suspend fun hangup(callId: String) = result()
        override suspend fun takeover() = result()
    }

    @Test fun communityCannotListOrExecuteAnyCallToolEvenWhenPortIsSupplied() = runTest {
        val calls = Calls()
        val tools = toolDispatcher(FakeScreen(), phoneCalls = calls,
            runSpec = RunSpec(RunId("community"), "test", "test", computeMode = ComputeMode.Community))
        assertTrue(tools.definitions().none { it.name.startsWith("phone_call_") })
        for ((name, args) in listOf("state" to "{}", "dial" to """{"number":"123456"}""",
            "answer" to """{"call_id":"call-1"}""", "hangup" to """{"call_id":"call-1"}""", "takeover" to "{}")) {
            tools.execute(Part.ToolCall("call", "phone_call_$name", args), ActionPass())
        }
        assertEquals(0, calls.requests)
    }

    @Test fun ownModeRetainsNativeCallTools() = runTest {
        val calls = Calls()
        val tools = toolDispatcher(FakeScreen(), phoneCalls = calls)
        assertEquals(5, tools.definitions().count { it.name.startsWith("phone_call_") })
        tools.execute(Part.ToolCall("call", "phone_call_state", "{}"), ActionPass())
        assertEquals(1, calls.requests)
    }
}
