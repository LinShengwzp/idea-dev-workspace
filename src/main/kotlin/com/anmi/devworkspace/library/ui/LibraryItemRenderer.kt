package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridLayout
import javax.swing.Icon
import javax.swing.JList
import javax.swing.ListCellRenderer

/** IntelliJ icon adapter; file items prefer the icon resolved from their target filename. */
class IdeaLibraryIconResolver {
    fun resolve(row: LibraryListItem, kind: LibraryIconKind): Icon = when (kind) {
        LibraryIconKind.MARKDOWN -> AllIcons.FileTypes.Markdown
        LibraryIconKind.LINK -> AllIcons.General.Web
        LibraryIconKind.FILE -> fileIcon(row.item.target) ?: AllIcons.FileTypes.Any_type
        LibraryIconKind.IMAGE -> AllIcons.FileTypes.Image
        LibraryIconKind.MEDIA -> AllIcons.Actions.Play_forward
    }

    private fun fileIcon(target: String?): Icon? {
        val fileName = target
            ?.replace('\\', '/')
            ?.substringAfterLast('/')
            ?.takeIf(String::isNotBlank)
            ?: return null
        return FileTypeManager.getInstance().getFileTypeByFileName(fileName).icon
    }
}

class LibraryItemRenderer(
    private val model: LibraryRowPresentationModel = LibraryRowPresentationModel(),
    private val icons: IdeaLibraryIconResolver = IdeaLibraryIconResolver(),
) : ListCellRenderer<LibraryListItem> {
    private val icon = JBLabel().apply {
        border = JBUI.Borders.emptyRight(8)
        verticalAlignment = JBLabel.TOP
    }
    private val title = JBLabel()
    private val metadata = JBLabel()
    private val summary = JBLabel()
    private val text = JBPanel<JBPanel<*>>(GridLayout(3, 1, 0, 1)).apply {
        isOpaque = false
        add(title)
        add(metadata)
        add(summary)
    }
    private val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = JBUI.Borders.empty(5, 8)
        add(icon, BorderLayout.WEST)
        add(text, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out LibraryListItem>,
        value: LibraryListItem,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val presentation = model.present(value)
        icon.icon = icons.resolve(value, presentation.iconKind)
        title.text = if (presentation.favorite) "★ ${presentation.title}" else presentation.title
        metadata.text = metadata(presentation)
        summary.text = presentation.summary.orEmpty()
        summary.isVisible = presentation.summary != null

        val foreground = if (isSelected) list.selectionForeground else list.foreground
        panel.background = if (isSelected) list.selectionBackground else list.background
        panel.isOpaque = true
        title.foreground = foreground
        metadata.foreground = when {
            isSelected -> foreground
            presentation.warning != LibraryRowWarning.NONE -> UIUtil.getErrorForeground()
            else -> UIUtil.getContextHelpForeground()
        }
        summary.foreground = if (isSelected) foreground else UIUtil.getContextHelpForeground()
        return panel
    }

    private fun metadata(presentation: LibraryRowPresentation): String = buildList {
        add(message("library.scope.${presentation.scope.name.lowercase().replace('_', '.')}"))
        presentation.groupName?.let(::add)
        if (presentation.tags.isNotEmpty()) add(presentation.tags.joinToString(" · "))
        warningText(presentation.warning)?.let(::add)
    }.joinToString("  ·  ")

    private fun warningText(warning: LibraryRowWarning): String? = when (warning) {
        LibraryRowWarning.NONE -> null
        LibraryRowWarning.TARGET_MISSING -> message("library.path.missing")
        LibraryRowWarning.SOURCE_MISSING -> message("library.source.missing")
        LibraryRowWarning.TARGET_AND_SOURCE_MISSING ->
            "${message("library.path.missing")}、${message("library.source.missing")}"
    }

    private fun message(key: String): String = DevWorkspaceBundle.message(key)
}
