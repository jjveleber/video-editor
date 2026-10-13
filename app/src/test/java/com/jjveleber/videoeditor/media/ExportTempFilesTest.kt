package com.jjveleber.videoeditor.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExportTempFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun tempFileLivesInDirWithExportPrefix() {
        val file = exportTempFile(tmp.root)
        assertEquals(tmp.root, file.parentFile)
        assertTrue(file.name.startsWith("export-") && file.name.endsWith(".mp4"))
    }

    @Test
    fun deleteLeftoverExportsRemovesOnlyExportTempFiles() {
        exportTempFile(tmp.root).writeText("partial")
        tmp.newFile("export-123.mp4").writeText("partial")
        tmp.newFile("thumbnail.png")
        tmp.newFile("export-notes.txt")

        deleteLeftoverExports(tmp.root)

        assertEquals(setOf("thumbnail.png", "export-notes.txt"), tmp.root.list()!!.toSet())
    }

    @Test
    fun deleteLeftoverExportsIgnoresMissingDir() {
        deleteLeftoverExports(tmp.root.resolve("missing"))
    }
}
