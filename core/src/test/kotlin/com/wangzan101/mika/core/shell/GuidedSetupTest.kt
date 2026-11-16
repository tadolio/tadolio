// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.shell

import com.wangzan101.mika.core.shell.GuidedSetup.Action
import com.wangzan101.mika.core.shell.GuidedSetup.Node
import com.wangzan101.mika.core.shell.GuidedSetup.Route
import com.wangzan101.mika.core.shell.GuidedSetup.State
import com.wangzan101.mika.core.shell.GuidedSetup.Step
import org.junit.Assert.*
import org.junit.Test

class GuidedSetupTest {
    @Test fun allInstallsTryAccessibilityFirst() {
        assertEquals(Route.AccessibilityFirst, GuidedSetup.route(false, 36))
        assertEquals(Route.AccessibilityFirst, GuidedSetup.route(true, 36))
        assertEquals(Route.AccessibilityFirst, GuidedSetup.route(false, 29))
    }

    @Test fun accessibilityFirstAdvancesOnlyFromObservedState() {
        val r = Route.AccessibilityFirst
        var s = State()
        assertEquals(Step.Accessibility, GuidedSetup.step(s, r))
        s = s.copy(accessibility = true)
        assertEquals(Step.Developer, GuidedSetup.step(s, r))
        s = s.copy(developer = true)
        assertEquals(Step.Wireless, GuidedSetup.step(s, r))
        s = s.copy(wireless = true)
        assertEquals(Step.Pair, GuidedSetup.step(s, r))
        s = s.copy(helper = true)
        assertEquals(Step.Notifications, GuidedSetup.step(s, r))
        s = s.copy(notifications = true)
        assertEquals(Step.Done, GuidedSetup.step(s, r))
        assertEquals(Step.Done, GuidedSetup.step(s.copy(battery = true), r))
    }

    @Test fun pairingFirstTeachesCodeBeforeOpeningSettingsAndRequiresNotifications() {
        val r = GuidedSetup.route(false, 36, pairingSelected = true)
        assertEquals(Route.PairingFirst, r)
        var s = State(restricted = true)
        assertEquals(Step.CodeIntro, GuidedSetup.step(s, r))
        s = s.copy(codeIntroDone = true)
        assertEquals(Step.Notifications, GuidedSetup.step(s, r))
        s = s.copy(notifications = true)
        assertEquals(Step.Developer, GuidedSetup.step(s, r))
        s = s.copy(developer = true)
        assertEquals(Step.Wireless, GuidedSetup.step(s, r))
        s = s.copy(wireless = true)
        assertEquals(Step.Pair, GuidedSetup.step(s, r))
        s = s.copy(helper = true)
        assertEquals(Step.Helper, GuidedSetup.step(s, r))
        assertEquals(Step.Accessibility, GuidedSetup.step(s.copy(helperWaitExpired = true), r))
        assertEquals(Step.Done, GuidedSetup.step(s.copy(accessibility = true), r))
    }

    @Test fun blockedAccessibilityOffersUnblockingBeforePairing() {
        assertEquals(Step.Unrestrict, GuidedSetup.step(State(restricted = true), Route.AccessibilityFirst))
        assertEquals(Step.Accessibility, GuidedSetup.step(State(), Route.AccessibilityFirst))
        assertEquals(Step.Developer, GuidedSetup.step(State(accessibility = true, restricted = true), Route.AccessibilityFirst))
    }

    @Test fun ordinaryPathMayFinishAfterNotificationDenialButPairingStillNeedsIt() {
        val s = State(helper = true, accessibility = true, notificationsSkipped = true)
        assertEquals(Step.Done, GuidedSetup.step(s, Route.AccessibilityFirst))
        assertEquals(Step.Notifications, GuidedSetup.step(State(codeIntroDone = true, notificationsSkipped = true), Route.PairingFirst))
    }

