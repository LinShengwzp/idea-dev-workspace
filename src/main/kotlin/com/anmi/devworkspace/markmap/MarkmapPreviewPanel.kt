package com.anmi.devworkspace.markmap

import com.anmi.devworkspace.DevWorkspaceBundle.message
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import java.awt.BorderLayout
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.swing.JPanel

internal object MarkmapWebViewProtocol {
    fun renderScript(markdown: String, optionsJson: String): String =
        "window.updateMarkmapFromBase64('${encode(markdown)}', '${encode(optionsJson)}')"

    private fun encode(text: String): String =
        Base64.getEncoder().encodeToString(text.toByteArray(StandardCharsets.UTF_8))
}

class MarkmapPreviewPanel(
    private val service: MarkmapPreviewService? = null,
) : JPanel(BorderLayout()), MarkmapRenderTarget, Disposable {
    private var browser: JBCefBrowser? = null
    private var disposed = false

    init {
        when {
            !JBCefApp.isSupported() -> {
                add(JBLabel(message("markmap.preview.jcef.unsupported")), BorderLayout.CENTER)
            }

            javaClass.getResource(WEBVIEW_RESOURCE) == null -> {
                add(JBLabel(message("markmap.preview.resource.missing")), BorderLayout.CENTER)
            }

            else -> {
                val createdBrowser = JBCefBrowser()
                browser = createdBrowser
                add(createdBrowser.component, BorderLayout.CENTER)
                val html = requireNotNull(javaClass.getResource(WEBVIEW_RESOURCE)).readText()
                createdBrowser.loadHTML(html)
            }
        }
    }

    override fun render(markdown: String, optionsJson: String) {
        val script = MarkmapWebViewProtocol.renderScript(markdown, optionsJson)
        ApplicationManager.getApplication().invokeLater {
            if (!disposed) {
                browser?.cefBrowser?.let { cefBrowser ->
                    cefBrowser.executeJavaScript(script, cefBrowser.url, 0)
                }
            }
        }
    }

    override fun dispose() {
        if (disposed) return
        disposed = true
        service?.clearPanel(this)
        browser?.let(Disposer::dispose)
        browser = null
    }

    companion object {
        private const val WEBVIEW_RESOURCE = "/markmap/index.html"
    }
}
