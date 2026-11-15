package com.mcmanager.feature.files

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

const val MAX_EDIT_BYTES = 2L * 1024 * 1024
const val SNIFF_BYTES = 512L * 1024

val TEXT_EXTENSIONS = setOf(
  "txt", "properties", "yml", "yaml", "json", "json5", "toml", "cfg", "conf", "log", "md", "sh", "mcmeta",
)

object FileOps {
  /** Resolves [rel] inside [root]; returns null if the canonical result would leave [root]. */
  fun resolveInside(root: File, rel: String): File? {
    val rootC = root.canonicalFile
    val f = File(rootC, rel.trim('/', '\\')).canonicalFile
    return if (f == rootC || f.path.startsWith(rootC.path + File.separator)) f else null
  }

  fun relativePath(root: File, f: File): String =
    if (f.canonicalFile == root.canonicalFile) "" else f.canonicalFile.relativeTo(root.canonicalFile).invariantSeparatorsPath

  /** Writes via `<name>.tmp` then renames over the target. */
  fun atomicWrite(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    try {
      tmp.writeText(text, Charsets.UTF_8)
      if (!tmp.renameTo(target)) {
        target.delete()
        if (!tmp.renameTo(target)) throw IOException("Could not replace ${target.name}")
      }
    } finally {
      if (tmp.exists()) tmp.delete()
    }
  }

  fun isValidName(name: String): Boolean =
    name.isNotBlank() && name != "." && name != ".." && !name.contains('/') && !name.contains('\\') && !name.contains('\u0000')

  fun isTextByExtension(f: File): Boolean = f.extension.lowercase() in TEXT_EXTENSIONS

  /** True when the first bytes of [f] decode as strict UTF-8 without NULs. */
  fun looksLikeUtf8(f: File): Boolean = try {
    val n = minOf(f.length(), SNIFF_BYTES).toInt()
    val buf = ByteArray(n)
    f.inputStream().use { it.read(buf) }
    if (buf.any { it == 0.toByte() }) {
      false
    } else {
      val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      // A cut multi-byte char at the end is fine when the file is longer than the sample.
      var end = n
      if (f.length() > n) while (end > 0 && (buf[end - 1].toInt() and 0xC0) == 0x80) end--
      if (f.length() > n && end > 0 && buf[end - 1].toInt() and 0x80 != 0) end--
      dec.decode(ByteBuffer.wrap(buf, 0, end))
      true
    }
  } catch (e: Exception) {
    false
  }

  fun isEditable(f: File): Boolean = isTextByExtension(f) || (f.length() < SNIFF_BYTES && looksLikeUtf8(f))
}
