package com.anmi.devworkspace.markmap

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.util.Disposer
import com.intellij.util.Alarm

internal interface MarkmapRenderTarget {
    fun render(markdown: String, optionsJson: String = "{}")
}

internal interface MarkmapDocument {
    val text: String

    fun addChangeListener(listener: () -> Unit): Disposable
}

internal interface MarkmapRenderScheduler : Disposable {
    fun schedule(delayMillis: Int, action: () -> Unit)

    fun cancelPending()
}

/**
 * Owns the active document binding and debounces rendering independently of Tool Window creation.
 */
@Service(Service.Level.PROJECT)
class MarkmapPreviewService internal constructor(
    private val scheduler: MarkmapRenderScheduler = AlarmMarkmapRenderScheduler(),
) : Disposable {
    private var target: MarkmapRenderTarget? = null
    private var currentDocument: MarkmapDocument? = null
    private var currentBinding: Disposable? = null
    private var disposed = false

    fun setPanel(panel: MarkmapPreviewPanel) {
        setTarget(panel)
    }

    fun clearPanel(panel: MarkmapPreviewPanel) {
        clearTarget(panel)
    }

    fun preview(document: Document) {
        preview(IdeaMarkmapDocument(document))
    }

    internal fun setTarget(candidate: MarkmapRenderTarget) {
        if (disposed) return
        target = candidate
        currentDocument?.let(::renderNow)
    }

    internal fun clearTarget(candidate: MarkmapRenderTarget) {
        if (target === candidate) {
            target = null
        }
    }

    internal fun preview(document: MarkmapDocument) {
        if (disposed) return
        currentBinding?.let(Disposer::dispose)
        scheduler.cancelPending()
        currentDocument = document
        currentBinding = document.addChangeListener {
            if (document === currentDocument) {
                scheduleRender(document)
            }
        }
        renderNow(document)
    }

    private fun scheduleRender(document: MarkmapDocument) {
        scheduler.cancelPending()
        scheduler.schedule(RENDER_DELAY_MS) {
            if (!disposed && document === currentDocument) {
                renderNow(document)
            }
        }
    }

    private fun renderNow(document: MarkmapDocument) {
        target?.render(document.text)
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        scheduler.cancelPending()
        currentBinding?.let(Disposer::dispose)
        currentBinding = null
        currentDocument = null
        target = null
        scheduler.dispose()
    }

    companion object {
        private const val RENDER_DELAY_MS = 300
    }
}

private class IdeaMarkmapDocument(
    private val document: Document,
) : MarkmapDocument {
    override val text: String
        get() = document.text

    override fun addChangeListener(listener: () -> Unit): Disposable {
        val binding = Disposer.newDisposable("MarkMap document binding")
        document.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    listener()
                }
            },
            binding,
        )
        return binding
    }
}

private class AlarmMarkmapRenderScheduler : MarkmapRenderScheduler {
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD)

    override fun schedule(delayMillis: Int, action: () -> Unit) {
        alarm.addRequest(action, delayMillis)
    }

    override fun cancelPending() {
        alarm.cancelAllRequests()
    }

    override fun dispose() {
        alarm.cancelAllRequests()
        alarm.dispose()
    }
}
