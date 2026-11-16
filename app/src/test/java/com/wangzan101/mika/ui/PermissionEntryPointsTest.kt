// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Android launchers need device coverage. These checks protect their on-demand wiring on the JVM. */
class PermissionEntryPointsTest {
    private fun source(path: String) = File("src/main/java/com/wangzan101/mika/$path").readText()

    @Test fun newRuntimePermissionsAreAbsentFromStartupRequests() {
        for (path in listOf("MikaApp.kt", "ui/MainActivity.kt")) {
            val startup = source(path)
            for (permission in listOf("CAMERA", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "CALL_PHONE", "READ_PHONE_STATE")) {
                assertFalse("$path $permission", startup.contains("Manifest.permission.$permission"))
            }
        }
    }

    @Test fun cameraAndWebPermissionsLaunchFromExplicitActions() {
        val attachments = source("ui/ChatAttachments.kt")
        assertTrue(attachments.contains("AttachCopy.CAMERA, onCamera"))
        assertFalse(attachments.contains("RequestPermission"))
        val chat = source("ui/ChatScreen.kt")
        assertTrue(chat.contains("onCamera = { cameraOpen = true }"))
        val camera = source("ui/TadolioCamera.kt")
        assertTrue(camera.contains("internal fun TadolioCamera("))
        assertTrue(camera.contains("LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }"))
        assertTrue(camera.contains("onPauseOrDispose { active = false }"))
        val browser = source("ui/BrowserScreen.kt")
        for (launcher in listOf("askMediaPermissions.launch", "askLocationPermission.launch")) {
            val at = browser.indexOf(launcher)
            assertTrue(launcher, at > 0)
            val before = browser.substring(0, at)
            assertTrue(launcher, before.lastIndexOf("onAllow = allow@{") > before.lastIndexOf("WebPermissionDialog("))
        }
    }

    @Test fun phonePermissionsLaunchFromCallSettingsAction() {
        val source = source("ui/CallDeviceReadiness.kt")
        assertTrue(source.contains("listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE)"))
        val action = source.indexOf("phoneAction = {")
        val launch = source.indexOf("requestPhone.launch(missing.toTypedArray())")
        assertTrue(action > 0 && launch > action)
    }
}
