package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.library.domain.LibraryItem
import com.anmi.devworkspace.library.domain.LibraryItemSource
import com.anmi.devworkspace.library.preview.ImagePreview
import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JPopupMenu

/**
 * Rich details view with platform links and explicit copy actions.
 *
 * Link callbacks receive domain values only; filesystem checks and IntelliJ
 * effects remain in the opening adapters.
 */
class LibraryDetailsPanel(
    private val openTarget: (LibraryItem) -> Unit,
    private val openSource: (LibraryItemSource) -> Unit,
    private val model: LibraryDetailsModel = LibraryDetailsModel(),
) : JBPanel<LibraryDetailsPanel>(CardLayout()) {
    private val cards = layout as CardLayout
    private val empty = JBLabel(message("library.details.empty"))
    private val title = JBLabel()
    private val type = JBLabel()
    private val scope = JBLabel()
    private val group = JBLabel()
    private val tags = JBLabel()
    private val status = JBLabel()
    private val note = JBTextArea(3, 30).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty()
    }
    private val targetLink = ActionLink("")
    private val sourceLink = ActionLink("")
    private val targetCopy = copyButton { targetLink.text }
    private val sourceCopy = copyButton { sourceLink.text }
    private val targetRow = linkRow(targetLink, targetCopy)
    private val sourceRow = linkRow(sourceLink, sourceCopy)
    private val created = JBLabel()
    private val updated = JBLabel()
    private val preview = JEditorPane("text/html", "").apply {
        isEditable = false
        border = JBUI.Borders.empty()
    }
    private val image = JLabel().apply {
        horizontalAlignment = JLabel.CENTER
        verticalAlignment = JLabel.CENTER
    }
    private val previewEmpty = JBLabel(message("library.preview.none"))
    private val previewCards = CardLayout()
    private val previewBody = JBPanel<JBPanel<*>>(previewCards).apply {
        preferredSize = Dimension(JBUI.scale(300), JBUI.scale(220))
        add(previewEmpty, PREVIEW_EMPTY)
        add(JBScrollPane(preview), PREVIEW_MARKDOWN)
        add(JBScrollPane(image), PREVIEW_IMAGE)
    }
    private val fields = FormBuilder.createFormBuilder()
        .addLabeledComponent(message("library.details.title"), title)
        .addLabeledComponent(message("library.details.type"), type)
        .addLabeledComponent(message("library.details.scope"), scope)
        .addLabeledComponent(message("library.details.group"), group)
        .addLabeledComponent(message("library.details.tags"), tags)
        .addLabeledComponent(message("library.details.status"), status)
        .addLabeledComponent(message("library.details.note"), JBScrollPane(note))
        .addLabeledComponent(message("library.details.target"), targetRow)
        .addLabeledComponent(message("library.details.source"), sourceRow)
        .addLabeledComponent(message("library.details.created"), created)
        .addLabeledComponent(message("library.details.updated"), updated)
        .panel.apply { border = JBUI.Borders.empty(8) }
    private val item = JBPanel<JBPanel<*>>(BorderLayout(0, JBUI.scale(6))).apply {
        add(JBScrollPane(fields).apply { border = JBUI.Borders.empty() }, BorderLayout.NORTH)
        add(previewBody, BorderLayout.CENTER)
    }
    private var current: LibraryListItem? = null

    init {
        border = JBUI.Borders.empty()
        title.font = title.font.deriveFont(title.font.size2D + 2f)
        targetLink.addActionListener { current?.item?.let(openTarget) }
        sourceLink.addActionListener { current?.item?.source?.let(openSource) }
        installCopyPopup(targetLink) { targetLink.text }
        installCopyPopup(sourceLink) { sourceLink.text }
        add(empty, EMPTY)
        add(item, ITEM)
        cards.show(this, EMPTY)
    }

    fun show(value: LibraryListItem?) {
        current = value
        if (value == null) {
            cards.show(this, EMPTY)
            return
        }
        val details = model.present(value)
        title.text = details.title
        type.text = message("library.type.${details.type.name.lowercase()}")
        scope.text = message("library.scope.${details.scope.name.lowercase().replace('_', '.')}")
        group.text = details.groupName ?: message("library.group.none")
        tags.text = details.tags.takeIf(List<String>::isNotEmpty)?.joinToString(" · ")
            ?: message("library.details.none")
        status.text = message("library.details.status.${details.status.name.lowercase()}")
        val warning = details.status == LibraryDetailsStatus.TARGET_MISSING ||
            details.status == LibraryDetailsStatus.SOURCE_MISSING ||
            details.status == LibraryDetailsStatus.TARGET_AND_SOURCE_MISSING
        status.icon = AllIcons.General.Warning.takeIf { warning }
        status.foreground = if (warning) UIUtil.getErrorForeground() else UIUtil.getLabelForeground()
        note.text = details.note.orEmpty()
        targetLink.text = details.target ?: message("library.details.none")
        targetLink.isEnabled = details.target != null
        targetCopy.isVisible = details.target != null
        sourceLink.text = details.source?.let(::formatSource) ?: message("library.details.none")
        sourceLink.isEnabled = details.source != null
        sourceCopy.isVisible = details.source != null
        created.text = FORMATTER.format(details.createdAt)
        updated.text = FORMATTER.format(details.updatedAt)
        when (details.previewMode) {
            LibraryPreviewMode.NONE -> previewCards.show(previewBody, PREVIEW_EMPTY)
            LibraryPreviewMode.MARKDOWN -> {
                previewEmpty.text = message("library.preview.loading")
                previewCards.show(previewBody, PREVIEW_EMPTY)
            }
            LibraryPreviewMode.IMAGE -> {
                previewEmpty.text = message("library.preview.loading")
                previewCards.show(previewBody, PREVIEW_EMPTY)
            }
        }
        cards.show(this, ITEM)
    }

    fun showHtml(html: String) {
        preview.text = html
        preview.caretPosition = 0
        previewCards.show(previewBody, PREVIEW_MARKDOWN)
    }

    fun showImage(value: ImagePreview) {
        image.icon = (value as? ImagePreview.Available)?.let { ImageIcon(it.image) }
        image.text = if (value is ImagePreview.Placeholder) message("library.preview.unavailable") else null
        previewCards.show(previewBody, PREVIEW_IMAGE)
    }

    private fun linkRow(link: ActionLink, copy: JButton): JComponent =
        JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(4), 0)).apply {
            isOpaque = false
            add(link, BorderLayout.CENTER)
            add(copy, BorderLayout.EAST)
        }

    private fun copyButton(value: () -> String): JButton =
        JButton(AllIcons.General.Copy).apply {
            toolTipText = message("library.details.copy")
            isFocusPainted = false
            border = JBUI.Borders.empty(2)
            addActionListener { copy(value()) }
        }

    private fun installCopyPopup(component: JComponent, value: () -> String) {
        component.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(event: MouseEvent) = showPopup(event)
            override fun mouseReleased(event: MouseEvent) = showPopup(event)

            private fun showPopup(event: MouseEvent) {
                if (!event.isPopupTrigger) return
                JPopupMenu().apply {
                    add(JMenuItem(message("library.details.copy")).apply {
                        addActionListener { copy(value()) }
                    })
                    show(component, event.x, event.y)
                }
            }
        })
    }

    private fun copy(value: String) {
        if (value.isNotEmpty()) CopyPasteManager.getInstance().setContents(StringSelection(value))
    }

    private fun formatSource(source: LibraryItemSource): String = buildString {
        append(source.path)
        if (source.startLine != null && source.endLine != null) {
            append(':').append(source.startLine).append('-').append(source.endLine)
        }
    }

    private companion object {
        const val EMPTY = "empty"
        const val ITEM = "item"
        const val PREVIEW_EMPTY = "empty-preview"
        const val PREVIEW_MARKDOWN = "markdown-preview"
        const val PREVIEW_IMAGE = "image-preview"
        val FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())

        fun message(key: String): String = DevWorkspaceBundle.message(key)
    }
}
