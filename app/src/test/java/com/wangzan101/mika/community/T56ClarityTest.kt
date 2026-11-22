// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.community

import com.wangzan101.mika.R
import com.wangzan101.mika.ui.elderModeNeedsConfirmation
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.w3c.dom.Element

class T56ClarityTest {
    private fun resources(folder: String): Map<String, String> = buildMap {
        File("src/main/res/$folder").listFiles { f -> f.extension == "xml" }!!.forEach { file ->
            val elements = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                .getElementsByTagName("string")
            for (i in 0 until elements.length) {
                val element = elements.item(i) as Element
                put(element.getAttribute("name"), element.textContent)
            }
        }
    }

    private fun source(file: String) = File("src/main/java/com/wangzan101/mika/$file").readText()

    @Test fun reviewedResourcesExistInBothLanguages() {
        val names = listOf(
            "miner_what_title", "miner_intro", "phone_miner_role", "phone_operator_role",
            "phone_miner_role_sub", "phone_operator_role_sub", "miner_exam",
            "miner_state_waiting_exam", "miner_state_exam", "miner_state_failed_exam",
            "miner_number", "miner_number_missing", "miner_cloud", "miner_details", "miner_node",
            "phone_operator_switch", "phone_operator_invite_label", "miner_drawer_running",
            "miner_drawer_paused", "miner_drawer_preparing", "miner_drawer_attention",
            "community_miner_link", "operator_empty", "operator_manage", "community_privacy",
            "community_local_source", "elder_home_title", "elder_home_note", "elder_enable_title",
            "elder_enable_body", "elder_enable_confirm", "elder_mode_sub", "settings_model_sub",
        )
        for (folder in listOf("values", "values-zh")) {
            val strings = resources(folder)
            names.forEach { assertFalse("$folder/$it", strings[it].isNullOrBlank()) }
        }
        val zh = resources("values-zh")
        val en = resources("values")
        assertEquals("用你的 Key，帮别人的 Tadolio 办事", zh["miner_what_title"])
        assertEquals("Use your key to run other people’s Tadolio", en["miner_what_title"])
        assertEquals("想办什么事？", zh["elder_home_title"])
        assertEquals("What do you need?", en["elder_home_title"])
        assertTrue(zh.getValue("operator_manage").contains("侧栏“当矿工”"))
        assertTrue(en.getValue("operator_manage").contains("side menu"))
    }

    @Test fun privacyHasNoHubOrNodeJargonInAnyLocale() {
        val english = resources("values").getValue("community_privacy")
        File("src/main/res").listFiles { f -> f.isDirectory && f.name.startsWith("values") }!!.forEach { folder ->
            val privacy = resources(folder.name)["community_privacy"] ?: english
            assertFalse(folder.name, privacy.contains("hub", ignoreCase = true))
            assertFalse(folder.name, privacy.contains("node", ignoreCase = true))
            assertFalse(folder.name, privacy.contains("节点") || privacy.contains("節點"))
        }
        assertTrue(english.contains("swap the encryption keys"))
        assertTrue(resources("values-zh").getValue("community_privacy").contains("换掉加密钥匙"))
        assertTrue(resources("values-b+zh+Hant").getValue("community_privacy").contains("換掉加密鑰匙"))
    }

    @Test fun numberCopyHandlesPresentMissingAndWhitespaceNumbers() {
        assertEquals(R.string.miner_number, minerNumberMessage("12345678"))
        for (number in listOf("", " ", "\n")) {
            assertEquals(R.string.miner_number_missing, minerNumberMessage(number))
        }
        for (folder in listOf("values", "values-zh")) {
            val strings = resources(folder)
            assertTrue(strings.getValue("miner_number").contains("%1\$s"))
            assertFalse(strings.getValue("miner_number_missing").contains("%1\$s"))
        }
        val activity = source("community/MinerActivity.kt")
        assertFalse(activity.contains("stringResource(R.string.miner_number,"))
        assertTrue(activity.contains("stringResource(minerNumberMessage(number), number)"))
    }

    @Test fun drawerMapsEveryStateAndHidesPeopleWhoHaveNotStarted() {
        for (state in MinerState.entries) {
            val expected = when (state) {
                MinerState.ONLINE, MinerState.WORKING -> R.string.miner_drawer_running
                MinerState.CONNECTING, MinerState.WAITING_EXAM, MinerState.EXAM -> R.string.miner_drawer_preparing
                MinerState.KEY_BAD, MinerState.FAILED_EXAM -> R.string.miner_drawer_attention
                else -> R.string.miner_drawer_paused
            }
            assertEquals(state.name, expected, minerDrawerMessage(true, state))
            assertEquals(state.name, if (state == MinerState.KEY_BAD) R.string.miner_drawer_attention else null,
                minerDrawerMessage(false, state))
        }
        val drawer = source("ui/ChatDrawer.kt")
        val refresh = drawer.substringAfter("LaunchedEffect(open) {").substringBefore("    BackHandler")
        assertTrue(refresh.contains("if (open &&"))
        assertTrue(refresh.contains("store.enabled, store.state()"))
        assertFalse(refresh.contains("delay("))
        assertFalse(refresh.contains("while"))
        val entries = drawer.substringAfter("NavEntry(MikaIcons.Heart, stringResource(R.string.number_title)")
        assertTrue(entries.contains("R.string.miner_title"))
        assertTrue(drawer.substringAfter("&& permanent)").substringBefore("// 搜索框").contains("R.string.drawer_plans"))
        assertTrue(drawer.contains("rightText: String? = null"))
        assertTrue(source("ui/MainActivity.kt").contains("onMiner = { context.startActivity"))
    }

