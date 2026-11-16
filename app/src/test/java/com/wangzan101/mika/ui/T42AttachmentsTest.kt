// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.ui

import com.wangzan101.mika.cap.sandbox.ImportResult
import com.wangzan101.mika.chat.ConversationStore
import com.wangzan101.mika.core.agent.TaskState
import com.wangzan101.mika.settings.Prerequisites
import com.wangzan101.mika.takeover.Takeover
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class T42AttachmentsTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val texts = com.wangzan101.mika.i18n.ChineseTexts()
    private val started = mutableListOf<String>()
    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { started += goal }
        override fun cancel() {}
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) {}
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
    }
    @Test fun noVisionPreservesDraftAndShowsClearMessage() = runTest {
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions,
            ConversationStore(tmp.newFolder()), sandboxReady = MutableStateFlow(true), imageSupported = { false })
        runCurrent()
        vm.attach(listOf("content://test/photo.jpg"))
        runCurrent()
        assertFalse(vm.send("read", Prerequisites.Check(emptyList())))
        assertTrue(vm.ui.value.imageUnsupported)
        assertEquals(1, vm.ui.value.attachments.size)
        assertTrue(started.isEmpty())
        assertEquals("当前模型看不了图片", com.wangzan101.mika.i18n.tr(com.wangzan101.mika.R.string.t42_no_vision))
    }
    @Test fun communityNoticeIsConsumedOnlyAfterAcceptedImageAndPersistsAcrossViewModels() = runTest {
        var seen = false
        var community = true
        var reject = true
        fun make() = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions,
            ConversationStore(tmp.newFolder()), sandboxReady = MutableStateFlow(true),
            communityImages = { community }, imageNoticeSeen = { seen }, markImageNoticeSeen = { seen = true },
            importAttachments = { _, _ -> if (reject) ImportResult(emptyList(), listOf("photo.jpg")) else ImportResult(listOf("/workspace/inbox/photo.jpg" to 3L), emptyList()) })
        val vm = make()
        runCurrent()
        vm.attach(listOf("content://test/photo.jpg"))
        runCurrent()
        assertTrue(vm.showCommunityImageNotice())
        community = false
        assertFalse(vm.showCommunityImageNotice())
        community = true
        vm.send("", Prerequisites.Check(emptyList()))
        runCurrent()
        assertFalse(seen)
        reject = false
        vm.send("", Prerequisites.Check(emptyList()))
        runCurrent()
        assertTrue(seen)
        assertTrue(started.single().contains("/workspace/inbox/photo.jpg"))
        val second = make()
        runCurrent()
        second.attach(listOf("content://test/photo.jpg"))
        runCurrent()
        assertFalse(second.showCommunityImageNotice())
    }
    @Test fun qrBannerSendsOnlyQrTextAndPreservesAnExistingDraft() = runTest {
        val vm = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), MutableStateFlow(Takeover.Stage.None), actions,
            ConversationStore(tmp.newFolder()), sandboxReady = MutableStateFlow(true))
        runCurrent()
        vm.input("my draft")
        vm.attach(listOf("content://test/photo.jpg"))
        runCurrent()
        assertTrue(vm.sendCameraCode("https://example.org", Prerequisites.Check(emptyList())))
        assertEquals(listOf("https://example.org"), started)
        assertEquals("my draft", vm.ui.value.input)
        assertEquals(1, vm.ui.value.attachments.size)
    }
    @Test fun cameraSeparatesTransferCodesAndOtherContent() {
        assertEquals(CameraCode.Transfer("12345678"), CameraCode.parse("tadolio-transfer:12345678"))
        assertEquals(CameraCode.Other("https://example.org"), CameraCode.parse("https://example.org"))
        assertEquals(CameraCode.Other("hello"), CameraCode.parse("hello"))
    }
    @Test fun numberPagesHaveSeparateInputsAndExactlyThreeNormalEntrances() {
        val root = java.io.File("src/main/java/com/wangzan101/mika/ui")
        val screen = java.io.File(root, "NumberMoveScreen.kt").readText()
        val receive = screen.substringAfter("\"receive\" -> {").substringBefore("\"restore\" -> {")
        assertEquals(1, Regex("OutlinedTextField").findAll(receive).count())
        assertTrue(receive.contains("t42_replace_number"))
        assertFalse(receive.contains("recoveryValid"))
        val restore = screen.substringAfter("\"restore\" -> {").substringBefore("\"transfer\" -> {")
        assertEquals(2, Regex("OutlinedTextField").findAll(restore).count())
        assertTrue(restore.contains("recoveryInput"))
        val entry = screen.substringAfter("internal fun NumberMoveEntry")
        for (route in listOf("scan", "transfer", "recovery")) assertTrue(entry.contains("open(\"$route\")"))
        assertFalse(screen.contains("TakePicturePreview"))
    }
}
