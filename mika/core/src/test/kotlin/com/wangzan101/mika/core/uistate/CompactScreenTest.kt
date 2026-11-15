// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.uistate

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.tool.execute
import com.wangzan101.mika.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Explicit node semantics exercise the wire reader, rather than just comparing wire strings. */
object CompactScreenFixtures {
    fun screens(): Map<String, ScreenSnapshot> {
        fun screen(nodes: List<UiTreeNode>, overlay: Boolean = false): ScreenSnapshot {
            val elements = mutableListOf<UiElement>()
            fun visit(n: UiTreeNode) {
                n.ref?.let { elements += UiElement(it, n.role, n.text ?: n.description ?: "聚合行标签", 500, 150,
                    n.bounds, n.resourceId, n.clickable, n.editable, n.scrollable, focused = n.focused, secret = n.secret) }
                n.children.forEach(::visit)
            }
            nodes.forEach(::visit)
            val windows = listOf(UiWindowTree(7, "application", 0, "设置", "com.example", true, true, nodes)) +
                if (overlay) listOf(UiWindowTree(9, "input_method", 5, "键盘", "ime", false, true, emptyList())) else emptyList()
            return ScreenSnapshot("p1", "com.example", elements, 1000, 1000, windows = windows)
        }
        val row = UiTreeNode("row", listOf(0, 100, 999, 200), ref = "@e1", clickable = true,
            description = "网络设置", resourceId = "pkg:id/row", children = listOf(
                UiTreeNode("text", listOf(0, 100, 999, 200), text = "网络设置")))
        val toggle = row.copy(children = row.children + UiTreeNode("switch", listOf(800, 120, 950, 180),
            ref = "@e2", checkable = true, checked = false, clickable = true, selected = true, enabled = false))
        val dialog = UiTreeNode("dialog", listOf(100, 250, 900, 750), text = "是否继续？", children = listOf(
            UiTreeNode("button", listOf(650, 600, 850, 700), ref = "@e1", text = "继续", clickable = true)))
        val form = UiTreeNode("form", listOf(0, 0, 999, 700), children = listOf(
            UiTreeNode("edit", listOf(0, 100, 999, 200), ref = "@e1", text = "***", hintText = "密码",
                editable = true, focused = true, secret = true, description = "登录密码"),
            UiTreeNode("edit", listOf(0, 300, 999, 400), ref = "@e2", hintText = "用户名", editable = true)))
        val dialogBase = screen(listOf(row))
        val dialogContent = dialog.copy(children = dialog.children.map { it.copy(ref = "@e2") })
        val dialogScreen = dialogBase.copy(
            elements = dialogBase.elements + screen(listOf(dialogContent)).elements,
            windows = dialogBase.windows + UiWindowTree(8, "system", 4, "Confirm", "android", true, true, listOf(dialogContent)))
        return linkedMapOf("settings" to screen(listOf(row)), "toggle" to screen(listOf(toggle)),
            "dialog" to dialogScreen,
            "form-ime" to screen(listOf(form), overlay = true),
            "long-list" to screen(listOf(UiTreeNode("list", listOf(0, 0, 999, 999), scrollable = true,
                children = (1..80).map { row.copy(ref = "@e$it", description = "项目$it") }))))
    }
}

class CompactScreenTest {
    private fun decode(n: JsonObject, elements: Map<String, UiElement>, parent: List<Int>?): UiTreeNode {
        val ref = n["ref"]?.jsonPrimitive?.content
        val e = elements[ref]
        fun text(k: String): String? = n[k]?.jsonPrimitive?.let { if (!it.isString && it.booleanOrNull == true) e!!.name else it.content }
        fun flag(k: String, default: Boolean = false) = n[k]?.jsonPrimitive?.boolean ?: default
        val bounds = n["b"]?.jsonArray?.map { it.jsonPrimitive.int } ?: e?.bounds ?: parent!!
        return UiTreeNode(n["r"]?.jsonPrimitive?.content ?: e?.role ?: "text", bounds, ref,
            text("t"), text("d"), text("h"), if ("id" in n) n["id"]?.jsonPrimitive?.contentOrNull else e?.resourceId,
            flag("clickable"), flag("editable"), flag("scrollable"), "checked" in n, flag("checked"),
            flag("selected"), flag("enabled", true), flag("focused"), flag("secret"), flag("wheel"),
            n["c"]?.jsonArray?.map { decode(it.jsonObject, elements, bounds) }.orEmpty())
    }