    @Test fun runningHelperBypassesDeveloperAndWirelessButNotConsentChecks() {
        val s = State(helper = true, accessibility = true, notifications = true, battery = true)
        assertEquals(Step.Done, GuidedSetup.step(s, Route.PairingFirst))
        assertEquals(Step.Done, GuidedSetup.step(s.copy(battery = false), Route.AccessibilityFirst))
        assertEquals(Step.Accessibility, GuidedSetup.step(State(developer = true), Route.AccessibilityFirst))
    }

    @Test fun samsungAndAospLabelTreesSelectOnlyKnownSettingsRows() {
        val samsung = listOf(Node("软件信息", true), Node("重置", true))
        assertEquals(Action.Click(0), GuidedSetup.action(Step.Developer, samsung, true))
        val build = listOf(Node("Build number", true), Node("Android version", true))
        assertEquals(Action.Click(0), GuidedSetup.action(Step.Developer, build, true))
        val wireless = listOf(Node("Wireless debugging", true, false))
        assertEquals(Action.Click(0), GuidedSetup.action(Step.Wireless, wireless, true))
        val pair = listOf(Node("使用配对码配对设备", true))
        assertEquals(Action.Click(0), GuidedSetup.action(Step.Pair, pair, true))
    }

    @Test fun passwordAlwaysStopsBeforeAnyClick() {
        for (step in Step.entries) {
            assertEquals(Action.Password, GuidedSetup.action(step,
                listOf(Node("Build number", true), Node("", password = true)), true))
            assertEquals(Action.Password, GuidedSetup.action(step,
                listOf(Node("确认您的 PIN 码"), Node("Wireless debugging", true)), true))
        }
    }

    @Test fun unknownAmbiguousAndForeignWindowsRequireManualGuidance() {
        val known = Node("Wireless debugging", true)
        assertEquals(Action.Manual, GuidedSetup.action(Step.Wireless, listOf(known), false))
        assertEquals(Action.Manual, GuidedSetup.action(Step.Wireless, listOf(known, known), true))
        assertEquals(Action.Manual, GuidedSetup.action(Step.Pair, listOf(Node("Allow", true)), true))
        assertEquals(Action.Manual, GuidedSetup.action(Step.Wireless, listOf(Node("Wireless debugging", true, true)), true))
        for (step in listOf(Step.Accessibility, Step.Notifications, Step.Battery))
            assertEquals(Action.Manual, GuidedSetup.action(step, listOf(Node("Allow", true)), true))
    }

    @Test fun helpShareContainsOnlyFixedProgress() {
        val share = GuidedSetup.share(Step.Pair, setOf(Step.Developer, Step.Wireless))
        assertTrue(share.contains("2 Developer ✓"))
        assertTrue(share.contains("4 Pair ✗"))
        assertEquals(8, share.lines().size)
        assertFalse(share.contains("Done"))
    }

    @Test fun boundedScrollAndConsentSafety() {
        val nodes = listOf(Node("Other", scrollable = true))
        for (n in 0..5) assertEquals(Action.Scroll(0), GuidedSetup.action(Step.Developer, nodes, true, n))
        assertEquals(Action.Manual, GuidedSetup.action(Step.Developer, nodes, true, 6))
        assertEquals(Action.Manual, GuidedSetup.action(Step.Developer, nodes, false))
        assertEquals(Action.Manual, GuidedSetup.action(Step.Accessibility, nodes, true))
        assertEquals(Action.Manual, GuidedSetup.action(Step.Developer, nodes + nodes, true))
        assertEquals(Action.Password, GuidedSetup.action(Step.Developer, nodes + Node("", password = true), true))
    }
    @Test fun oppoVersionNavigationIsManufacturerScoped() {
        val nodes = listOf(Node("\u7248\u672c", true))
        assertEquals(Action.Click(0), GuidedSetup.action(Step.Developer, nodes, true, manufacturer = "OPPO"))
        assertEquals(Action.Manual, GuidedSetup.action(Step.Developer, nodes, true))
        assertEquals(Action.Manual, GuidedSetup.action(Step.Developer, nodes + nodes, true, manufacturer = "OPPO"))
    }
}
