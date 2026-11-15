// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.cap.sandbox

import com.wangzan101.mika.core.workspace.SandboxTree
import com.wangzan101.mika.core.agent.AttachmentContent
import com.wangzan101.mika.core.agent.AttachmentLines
import com.wangzan101.mika.core.model.Part
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class T42PrivateAttachmentsTest {
    @get:Rule val tmp = TemporaryFolder()
    @Test fun standaloneStorageIsConversationScopedAndFeedsModelWithoutInstallingSandbox() = runBlocking {
        val root = tmp.newFolder()
        val dirs = SandboxDirs(File(root, "sandbox"), File(root, "tmp"), File(root, "lib"), JVM_GATE, SandboxTree(), File(root, "attachments"))
        assertTrue(dirs.workspace("one").path.contains("attachments"))
        assertNotEquals(dirs.workspace("one"), dirs.workspace("two"))
        assertTrue(dirs.workspace("../escape").canonicalPath.startsWith(dirs.work.canonicalPath + File.separator))
        val source = tmp.newFile().apply { writeText("hello") }
        val files = Attachments.withTaskFiles(listOf("note.txt" to source), dirs.workspace("one"), dirs.gate, dirs.tree, { true }) { it }
        val message = AttachmentLines.composeBytes("read", files)
            val parts = AttachmentContent.parts(message, false, { path ->
                dirs.gate.openRead(dirs.workspace("one"), path.removePrefix("/workspace/")).use { it.read(0, it.size.toInt()) }
            })
        assertTrue((parts.single() as Part.Text).text.contains("hello"))
        assertFalse(dirs.rootfs.exists())
        dirs.deleteWorkspace("one")
        assertFalse(dirs.workspace("one").exists())
    }
}