    @Test fun fiveInterfacesRoundTripEveryNodeAndAllStates() {
        for ((name, s) in CompactScreenFixtures.screens()) {
            val elements = s.elements.associateBy { it.ref }
            val windows = UiTreeJson.windows(s)
            assertEquals(name, s.windows.size, windows.size)
            for ((old, compact) in s.windows.zip(windows)) {
                val decoded = compact.jsonObject["nodes"]!!.jsonArray.map { decode(it.jsonObject, elements, null) }
                assertEquals(name, old.nodes, decoded)
                for (key in listOf("id", "type", "layer", "title", "packageName", "active", "focused")) {
                    // Window metadata is intentionally unchanged by compaction.
                    assertFalse(name, compact.jsonObject[key] is JsonArray)
                }
            }
        }
    }

    @Test fun unmatchedRefsAndTruncatedElementsKeepSelfContainedProperties() {
        val s = CompactScreenFixtures.screens().getValue("toggle")
        val tree = UiTreeJson.windows(s, maxElements = 0).single().jsonObject
        val root = tree["nodes"]!!.jsonArray.single().jsonObject
        assertEquals("row", root["r"]!!.jsonPrimitive.content)
        assertNotNull(root["b"])
        assertEquals(s.windows.single().nodes, listOf(decode(root, emptyMap(), null)))
    }

    @Test fun divergentFieldsAndLiteralTrueAreNeverCollapsed() {
        val s = CompactScreenFixtures.screens().getValue("settings")
        val old = s.windows.single().nodes.single().copy(role = "custom", bounds = listOf(1, 2, 3, 4),
            resourceId = null, text = "true", description = s.elements.single().name, hintText = "unique", wheel = true)
        val modified = s.copy(windows = listOf(s.windows.single().copy(nodes = listOf(old))))
        val out = UiTreeJson.windows(modified).single().jsonObject["nodes"]!!.jsonArray.single().jsonObject
        assertEquals(JsonNull, out["id"])
        assertTrue(out["t"]!!.jsonPrimitive.isString)
        assertEquals(old, decode(out, s.elements.associateBy { it.ref }, null))
    }

    @Test fun centerOnlyDisappearsWhenExactlyDerivableAndTreeExists() {
        val s = CompactScreenFixtures.screens().getValue("settings")
        val e = s.elements.single().copy(centerX = 500, centerY = 150, bounds = listOf(0, 100, 999, 200))
        fun out(screen: ScreenSnapshot) = ScreenJson.elements(screen, 10).first.single().jsonObject
        assertFalse(out(s.copy(elements = listOf(e))).containsKey("center"))
        assertTrue(out(s.copy(elements = listOf(e.copy(centerX = 501)))).containsKey("at"))
        assertTrue(out(s.copy(elements = listOf(e), windows = emptyList())).containsKey("center"))
    }

