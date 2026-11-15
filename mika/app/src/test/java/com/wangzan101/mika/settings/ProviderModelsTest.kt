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
        assertEquals(listOf("main", "light", "vision"), built.map { it.model })
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
        assertEquals("light", built[1].model)
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
        assertThrows(IllegalStateException::class.java) { store.freezeModels(ModelSession.fresh()) }
        assertEquals(0, calls)
    }

    @Test fun hybridFreezesIndependentOwnRolesAndCommunityRouteBeforeFactoryCallbacks() {
        val built = mutableListOf<String>()
        lateinit var store: ProviderStore
        store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, _ ->
            built += cfg.model
            if (built.size == 1) store.save(ProviderConfig("node", ProviderConfig.Kind.Community, "", "operator", caps), null)
            Fake(cfg.capabilities)
        })
        store.save(ProviderConfig("node", ProviderConfig.Kind.Community, "", "compute", caps), null)
        assertTrue(store.choose(ModelRole.Main, RoleChoice.Model("node")))
        store.save(ProviderConfig("own", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "main", caps), "fixture")
        store.save(ProviderConfig("light", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "light", caps), null)
        val selected = store.freezeHybridModels(ModelSession.fresh(),
            RoleChoices(main = "own", light = RoleChoice.Model("light"), vision = RoleChoice.SameAsMain))
        assertEquals("compute", selected.communityConfig.model)
        assertEquals("operator", store.configFor(ModelRole.Main)!!.model)
        assertEquals(listOf("main", "main", "light").sorted(), built.sorted())
        assertEquals("main", selected.own.mainConfig!!.model)
        assertTrue(selected.own.distinctLight)
        assertNotNull(selected.own.vision)
        assertEquals(3, built.size)
    }

    @Test fun hybridRejectsMissingOwnOrCommunityAuxiliaryBeforeBuildingAnyAdapter() {
        var calls = 0
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, _ -> calls++; Fake(cfg.capabilities) })
        store.save(ProviderConfig("node", ProviderConfig.Kind.Community, "", "compute", caps), null)
        assertTrue(store.choose(ModelRole.Main, RoleChoice.Model("node")))
        store.save(ProviderConfig("own", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "main", caps), null)
        for (choices in listOf(RoleChoices(), RoleChoices(main = "missing"), RoleChoices(main = "node"),
            RoleChoices(main = "own", light = RoleChoice.Model("node")),
            RoleChoices(main = "own", vision = RoleChoice.Model("node")))) {
            assertThrows(IllegalArgumentException::class.java) { store.freezeHybridModels(ModelSession.fresh(), choices) }
        }
        assertEquals(0, calls)
    }

    @Test fun hybridRequiresARecognizedCommunityRouteBeforeBuildingOwnAdapters() {
        var calls = 0
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key }, modelFactory = { cfg, _, _, _ -> calls++; Fake(cfg.capabilities) })
        store.save(ProviderConfig("own", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "main", caps), null)
        assertTrue(store.choose(ModelRole.Main, RoleChoice.Model("own")))
        assertThrows(IllegalArgumentException::class.java) { store.freezeHybridModels(ModelSession.fresh(), RoleChoices(main = "own")) }
        store.save(ProviderConfig("node", ProviderConfig.Kind.Community, "", "arbitrary", caps), null)
        assertTrue(store.choose(ModelRole.Main, RoleChoice.Model("node")))
        assertThrows(IllegalArgumentException::class.java) { store.freezeHybridModels(ModelSession.fresh(), RoleChoices(main = "own")) }
        assertEquals(0, calls)
    }
    @Test fun retainedOwnRolesSurviveCommunitySelectionReloadAndProviderRemoval() {
        fun open() = ProviderStore(tmp.root, AesGcmKeyCipher { key },
            modelFactory = { cfg, _, _, _ -> Fake(cfg.capabilities) })
        val store = open()
        val own = store.connect("Own", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", null, emptyList())
        store.use(ModelRole.Main, own.id, "main", caps)
        store.use(ModelRole.Light, own.id, "light", caps)
        val choices = store.roles.value
        assertTrue(store.retainHybridOwnRoles(choices))
        store.save(ProviderConfig("node", ProviderConfig.Kind.Community, "", "operator", caps), null)
        assertTrue(store.choose(ModelRole.Main, RoleChoice.Model("node")))
        store.choose(ModelRole.Light, RoleChoice.SameAsMain)
        val loaded = open()
        assertEquals(choices, loaded.hybridOwnRoles.value)
        val frozen = loaded.freezeHybridModels(ModelSession.fresh())
        assertEquals("operator", frozen.communityConfig.model)
        assertEquals("main", frozen.own.mainConfig!!.model)
        assertTrue(frozen.own.distinctLight)
        assertFalse(loaded.retainHybridOwnRoles(RoleChoices(main = "node")))
        assertFalse(loaded.retainHybridOwnRoles(RoleChoices(main = "missing")))
        assertEquals(choices, loaded.hybridOwnRoles.value)
        loaded.removeProvider(own.id)
        assertNull(open().hybridOwnRoles.value.main)
        assertThrows(IllegalArgumentException::class.java) { loaded.freezeHybridModels(ModelSession.fresh()) }
    }

    @Test fun trustedHybridSelectionRequiresBothRoutesAndClearsOnOwnRemoval() {
        fun open() = ProviderStore(tmp.root, AesGcmKeyCipher { key },
            modelFactory = { cfg, _, _, _ -> Fake(cfg.capabilities) })
        val store = open()
        assertFalse(store.hybridSelected.value)
        assertFalse(store.selectHybrid(true))
        val own = store.connect("Own", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", null, emptyList())
        store.use(ModelRole.Main, own.id, "main", caps)
        assertTrue(store.retainHybridOwnRoles(store.roles.value))
        assertFalse(store.selectHybrid(true))
        store.save(ProviderConfig("node", ProviderConfig.Kind.Community, "", "operator", caps), null)
        assertTrue(store.choose(ModelRole.Main, RoleChoice.Model("node")))
        assertTrue(store.selectHybrid(true))
        val loaded = open()
        assertTrue(loaded.hybridSelected.value)
        assertTrue(loaded.selectHybrid(false))
        assertFalse(open().hybridSelected.value)
        assertTrue(loaded.selectHybrid(true))
        loaded.save(loaded.configFor(ModelRole.Main)!!.copy(model = "unsupported"), null)
        assertFalse(loaded.hybridSelected.value)
        assertFalse(open().hybridSelected.value)
        loaded.save(loaded.configFor(ModelRole.Main)!!.copy(model = "compute"), null)
        assertFalse(loaded.hybridSelected.value)
        assertTrue(loaded.selectHybrid(true))
        loaded.removeProvider(own.id)
        assertFalse(loaded.hybridSelected.value)
        assertFalse(open().hybridSelected.value)
        assertFalse(loaded.selectHybrid(true))
    }

    @Test fun missingExplicitHybridAuxiliaryIsRejectedBeforeAdapterConstruction() {
        var calls = 0
        val store = ProviderStore(tmp.root, AesGcmKeyCipher { key },
            modelFactory = { cfg, _, _, _ -> calls++; Fake(cfg.capabilities) })
        store.save(ProviderConfig("node", ProviderConfig.Kind.Community, "", "compute", caps), null)
        assertTrue(store.choose(ModelRole.Main, RoleChoice.Model("node")))
        store.save(ProviderConfig("own", ProviderConfig.Kind.OpenAiCompat, "https://example.test/v1", "main", caps), null)
        for (choices in listOf(
            RoleChoices(main = "own", light = RoleChoice.Model("missing")),
            RoleChoices(main = "own", vision = RoleChoice.Model("missing")))) {
            assertThrows(IllegalArgumentException::class.java) { store.freezeHybridModels(ModelSession.fresh(), choices) }
        }
        assertEquals(0, calls)
    }

}
