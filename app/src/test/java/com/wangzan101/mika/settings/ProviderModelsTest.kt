// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.*
import com.wangzan101.mika.core.usage.UsagePurpose
import com.wangzan101.mika.core.usage.UsageSink
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator

class ProviderModelsTest {
    @Test fun legacyHybridTrueKeepsTheSelectedCommunityModel() {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key })
        store.save(ProviderConfig("node", ProviderConfig.Kind.Community, "", "compute", caps), null)
        assertTrue(store.choose(ModelRole.Main, RoleChoice.Model("node")))
        val config = java.io.File(tmp.root, "providers.json")
        config.writeText(config.readText().replaceFirst("{", "{\"hybridSelected\":true,"))
        val restored = ProviderStore(tmp.root, AesGcmKeyCipher { key })
        assertEquals(ProviderConfig.Kind.Community, restored.configFor(ModelRole.Main)?.kind)
        assertEquals("compute", restored.configFor(ModelRole.Main)?.model)
    }
    @get:Rule val tmp = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caps = Capabilities(true, false, 32000, false)
    private class Fake(override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
            ModelReply(listOf(Part.Text("ok")), StopReason.EndTurn, Usage(1, 1))
    }

    @Test fun allRolesCaptureSettingsBeforeTheFirstAdapterIsBuilt() = runTest {
        val built = mutableListOf<ProviderConfig>()
        val setups = mutableListOf<ClientSetup>()
        val keys = mutableListOf<String?>()
        val usage = mutableListOf<UsagePurpose>()
        lateinit var store: ProviderStore
        var changeSettings: () -> Unit = {}
        store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, usage = UsageSink { source, _ -> usage += source.purpose },
            modelFactory = { cfg, apiKey, _, setup ->
                built += cfg
                setups += setup
                keys += apiKey
                if (built.size == 1) changeSettings()
                Fake(cfg.capabilities)
            })
        val provider = store.connect("Own", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "fixture-key",
            listOf(ListedModel("main"), ListedModel("light"), ListedModel("vision")), sessionHeader = "X-Session")
        store.use(ModelRole.Main, provider.id, "main", caps)
        store.use(ModelRole.Light, provider.id, "light", caps)
        store.use(ModelRole.Vision, provider.id, "vision", caps)
        val lightId = store.configFor(ModelRole.Light)!!.id
        assertTrue(store.setModelContext(lightId, ModelContextSettings(windowTokens = 64000)))
        changeSettings = {
            store.use(ModelRole.Main, provider.id, "changed", caps)
            store.choose(ModelRole.Light, RoleChoice.SameAsMain)
            store.choose(ModelRole.Vision, RoleChoice.Off)
        }
        val selection = store.freezeModels(ModelSession.of("frozen-run"))
        assertEquals(setOf("main", "light", "vision"), built.map { it.model }.toSet())
        assertEquals(listOf("fixture-key", "fixture-key", "fixture-key"), keys)
        assertEquals("main", selection.mainConfig!!.model)
        assertTrue(selection.distinctLight)
        assertEquals(64000, selection.light!!.capabilities.contextTokens)
        assertTrue(selection.vision!!.capabilities.vision)
        assertTrue(setups.all { it.headers["X-Session"] == ModelSession.of("frozen-run").id })
        selection.main!!.complete("base", emptyList(), emptyList())
        selection.light.complete("base", emptyList(), emptyList())
        selection.vision.complete("base", emptyList(), emptyList())
        assertEquals(listOf(UsagePurpose.Main, UsagePurpose.Light, UsagePurpose.Vision), usage)
        assertEquals(3, built.size)
        assertEquals("changed", store.configFor(ModelRole.Main)!!.model)
        assertEquals(1, built.count { it.model == "light" })
    }

    @Test fun sameAsMainAndVisionOffKeepTheirSelections() {
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, _ -> Fake(cfg.capabilities) })
        val p = store.connect("Own", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", null, emptyList())
        store.use(ModelRole.Main, p.id, "main", caps)
        val selected = store.freezeModels(ModelSession.fresh())
        assertNotNull(selected.main)
        assertNotNull(selected.light)
        assertNull(selected.vision)
        assertFalse(selected.distinctLight)
    }

    @Test fun noMainDoesNotConstructAnAuxiliaryAdapter() {
        var calls = 0
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, _ -> calls++; Fake(cfg.capabilities) })
        val p = store.connect("Own", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", null, emptyList())
        store.use(ModelRole.Light, p.id, "light", caps)
        val selected = store.freezeModels(ModelSession.fresh())
        assertNull(selected.main)
        assertNull(selected.light)
        assertEquals(0, calls)
    }

    @Test fun communityCannotBeConstructedAsAnOwnAdapter() {
        var calls = 0
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, _ -> calls++; Fake(cfg.capabilities) })
        val cfg = ProviderConfig("community", ProviderConfig.Kind.Community, "", "compute", caps)
        store.save(cfg, null)
        assertTrue(store.choose(ModelRole.Main, RoleChoice.Model(cfg.id)))
        assertNull(store.freezeModels(ModelSession.fresh()).main)
        assertEquals(0, calls)
    }

}
