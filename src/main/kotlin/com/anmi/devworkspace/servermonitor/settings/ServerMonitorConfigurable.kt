package com.anmi.devworkspace.servermonitor.settings

import com.anmi.devworkspace.DevWorkspaceBundle.message
import com.anmi.devworkspace.servermonitor.domain.ServerConfig
import com.anmi.devworkspace.servermonitor.ui.ServerConfigDialog
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ListSpeedSearch
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class ServerMonitorConfigurable(
    private val settings: ServerMonitorSettingsState = ServerMonitorSettingsState.getInstance(),
) : SearchableConfigurable {

    private var listModel: DefaultListModel<ServerConfig>? = null
    private var serverList: JBList<ServerConfig>? = null

    override fun getId(): String = ID

    override fun getDisplayName(): String = message("settings.serverMonitor.name")

    override fun createComponent(): JComponent {
        listModel = DefaultListModel<ServerConfig>().apply {
            settings.getServers().forEach { addElement(it) }
        }
        serverList = JBList<ServerConfig>(listModel!!).apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = object : ColoredListCellRenderer<ServerConfig>() {
                override fun customizeCellRenderer(
                    list: javax.swing.JList<out ServerConfig>,
                    value: ServerConfig,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append(value.name)
                    if (!value.enabled) append(" (disabled)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            ListSpeedSearch(this) { it.name }
        }

        val addButton = JButton(message("settings.serverMonitor.add")).apply {
            addActionListener { showAddEditDialog(null) }
        }
        val editButton = JButton(message("settings.serverMonitor.edit")).apply {
            addActionListener { showAddEditDialog(serverList?.selectedValue) }
        }
        val removeButton = JButton(message("settings.serverMonitor.remove")).apply {
            addActionListener { removeSelected() }
        }

        val buttonsPanel = JPanel().apply {
            add(addButton)
            add(editButton)
            add(removeButton)
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(message("settings.serverMonitor.servers.label"), JBScrollPane(serverList!!))
            .addComponent(buttonsPanel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    private fun showAddEditDialog(serverToEdit: ServerConfig?) {
        val dialog = ServerConfigDialog(serverToEdit)
        dialog.show()
        if (dialog.isOK) {
            val server = dialog.createdServer
            if (serverToEdit == null) {
                listModel?.addElement(server)
            } else {
                val index = listModel?.indexOf(serverToEdit)
                if (index != null && index >= 0) {
                    listModel?.set(index, server)
                }
            }
        }
    }

    private fun removeSelected() {
        val selected = serverList?.selectedValue
        selected?.let { listModel?.removeElement(it) }
    }

    override fun isModified(): Boolean {
        val currentServers = listModel?.let { model ->
            (0 until model.size).map { model.getElementAt(it) }
        } ?: emptyList()
        return currentServers != settings.getServers()
    }

    override fun apply() {
        val servers = listModel?.let { model ->
            (0 until model.size).map { model.getElementAt(it) }
        } ?: emptyList()
        settings.setServers(servers)
    }

    override fun reset() {
        listModel?.clear()
        settings.getServers().forEach { listModel?.addElement(it) }
    }

    override fun disposeUIResources() {
        listModel = null
        serverList = null
    }

    companion object {
        const val ID: String = "com.anmi.devworkspace.settings.serverMonitor"
    }
}