    @Test fun elderEnableRequiresConfirmationAndDisableIsImmediate() {
        assertTrue(elderModeNeedsConfirmation(false))
        assertFalse(elderModeNeedsConfirmation(true))
        val controls = source("ui/ElderControls.kt")
        assertTrue(controls.contains("if (elderModeNeedsConfirmation(enabled)) confirmEnable = true else setEnabled(false)"))
        assertTrue(controls.contains("onDismissRequest = { confirmEnable = false }"))
        assertTrue(controls.contains("onClick = { confirmEnable = false; setEnabled(true) }"))
        assertTrue(controls.contains("android.R.string.cancel"))
        val home = source("ui/ElderHome.kt")
        assertTrue(home.contains("OutlinedButton(settings)"))
        assertTrue(home.contains("R.string.elder_home_title"))
        assertTrue(home.contains("Modifier.fillMaxWidth().clickable(onClick = settings)"))
        assertFalse(home.contains("R.string.elder_mode)"))
    }

    @Test fun settingsEntryRemovedAndModelRemainsFirst() {
        val settings = source("ui/SettingsScreen.kt")
        assertFalse(settings.contains("R.string.miner_title"))
        assertFalse(settings.contains("MinerActivity::class.java"))
        assertTrue(settings.contains("R.string.settings_model_sub), first = true"))
        assertTrue(source("ui/CommunityControls.kt").contains("R.string.community_miner_link"))
    }

    @Test fun modelPlatformsAndTadolioProvidersKeepTheirRespectiveMeaning() {
        for (folder in listOf("values", "values-zh", "values-b+zh+Hant")) {
            for (file in listOf("strings_models.xml", "strings_setup.xml", "strings_usage.xml", "strings_voice.xml", "strings_hivo_t09.xml")) {
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/res/$folder/$file"))
                val body = doc.documentElement.textContent
                assertFalse("$folder/$file", body.contains("服务商") || body.contains("服務商"))
                assertFalse("$folder/$file", Regex("(?i)\\bprovider(s)?\\b").containsMatchIn(body))
            }
            val strings = resources(folder)
            assertTrue(strings.getValue("settings_model_sub").contains(if (folder == "values") "model platforms" else "模型平台"))
            assertTrue(strings.getValue("community_provider").contains(if (folder == "values") "provider" else if (folder == "values-zh") "服务商" else "服務商"))
        }
        assertEquals("Models, model platforms & token usage", resources("values")["settings_model_sub"])
        assertEquals("模型、模型平台与 Token 用量", resources("values-zh")["settings_model_sub"])
        assertTrue(File("src/main/res/values/community.xml").readText().contains("Your provider"))
        resources("values").values.forEach { assertFalse(it, Regex("(?i)\\boperator\\b").containsMatchIn(it)) }
        assertTrue(source("task/TaskRun.kt").contains("_communityPlatform.value = platform"))
        assertTrue(source("task/TaskRunState.kt").contains("run?.takeIf { it.community }?.communityPlatform"))
        assertTrue(source("ui/ChatScreen.kt").contains("communityTask?.communityPlatform?.collectAsState()"))
    }

    @Test fun setupAndRunningSectionsFollowTheReviewedOrder() {
        val activity = source("community/MinerActivity.kt")
        val body = activity.substringAfter(") { padding ->")
        assertTrue(body.indexOf("R.string.miner_what_title") < body.indexOf("RoleChoice()"))
        assertTrue(body.indexOf("RoleChoice()") < body.indexOf("MinerStep(1,"))
        assertTrue(body.indexOf("MinerStep(1,") < body.indexOf("MinerStep(2,"))
        assertTrue(body.indexOf("MinerStep(2,") < body.indexOf("MinerStep(3,"))
        assertTrue(body.indexOf("MinerStep(3,") < body.indexOf("R.string.miner_payees_hint"))
        assertTrue(body.indexOf("R.string.miner_today_usage") < body.indexOf("R.string.phone_operator_invite_label"))
        assertTrue(body.indexOf("R.string.phone_operator_invite_label") < body.indexOf("Text(invite,"))
        assertTrue(body.indexOf("Text(invite,") < body.indexOf("R.string.phone_operator_edit"))
        assertTrue(activity.contains("var showCost by remember { mutableStateOf(true) }"))
        assertTrue(activity.contains("FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp))"))
        assertEquals(2, Regex("ProviderCardFields\\(\\)").findAll(body).count())
    }
}