    @Test fun inheritedNamesChangeThroughElementsAndDefaultsResetInTreePatch() {
        val s = CompactScreenFixtures.screens().getValue("long-list")
        fun model(screen: ScreenSnapshot, id: String) = ModelSnapshot(id, screen, screen.elements, screen.elements.associate { it.ref to it.ref })
        val base = model(s, "s1")
        val nodes = s.windows.single().nodes.single().let { root -> root.copy(children = root.children.mapIndexed { i, n ->
            if (i == 0) n.copy(description = "改名", selected = true, focused = true) else n
        }) }
        val next = model(s.copy(elements = s.elements.mapIndexed { i, e -> if (i == 0) e.copy(name = "改名") else e },
            windows = listOf(s.windows.single().copy(nodes = listOf(nodes)))), "s2")
        val diff = InterfaceUpdate.between(base, next, true, Int.MAX_VALUE)
        assertEquals("false", diff["full"]!!.jsonPrimitive.content)
        assertEquals(base.snapshotId, diff["base"]!!.jsonPrimitive.content)
        assertEquals("改名", diff["changed"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
        val update = diff["treeChanges"]!!.jsonObject["updated"]!!.jsonArray.single().jsonObject
        assertEquals("w7/0/0", update["path"]!!.jsonPrimitive.content)
        assertEquals("true", update["node"]!!.jsonObject["selected"]!!.jsonPrimitive.content)
        val reset = InterfaceUpdate.between(next, model(s, "s3"), true, Int.MAX_VALUE)
        assertFalse(reset["treeChanges"]!!.jsonObject["updated"]!!.jsonArray.single().jsonObject["node"]!!.jsonObject.containsKey("selected"))
        assertEquals("true", InterfaceUpdate.between(base, next, false)["full"]!!.jsonPrimitive.content)
        assertEquals("true", InterfaceUpdate.between(null, next, true)["full"]!!.jsonPrimitive.content)
        val noTree = model(s.copy(windows = emptyList()), "s4")
        assertEquals("true", InterfaceUpdate.between(next, noTree, true)["full"]!!.jsonPrimitive.content)
    }

    @Test fun parentBoundsChangeMakesUnchangedChildBoundsExplicitInDiff() {
        val s = CompactScreenFixtures.screens().getValue("settings")
        val root = s.windows.single().nodes.single()
        val changed = s.copy(windows = listOf(s.windows.single().copy(nodes = listOf(root.copy(bounds = listOf(1, 2, 3, 4))))))
        // The ref still supplies original bounds, so the child continues to need no extra field.
        val patches = UiTreeJson.changes(s, changed)["updated"]!!.jsonArray
        assertEquals(listOf("w7/0", "w7/0/0"), patches.map { it.jsonObject["path"]!!.jsonPrimitive.content })
        assertNotNull(patches[1].jsonObject["node"]!!.jsonObject["b"])
    }

    @Test fun compactPatchesReplayAddedSubtreesRemovedChildrenAndStateResets() {
        val s = CompactScreenFixtures.screens().getValue("long-list")
        val root = s.windows.single().nodes.single()
        val edited = root.copy(children = root.children.mapIndexed { i, n -> when (i) {
            0 -> n.copy(selected = true, children = emptyList())
            79 -> n.copy(children = n.children + UiTreeNode("text", n.bounds, text = "extra"))
            else -> n
        } })
        val current = s.copy(windows = listOf(s.windows.single().copy(nodes = listOf(edited))))
        fun model(screen: ScreenSnapshot, id: String) = ModelSnapshot(id, screen, screen.elements, screen.elements.associate { it.ref to it.ref })
        val diff = InterfaceUpdate.between(model(s, "s1"), model(current, "s2"), true, Int.MAX_VALUE)
        assertEquals("false", diff["full"]!!.jsonPrimitive.content)
        var nodes = UiTreeJson.windows(s).single().jsonObject["nodes"]!!.jsonArray
        fun edit(list: JsonArray, path: List<Int>, value: JsonObject?): JsonArray {
            val index = path.first()
            val result = list.toMutableList()
            if (path.size > 1) {
                val parent = result[index].jsonObject
                val children = edit(parent["c"]!!.jsonArray, path.drop(1), value)
                result[index] = if (children.isEmpty()) JsonObject(parent - "c") else JsonObject(parent + ("c" to children))
            } else if (value == null) result.removeAt(index)
            else if (index == result.size) result.add(value)
            else {
                val old = result[index].jsonObject
                result[index] = JsonObject(value + old.filterKeys { it == "c" })
            }
            return JsonArray(result)
        }
        val changes = diff["treeChanges"]!!.jsonObject
        for (removed in changes["removed"]!!.jsonArray) {
            nodes = edit(nodes, removed.jsonPrimitive.content.split('/').drop(1).map(String::toInt), null)
        }
        for (update in changes["updated"]!!.jsonArray) {
            val u = update.jsonObject
            nodes = edit(nodes, u["path"]!!.jsonPrimitive.content.split('/').drop(1).map(String::toInt), u["node"]!!.jsonObject)
        }
        assertEquals(UiTreeJson.windows(current).single().jsonObject["nodes"], nodes)
        val bulk = current.copy(windows = listOf(s.windows.single().copy(nodes = listOf(root.copy(children = root.children.map { it.copy(selected = true) })))))
        assertEquals("true", InterfaceUpdate.between(model(s, "s1"), model(bulk, "s3"), true, Int.MAX_VALUE)["full"]!!.jsonPrimitive.content)
    }

    @Test fun sessionObservationKeepsRefResolutionAndProtectedPagePolicy() = runTest {
        val s = CompactScreenFixtures.screens().getValue("toggle")
        val tapped = mutableListOf<Pair<String, String>>()
        val port = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot() = s
            override suspend fun peek() = s
            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                tapped += ref to snapshotId
                return ActionResult.Ok(changed = false)
            }
        }
        val d = toolDispatcher(port)
        val obs = d.executeRead(Part.ToolCall("o", "phone_observe", "{}" )).content
        val ref = obs["elements"]!!.jsonArray.first().jsonObject["ref"]!!.jsonPrimitive.content
        val id = obs["snapshotId"]!!.jsonPrimitive.content
        val action = d.execute(Part.ToolCall("t", "phone_tap", """{"ref":"$ref","snapshotId":"$id"}"""))
        assertEquals("true", action.content["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("@e1" to "p1"), tapped)
        var screenshots = 0
        val protectedPort = object : ScreenPort by port {
            override suspend fun snapshot() = s.copy(protected = true)
            override suspend fun screenshotPng(): ByteArray? { screenshots++; return byteArrayOf(1) }
        }
        val protected = toolDispatcher(protectedPort).executeRead(Part.ToolCall("p", "phone_observe", "{}"))
        assertEquals("true", protected.content["protected"]!!.jsonPrimitive.content)
        assertNull(protected.image)
        assertEquals(0, screenshots)
    }
}
