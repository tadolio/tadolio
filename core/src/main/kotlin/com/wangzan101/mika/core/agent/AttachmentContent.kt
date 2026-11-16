// Modified by Tadolio contributors (2026)
package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.vision.ModelImage
import com.wangzan101.mika.core.vision.ImageOps

/** Resolve only the inbox paths explicitly present in this message. */
object AttachmentContent {
    fun isImage(path: String) = path.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "bmp")
    fun hasImages(text: String) = AttachmentLines.parse(text).files.any { isImage(it.first) }

    suspend fun parts(text: String, vision: Boolean, read: suspend (String) -> ByteArray, ops: ImageOps? = null): List<Part> =
        AttachmentLines.parse(text).files.map { (path, _) ->
            require(path.startsWith("/workspace/inbox/"))
            if (isImage(path)) {
                check(vision) { "attachment_model_cannot_see" }
                ModelImage.part(read(path), ops).copy(userAttachment = true)
            } else {
                val plain = path.substringAfterLast('.').lowercase() in setOf("txt", "md", "csv", "tsv", "json", "xml", "html", "log", "kt", "py", "js")
                Part.Text(if (plain) "Attached file $path (up to 65536 characters)\n" + read(path).toString(Charsets.UTF_8).take(65536)
                    else "Attached file $path is stored on the phone. Its contents have not been read. Explain when this file type cannot be read with the available tools.")
            }
        }
}
