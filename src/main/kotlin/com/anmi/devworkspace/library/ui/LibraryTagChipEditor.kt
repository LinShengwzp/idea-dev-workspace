package com.anmi.devworkspace.library.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.Locale
import javax.swing.JButton
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent

data class LibraryTagChipState(
    val tags: List<String>,
    val input: String,
)

/**
 * Pure insertion-ordered tag state. Normalized keys deduplicate tags without
 * changing the first spelling entered by the user.
 */
class LibraryTagChipEditorModel(initial: Collection<String> = emptyList()) {
    var state: LibraryTagChipState = LibraryTagChipState(deduplicate(initial), "")
        private set

    fun updateInput(value: String) {
        state = state.copy(input = value)
    }

    fun commit(value: String = state.input): LibraryTagChipState {
        state = LibraryTagChipState(
            tags = deduplicate(state.tags + value.split(DELIMITERS)),
            input = "",
        )
        return state
    }

    fun backspace(input: String): LibraryTagChipState {
        state = if (input.isEmpty() && state.tags.isNotEmpty()) {
            state.copy(tags = state.tags.dropLast(1))
        } else {
            state.copy(input = input)
        }
        return state
    }

    fun remove(tag: String): LibraryTagChipState {
        val key = normalize(tag)
        state = state.copy(tags = state.tags.filterNot { normalize(it) == key })
        return state
    }

    private fun deduplicate(values: Collection<String>): List<String> {
        val seen = hashSetOf<String>()
        return values.map(String::trim)
            .filter(String::isNotEmpty)
            .filter { seen.add(normalize(it)) }
    }

    private companion object {
        val DELIMITERS = Regex("[,，;；\\r\\n]+")
        fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
    }
}

/**
 * Theme-safe tag editor built only from stable Swing and IntelliJ components.
 *
 * Delimiter-containing paste is committed immediately; Enter and typed
 * delimiters share the same pure model boundary.
 */
class LibraryTagChipEditor(
    initial: Collection<String> = emptyList(),
) : JBPanel<LibraryTagChipEditor>(BorderLayout(0, JBUI.scale(3))) {
    private val model = LibraryTagChipEditorModel(initial)
    private val chips = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0))
    private val input = JBTextField()
    private var synchronizing = false

    val tags: Set<String>
        get() = LinkedHashSet(model.state.tags)

    fun commitPending() {
        if (input.text.isNotBlank()) commitInput()
    }

    init {
        border = JBUI.Borders.empty(3)
        add(chips, BorderLayout.CENTER)
        add(input, BorderLayout.SOUTH)
        input.emptyText.text = message("library.editor.tags.hint")
        input.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (synchronizing) return
                model.updateInput(input.text)
                if (DELIMITER.containsMatchIn(input.text)) {
                    SwingUtilities.invokeLater(::commitInput)
                }
            }
        })
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: KeyEvent) {
                when {
                    event.keyCode == KeyEvent.VK_ENTER -> {
                        commitInput()
                        event.consume()
                    }
                    event.keyCode == KeyEvent.VK_BACK_SPACE && input.text.isEmpty() -> {
                        model.backspace("")
                        renderChips()
                        event.consume()
                    }
                }
            }
        })
        renderChips()
    }

    private fun commitInput() {
        if (synchronizing) return
        model.commit(input.text)
        synchronizing = true
        try {
            input.text = ""
        } finally {
            synchronizing = false
        }
        renderChips()
        input.requestFocusInWindow()
    }

    private fun renderChips() {
        chips.removeAll()
        model.state.tags.forEach { tag ->
            chips.add(
                JButton("$tag ×").apply {
                    margin = JBUI.insets(1, 5)
                    isFocusPainted = false
                    toolTipText = message("library.editor.tags.remove", tag)
                    addActionListener {
                        this@LibraryTagChipEditor.model.remove(tag)
                        renderChips()
                        input.requestFocusInWindow()
                    }
                },
            )
        }
        chips.revalidate()
        chips.repaint()
    }

    private companion object {
        val DELIMITER = Regex("[,，;；\\r\\n]")
        fun message(key: String, vararg params: Any): String = DevWorkspaceBundle.message(key, *params)
    }
}
