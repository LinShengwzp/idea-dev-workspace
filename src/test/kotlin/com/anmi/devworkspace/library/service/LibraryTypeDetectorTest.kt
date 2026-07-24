package com.anmi.devworkspace.library.service

import com.anmi.devworkspace.library.domain.LibraryItemType
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryTypeDetectorTest {
    private val detector = LibraryTypeDetector()

    @Test
    fun `detects links images media files and Markdown text`() {
        assertEquals(LibraryItemType.LINK, detector.detect("https://example.com/docs"))
        assertEquals(LibraryItemType.LINK, detector.detect("HTTP://example.com"))
        assertEquals(LibraryItemType.IMAGE, detector.detect("D:/images/cover.PNG"))
        assertEquals(LibraryItemType.MEDIA, detector.detect("${'$'}{PROJECT_DIR}/media/demo.mp4"))
        assertEquals(LibraryItemType.FILE, detector.detect("../docs/reference.pdf"))
        assertEquals(LibraryItemType.MARKDOWN, detector.detect("A paragraph of useful notes"))
    }

    @Test
    fun `manual override wins over automatic detection`() {
        assertEquals(
            LibraryItemType.FILE,
            detector.detect("https://example.com/image.png", LibraryItemType.FILE),
        )
    }
}
