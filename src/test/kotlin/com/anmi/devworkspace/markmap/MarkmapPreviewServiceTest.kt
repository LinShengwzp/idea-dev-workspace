package com.anmi.devworkspace.markmap

import com.intellij.openapi.Disposable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkmapPreviewServiceTest {
    @Test
    fun `panel attached after document receives current contents`() {
        val scheduler = ControlledScheduler()
        val service = MarkmapPreviewService(scheduler)
        val document = FakeDocument("# current")
        val target = RecordingTarget()

        service.preview(document)
        service.setTarget(target)

        assertEquals(listOf("# current"), target.markdown)
    }

    @Test
    fun `switching documents detaches old listener`() {
        val scheduler = ControlledScheduler()
        val service = MarkmapPreviewService(scheduler)
        val first = FakeDocument("# first")
        val second = FakeDocument("# second")
        val target = RecordingTarget()
        service.setTarget(target)

        service.preview(first)
        service.preview(second)
        first.changeTo("# stale")

        assertFalse(scheduler.hasPending)
        assertEquals(0, first.listenerCount)
        assertEquals(1, second.listenerCount)
    }

    @Test
    fun `document changes debounce to latest contents`() {
        val scheduler = ControlledScheduler()
        val service = MarkmapPreviewService(scheduler)
        val document = FakeDocument("# initial")
        val target = RecordingTarget()
        service.setTarget(target)
        service.preview(document)

        document.changeTo("# intermediate")
        document.changeTo("# final")
        scheduler.runPending()

        assertEquals(listOf("# initial", "# final"), target.markdown)
        assertEquals(listOf(300), scheduler.delays)
    }

    @Test
    fun `disposing service cancels refresh and listener`() {
        val scheduler = ControlledScheduler()
        val service = MarkmapPreviewService(scheduler)
        val document = FakeDocument("# initial")
        val target = RecordingTarget()
        service.setTarget(target)
        service.preview(document)
        document.changeTo("# pending")

        service.dispose()
        scheduler.runPending()
        document.changeTo("# ignored")

        assertEquals(listOf("# initial"), target.markdown)
        assertEquals(0, document.listenerCount)
        assertTrue(scheduler.disposed)
    }

    @Test
    fun `clearing stale panel keeps newer panel attached`() {
        val scheduler = ControlledScheduler()
        val service = MarkmapPreviewService(scheduler)
        val oldTarget = RecordingTarget()
        val currentTarget = RecordingTarget()
        val document = FakeDocument("# current")

        service.setTarget(oldTarget)
        service.setTarget(currentTarget)
        service.clearTarget(oldTarget)
        service.preview(document)

        assertEquals(emptyList(), oldTarget.markdown)
        assertEquals(listOf("# current"), currentTarget.markdown)
    }

    private class FakeDocument(initialText: String) : MarkmapDocument {
        private val listeners = linkedSetOf<() -> Unit>()

        override var text: String = initialText
            private set

        val listenerCount: Int
            get() = listeners.size

        override fun addChangeListener(listener: () -> Unit): Disposable {
            listeners += listener
            return Disposable { listeners -= listener }
        }

        fun changeTo(newText: String) {
            text = newText
            listeners.toList().forEach { it() }
        }
    }

    private class RecordingTarget : MarkmapRenderTarget {
        val markdown = mutableListOf<String>()

        override fun render(markdown: String, optionsJson: String) {
            this.markdown += markdown
        }
    }

    private class ControlledScheduler : MarkmapRenderScheduler {
        private var pending: (() -> Unit)? = null
        val delays = mutableListOf<Int>()
        var disposed: Boolean = false
            private set

        val hasPending: Boolean
            get() = pending != null

        override fun schedule(delayMillis: Int, action: () -> Unit) {
            pending = action
            delays.clear()
            delays += delayMillis
        }

        override fun cancelPending() {
            pending = null
        }

        override fun dispose() {
            disposed = true
            pending = null
        }

        fun runPending() {
            pending?.also { pending = null }?.invoke()
        }
    }
}
