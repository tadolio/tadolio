// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.settings

import com.wangzan101.mika.capsule.CapsuleMode
import com.wangzan101.mika.core.cap.phone.OperatingPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** settings_list / settings_set 的白名单:只给能随手改回去的偏好;模型、Key、删数据一律不在其中。 */
class SelfConfigImplTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun offersOnlyPreferencesAvailableToday() {
        val keys = SelfConfigImpl(flags()).options().map { it.key }.toSet()
        assertEquals(setOf("operating_pace", "mute_apps", "appearance", "accent_color", "capsule", "keep_screen_on", "large_text"), keys)
    }

    @Test fun modelsKeysAndAnythingThatDeletesDataAreNotOffered() {
        val keys = SelfConfigImpl(flags()).options().map { it.key }
        assertTrue(keys.none { k -> listOf("model", "key", "delete", "reset", "clear").any { it in k } })
        // 容器的联网与代理会让数据离开手机,也要用户自己在设置里决定
        assertTrue("container_network" !in keys)
    }

    @Test fun changingAPreferenceTakesEffect() {
        val f = flags()
        val s = SelfConfigImpl(f)
        assertNotNull(s.set("standby", "off"))
        assertNull(s.set("large_text", "on"))
        assertTrue(f.largeText())
        assertNull(s.set("operating_pace", "fast"))
        assertEquals(OperatingPace.FAST, f.operatingPace())
        assertNull(s.set("mute_apps", "off"))
        assertEquals(false, f.muteTarget())
        assertNull(s.set("appearance", "dark"))
        assertEquals(ThemeMode.Dark, f.themeMode())
        assertNull(s.set("accent_color", "Purple"))
        assertEquals(ThemeAccent.Purple, f.themeAccent())
        assertNull(s.set("capsule", "off"))
        assertEquals(CapsuleMode.Off, f.capsuleMode())
        assertNull(s.set("keep_screen_on", "off"))
        assertEquals(false, f.keepScreenOn())
    }

    @Test fun badValuesAndUnknownKeysAreReported() {
        val s = SelfConfigImpl(flags())
        assertNotNull(s.set("operating_pace", "turbo"))
        assertNotNull(s.set("appearance", "sepia"))
        assertNotNull(s.set("accent_color", "green"))
        assertNotNull(s.set("mute_apps", "maybe"))
        assertEquals("there is no such setting", s.set("api_key", "sk-123"))
        assertEquals("there is no such setting", s.set("container_network", "on"))
    }

    @Test fun numberPreferencesRoundTripThroughSettingsSet() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val number = com.wangzan101.mika.number.NumberPreferences(prefs)
        val settings = SelfConfigImpl(flags(), numberSettings = number)
        assertEquals("", number.publicCard())
        assertTrue(number.autoReplyEnabled())
        assertNull(settings.set("number_public_card", "  Public CARD\n9-18  "))
        assertEquals("  Public CARD\n9-18  ", number.publicCard())
        assertNull(settings.set("number_auto_reply", "off"))
        val restored = com.wangzan101.mika.number.NumberPreferences(prefs)
        assertEquals(number.publicCard(), restored.publicCard())
        assertEquals(false, restored.autoReplyEnabled())
        assertNotNull(settings.set("number_public_card", "x".repeat(1001)))
        assertNull(settings.set("number_public_card", "😀".repeat(1000)))
        assertEquals("off", settings.options().single { it.key == "number_auto_reply" }.value)
        assertNull(settings.set("number_public_card", ""))
        assertEquals("", restored.publicCard())
    }

    @Test fun juryDefaultsOnAndSettingsSetPersistsTheSwitch() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val number = com.wangzan101.mika.number.NumberPreferences(prefs)
        val settings = SelfConfigImpl(flags(), numberSettings = number)
        assertTrue(number.governanceJuryEnabled())
        assertEquals("on", settings.options().single { it.key == "governance_jury" }.value)
        assertNull(settings.set("governance_jury", "off"))
        assertEquals(false, com.wangzan101.mika.number.NumberPreferences(prefs).governanceJuryEnabled())
        assertNotNull(settings.set("governance_jury", "maybe"))
        assertNull(settings.set("governance_jury", "on"))
        assertTrue(number.governanceJuryEnabled())
    }

    @Test fun postStandbyDefaultsOnAndSettingsSetPersistsTheSwitch() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        val number = com.wangzan101.mika.number.NumberPreferences(prefs)
        val settings = SelfConfigImpl(flags(), numberSettings = number)
        assertTrue(number.postStandbyEnabled())
        assertEquals("on", settings.options().single { it.key == "post_standby" }.value)
        assertNull(settings.set("post_standby", "off"))
        assertEquals(false, com.wangzan101.mika.number.NumberPreferences(prefs).postStandbyEnabled())
        assertNotNull(settings.set("post_standby", "maybe"))
        assertEquals("off", settings.options().single { it.key == "post_standby" }.value)
        assertNull(settings.set("post_standby", "on"))
        assertTrue(number.postStandbyEnabled())
    }
}
