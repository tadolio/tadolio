// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import com.wangzan101.mika.core.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class ProviderKeysTest {
    @get:Rule val tmp = TemporaryFolder()
    private val cipher = AesGcmKeyCipher { key }
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val caps = Capabilities(true, true, 32000, false)
    private val calls = mutableListOf<String?>()
    private fun store() = ProviderStore(tmp.root, cipher, modelFactory = { cfg, apiKey, _, _ -> object : ChatModel {
        override val capabilities = cfg.capabilities
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            calls += apiKey
            if (apiKey?.startsWith("synthetic-bad") == true) throw ModelError.Auth()
            return ModelReply(listOf(Part.Text(cfg.model)), StopReason.EndTurn, null)
        }
    } })
    private fun connect(store: ProviderStore, name: String, key: String) = store.connect(name, ProviderConfig.Kind.OpenAiCompat,
        "https://$name.invalid/v1", key, listOf(ListedModel("synthetic-model")))

    @Test fun migratesEncryptedSingleKeyAndMaintainsOrderThroughRestart() {
        var store = store()
        val p = connect(store, "first", "synthetic-one")
        val file = File(tmp.root, "provider-keys.json")
        val legacy = Json.parseToJsonElement(file.readText()).jsonObject.getValue(p.id).jsonArray.single()
        file.writeText(buildJsonObject { put(p.id, legacy) }.toString())
        store = store()
        assertEquals("synthetic-one", store.providerKey(p.id))
        store.addProviderKey(p.id, "synthetic-two")
        store.addProviderKey(p.id, "synthetic-three")
        store.moveProviderKey(p.id, 2, 0)
        assertEquals(listOf("hree", "-one", "-two"), store.keySummaries(p.id).map { it.suffix })
        store.removeProviderKey(p.id, 1)
        store = store()
        assertEquals("synthetic-three", store.providerKey(p.id))
        assertEquals(2, store.keySummaries(p.id).size)
        assertFalse(file.readText().contains("synthetic"))
        store.updateProviderKey(p.id, "synthetic-two")
        assertEquals(1, store.keySummaries(p.id).size)
    }

    @Test fun rotatesKeysPersistsFailureAndUsesFrozenBackupOnlyOncePerTask() = runTest {
        val store = store()
        val p = connect(store, "first", "synthetic-bad-one")
        store.addProviderKey(p.id, "synthetic-bad-two")
        val backup = connect(store, "backup", "synthetic-good")
        store.use(ModelRole.Main, p.id, "synthetic-model", caps)
        assertTrue(store.useBackup(backup.id, "backup-model", caps))
        var notices = 0
        val frozen = store.freezeModels(ModelSession.fresh()) { _, _ -> notices++ }
        frozen.main!!.complete("", emptyList(), emptyList())
        frozen.main.complete("", emptyList(), emptyList())
        frozen.light!!.complete("", emptyList(), emptyList())
        assertEquals(listOf("synthetic-bad-one", "synthetic-bad-two", "synthetic-good", "synthetic-good", "synthetic-good"), calls)
        assertEquals(1, notices)
        val restored = store()
        assertEquals(2, restored.keySummaries(p.id).count { it.status == KeyFailure.invalid })
        assertNotNull(restored.backup.value)
        assertNull(restored.providerKey(p.id))
        restored.updateProviderKey(p.id, "synthetic-edited")
        assertEquals("synthetic-edited", restored.allowanceKey(p, restored.providerKeyRevisions.value.getValue(p.id)))
    }

    @Test fun rejectsCommunityBackupAndMultiKeyForSubscriptionAccounts() {
        val store = store()
        val community = store.connect("community", ProviderConfig.Kind.Community, "https://community.invalid", null, emptyList())
        assertFalse(store.useBackup(community.id, "compute", caps))
        val account = store.connect("account", ProviderConfig.Kind.ChatGpt, "https://account.invalid", null, emptyList())
        assertThrows(IllegalArgumentException::class.java) { store.addProviderKey(account.id, "synthetic-one") }
    }

    @Test fun t59BackupAndEncryptedKeyArrayMigrateToOneFallbackChainIdempotently() = runTest {
        var store = store()
        val primary = connect(store, "legacy-primary", "synthetic-bad-one")
        store.addProviderKey(primary.id, "synthetic-good")
        val backup = connect(store, "legacy-backup", "synthetic-backup")
        store.use(ModelRole.Main, primary.id, "synthetic-model", caps)
        store.useBackup(backup.id, "backup-model", caps)
        val config = File(tmp.root, "providers.json")
        val current = Json.parseToJsonElement(config.readText()).jsonObject
        val backupId = store.backup.value!!
        val legacy = JsonObject(current + ("backup" to JsonPrimitive(backupId)) + ("uses" to
            JsonObject(current.getValue("uses").jsonObject - "MainFallbacks")))
        config.writeText(legacy.toString())
        val credentials = File(tmp.root, "provider-keys.json").readBytes()
        store = store()
        assertEquals(listOf(backupId), store.roles.value.mainFallbacks)
        assertFalse(config.readText().contains("\"backup\""))
        assertArrayEquals(credentials, File(tmp.root, "provider-keys.json").readBytes())
        store.modelFor(ModelRole.Main)!!.complete("", emptyList(), emptyList())
        assertEquals(listOf("synthetic-bad-one", "synthetic-good"), calls)
        store = store()
        assertEquals(listOf(backupId), store.roles.value.mainFallbacks)
        assertEquals(KeyFailure.invalid, store.keySummaries(primary.id).first().status)
    }

    @Test fun ownBackupWorksThroughNormalTaskAssembly() = runTest {
        val store = store()
        val primary = connect(store, "primary", "synthetic-bad-one")
        val backup = connect(store, "backup", "synthetic-good")
        store.use(ModelRole.Main, primary.id, "synthetic-model", caps)
        store.useBackup(backup.id, "backup-model", caps)
        val models = com.wangzan101.mika.task.TaskModels.fromFrozen(store.freezeModels(ModelSession.fresh()), { it })!!
        models.main.complete("", emptyList(), emptyList())
        models.light!!.complete("", emptyList(), emptyList())
        assertEquals(listOf("synthetic-bad-one", "synthetic-good", "synthetic-good"), calls)
    }

    @Test fun openRouterAuthorizedApiKeySupportsBackups() = runTest {
        val store = store()
        val provider = store.connect("OpenRouter", ProviderConfig.Kind.OpenAiCompat, "https://openrouter.ai/api/v1",
            "synthetic-bad-authorized", listOf(ListedModel("synthetic-model")), signIn = ProviderStore.OPENROUTER_SIGN_IN)
        store.addProviderKey(provider.id, "synthetic-good")
        store.use(ModelRole.Main, provider.id, "synthetic-model", caps)
        store.modelFor(ModelRole.Main)!!.complete("", emptyList(), emptyList())
        assertEquals(listOf("synthetic-bad-authorized", "synthetic-good"), calls)
    }

    @Test fun allowanceAndTranscriptionCallsRotateAndInvalidatePreviousAllowanceResults() = runTest {
        val store = store()
        val provider = connect(store, "platform", "synthetic-bad-one")
        store.addProviderKey(provider.id, "synthetic-good")
        val revision = store.providerKeyRevisions.value.getValue(provider.id)
        val seen = mutableListOf<String?>()
        val value = store.withProviderKey(provider.id) { key ->
            seen += key
            if (key == "synthetic-bad-one") throw ModelError.Auth()
            "synthetic allowance"
        }
        assertEquals("synthetic allowance", value)
        assertEquals(listOf("synthetic-bad-one", "synthetic-good"), seen)
        assertFalse(store.matchesAllowanceConnection(provider, revision))
        assertNull(store.allowanceKey(provider, revision))
        assertEquals("synthetic-good", store.allowanceKey(provider, store.providerKeyRevisions.value.getValue(provider.id)))
    }

    @Test fun loopReceivesRawFrozenModelsAndTheFallbackChainSeparately() = runTest {
        val store = store()
        val primary = connect(store, "loop-primary", "synthetic-bad-one")
        val backup = connect(store, "loop-backup", "synthetic-good")
        store.use(ModelRole.Main, primary.id, "synthetic-model", caps)
        store.useBackup(backup.id, "backup-model", caps)
        val frozen = store.freezeModels(ModelSession.fresh(), includeFallbacks = false)
        try {
            frozen.main!!.complete("", emptyList(), emptyList())
            fail("The raw primary must leave model failover to ModelCaller")
        } catch (_: ModelError.Auth) { }
        assertEquals(listOf("synthetic-bad-one"), calls)
        frozen.fallbacks.getValue(ModelRole.Main).single().complete("", emptyList(), emptyList())
        assertEquals(listOf("synthetic-bad-one", "synthetic-good"), calls)
        assertEquals(listOf(primary.id, backup.id), frozen.mainChainProviderIds)
    }

    @Test fun communityUsesTheRunScopedTransportAndRetainsTheOwnFallback() = runTest {
        val store = store()
        val provider = store.connect("community", ProviderConfig.Kind.Community, "https://community.invalid", null, emptyList())
        store.use(ModelRole.Main, provider.id, "compute", caps)
        val backup = connect(store, "community-backup", "synthetic-good")
        store.useBackup(backup.id, "backup-model", caps)
        val transport = object : ChatModel {
            override val capabilities = caps
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>) =
                ModelReply(listOf(Part.Text("community")), StopReason.EndTurn, null)
        }
        val frozen = store.freezeModels(ModelSession.fresh(), includeFallbacks = false, communityModel = transport)
        assertSame(transport, frozen.main)
        frozen.fallbacks.getValue(ModelRole.Main).single().complete("", emptyList(), emptyList())
        assertEquals(listOf("synthetic-good"), calls)
    }
}
