// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 给模型看的手机窗口层级;节点完整保留,只省去空字段和默认状态。 */
object UiTreeJson {
    fun windows(s: ScreenSnapshot, maxElements: Int = Int.MAX_VALUE): JsonArray = buildJsonArray {
        val elements = s.elements.filter { it.meaningful }.take(maxElements).associateBy { it.ref }
        for (w in s.windows) add(buildJsonObject {
            put("id", w.id)
            put("type", w.type)
            put("layer", w.layer)
            w.title?.let { put("title", it) }
            w.packageName?.let { put("packageName", it) }
            if (w.active) put("active", true)
            if (w.focused) put("focused", true)
            put("nodes", JsonArray(w.nodes.map { node(it, elements, null) }))
        })
    }

    // Elements own targeting data; the tree owns hierarchy and independent node properties.
    private fun node(n: UiTreeNode, elements: Map<String, UiElement>, parentBounds: List<Int>?): JsonObject =
        JsonObject(shallow(n, elements, parentBounds) +
            (if (n.children.isEmpty()) emptyMap() else mapOf("c" to JsonArray(n.children.map { node(it, elements, n.bounds) }))))

    private fun shallow(n: UiTreeNode, elements: Map<String, UiElement>, parentBounds: List<Int>?): JsonObject = buildJsonObject {
        val e = elements[n.ref]
        if (n.role != (e?.role ?: "text")) put("r", n.role)
        n.ref?.let { put("ref", it) }
        fun label(key: String, value: String?) {
            value?.let { if (e != null && it == e.name) put(key, true) else put(key, it) }
        }
        label("t", n.text)
        label("d", n.description)
        label("h", n.hintText)
        if (n.resourceId != e?.resourceId) put("id", n.resourceId?.let(::JsonPrimitive) ?: JsonNull)
        if (n.bounds != (e?.bounds ?: parentBounds)) put("b", JsonArray(n.bounds.map(::JsonPrimitive)))
        if (n.clickable) put("clickable", true)
        if (n.editable) put("editable", true)
        if (n.scrollable) put("scrollable", true)
        if (n.checkable) put("checked", n.checked)
        if (n.selected) put("selected", true)
        if (!n.enabled) put("enabled", false)
        if (n.focused) put("focused", true)
        if (n.secret) put("secret", true)
        if (n.wheel) put("wheel", true)
    }

    /** Decode at the one internal JSON consumer; legacy input remains accepted, history is never rewritten. */
    fun expandedWindows(content: JsonObject): JsonArray? {
        val windows = content["tree"] as? JsonArray ?: return null
        val elements = (content["elements"] as? JsonArray).orEmpty().mapNotNull { value ->
            val e = value as? JsonObject ?: return@mapNotNull null
            val ref = e["ref"] as? JsonPrimitive ?: return@mapNotNull null
            ref.content to e
        }.toMap()
        fun expand(n: JsonObject, parentBounds: JsonElement?): JsonObject {
            if ("role" in n) return n // Legacy nodes always have role and bounds.
            val e = elements[(n["ref"] as? JsonPrimitive)?.content]
            val bounds = n["b"] ?: e?.get("bounds") ?: parentBounds
            return buildJsonObject {
                put("role", n["r"] ?: e?.get("role") ?: JsonPrimitive("text"))
                bounds?.let { put("bounds", it) }
                for ((key, value) in n) {
                    if (key in setOf("r", "b", "c", "id")) continue
                    val label = key in setOf("t", "d", "h")
                    val expandedKey = when (key) { "t" -> "text"; "d" -> "description"; "h" -> "hintText"; else -> key }
                    put(expandedKey, if (label && value == JsonPrimitive(true)) e?.get("name") ?: JsonPrimitive("") else value)
                }
                val id = if ("id" in n) n["id"] else e?.get("resourceId")
                if (id != null && id != JsonNull) put("resourceId", id)
                (n["c"] as? JsonArray)?.let { children -> put("children", JsonArray(children.map { expand(it as JsonObject, bounds) })) }
            }
        }
        return JsonArray(windows.map { value ->
            val w = value as JsonObject
            JsonObject(w + ("nodes" to JsonArray((w["nodes"] as JsonArray).map { expand(it as JsonObject, null) })))
        })
    }

    /** 路径按窗口和子节点序号定位;同一路径的属性变化只发该节点,新增节点发完整子树。 */
    fun changes(before: ScreenSnapshot, current: ScreenSnapshot): JsonObject {
        val old = index(before)
        val now = index(current)
        val updated = buildJsonArray {
            for ((path, n) in now) {
                val previous = old[path]
                if (previous == null && parent(path) in now && parent(path) !in old) continue
                val properties = JsonObject(n - "c")
                if (previous == null || JsonObject(previous - "c") != properties) add(buildJsonObject {
                    put("path", path)
                    put("node", if (previous == null) n else properties)
                })
            }
        }
        val gone = old.keys.filterTo(LinkedHashSet()) { it !in now }
        val removed = buildJsonArray { for (path in gone) if (parent(path) !in gone) add(JsonPrimitive(path)) }
        return buildJsonObject {
            put("updated", updated)
            put("removed", removed)
        }
    }

    fun sameWindows(a: ScreenSnapshot, b: ScreenSnapshot): Boolean =
        a.windows.map { listOf(it.id, it.type, it.layer, it.title, it.packageName, it.active, it.focused) } ==
            b.windows.map { listOf(it.id, it.type, it.layer, it.title, it.packageName, it.active, it.focused) }

    private fun index(screen: ScreenSnapshot): LinkedHashMap<String, JsonObject> = LinkedHashMap<String, JsonObject>().also { out ->
        fun walk(prefix: String, nodes: JsonArray) {
            nodes.forEachIndexed { i, value ->
                val n = value as JsonObject
                val path = "$prefix/$i"
                out[path] = n
                (n["c"] as? JsonArray)?.let { walk(path, it) }
            }
        }
        for (value in windows(screen)) {
            val window = value as JsonObject
            walk("w${(window["id"] as JsonPrimitive).content}", window["nodes"] as JsonArray)
        }
    }

    private fun parent(path: String): String = path.substringBeforeLast('/', "")
}
