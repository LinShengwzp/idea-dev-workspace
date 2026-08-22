package com.anmi.devworkspace.servermonitor.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.servermonitor.domain.AuthType
import com.anmi.devworkspace.servermonitor.domain.ProcessInfo
import com.anmi.devworkspace.servermonitor.domain.ServerConfig
import com.anmi.devworkspace.servermonitor.domain.ServerMetrics
import com.anmi.devworkspace.servermonitor.ssh.MetricsCollector
import com.anmi.devworkspace.servermonitor.ssh.SshConfigService
import com.anmi.devworkspace.servermonitor.ssh.SshExecutor
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.FormBuilder
import com.intellij.openapi.wm.ToolWindow
import javax.swing.JSplitPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.RowFilter
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableRowSorter
import com.intellij.ui.components.JBPasswordField
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton

class ServerMonitorPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {
    private val sshConfigService = SshConfigService()
    private val settings = com.anmi.devworkspace.servermonitor.settings.ServerMonitorSettingsState.getInstance()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var refreshJob: Job? = null
    private var selectedServerId: String? = null

    private val serverTableModel = ServerTableModel()
    private val serverTable = JBTable(serverTableModel).apply {
        rowHeight = 36
        tableHeader.setReorderingAllowed(false)
        tableHeader.setResizingAllowed(true)
        setDefaultRenderer(Any::class.java, ServerTableCellRenderer())
        columnModel.getColumn(0).minWidth = 160
        columnModel.getColumn(0).preferredWidth = 160
        columnModel.getColumn(1).minWidth = 80
        columnModel.getColumn(1).preferredWidth = 80
        columnModel.getColumn(2).minWidth = 80
        columnModel.getColumn(2).preferredWidth = 80
        columnModel.getColumn(3).minWidth = 80
        columnModel.getColumn(3).preferredWidth = 80
        columnModel.getColumn(4).minWidth = 120
        columnModel.getColumn(4).preferredWidth = 120
        columnModel.getColumn(5).minWidth = 100
        columnModel.getColumn(5).preferredWidth = 100
        columnModel.getColumn(6).minWidth = 100
        columnModel.getColumn(6).preferredWidth = 100
        columnModel.getColumn(7).minWidth = 100
        columnModel.getColumn(7).preferredWidth = 100
    }

    private val processTableModel = ProcessTableModel()
    private val processTable = JBTable(processTableModel).apply {
        rowHeight = 28
        tableHeader.setReorderingAllowed(false)
        tableHeader.setResizingAllowed(true)
        setDefaultRenderer(Any::class.java, ProcessTableCellRenderer())
        columnModel.getColumn(0).minWidth = 60
        columnModel.getColumn(0).preferredWidth = 60
        columnModel.getColumn(1).minWidth = 120
        columnModel.getColumn(1).preferredWidth = 120
        columnModel.getColumn(2).minWidth = 60
        columnModel.getColumn(2).preferredWidth = 60
        columnModel.getColumn(3).minWidth = 60
        columnModel.getColumn(3).preferredWidth = 60
        columnModel.getColumn(4).minWidth = 200
        columnModel.getColumn(4).preferredWidth = 200
    }

    private val lastUpdateLabel = JBLabel().apply {
        border = JBUI.Borders.emptyLeft(8)
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
    }

    private val refreshAction = RefreshAction()
    private val autoRefreshAction = AutoRefreshAction()

    private val serverComboBox = ComboBox<ServerConfig>().apply {
        renderer = object : ColoredListCellRenderer<ServerConfig>() {
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
        addActionListener {
            val server = selectedItem as? ServerConfig
            selectedServerId = server?.id
            refreshProcesses()
        }
    }

    init {
        val toolbarGroup = DefaultActionGroup(refreshAction, autoRefreshAction, AddServerAction(), EditServerAction(), DeleteServerAction())
        val toolbar = ActionManager.getInstance().createActionToolbar("ServerMonitor", toolbarGroup, true)
        toolbar.targetComponent = this
        setToolbar(toolbar.component)

        val serverPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), DevWorkspaceBundle.message("servermonitor.panel.servers"))
            add(JBScrollPane(serverTable), BorderLayout.CENTER)
        }

        val processFilterField = SearchTextField()

        val processTableSorter = TableRowSorter<ProcessTableModel>(processTableModel)
        processTable.rowSorter = processTableSorter

        processFilterField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                val text = processFilterField.text.trim()
                if (text.isEmpty()) {
                    processTableSorter.rowFilter = null
                } else {
                    processTableSorter.rowFilter = RowFilter.regexFilter("(?i)$text", 0, 1, 2, 3, 4)
                }
            }
        })

        val processHeaderPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(serverComboBox, BorderLayout.WEST)
            add(processFilterField, BorderLayout.CENTER)
        }

        val processPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            border = BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), DevWorkspaceBundle.message("servermonitor.panel.processes"))
            add(processHeaderPanel, BorderLayout.NORTH)
            add(JBScrollPane(processTable), BorderLayout.CENTER)
        }

        // Use JSplitPane for resizable panels
        val splitPane = JSplitPane(JSplitPane.VERTICAL_SPLIT, serverPanel, processPanel).apply {
            resizeWeight = 0.4
            dividerLocation = 300
            isOneTouchExpandable = true
            setContinuousLayout(true)
        }

        val statusPanel = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT)).apply {
            add(lastUpdateLabel)
        }

        setContent(JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(splitPane, BorderLayout.CENTER)
            add(statusPanel, BorderLayout.SOUTH)
        })

        // Initialize server combo box and select first server
        updateServerComboBox()

        // Add selection listener for server table (after table is fully initialized)
        serverTable.selectionModel.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selectedRow = serverTable.selectedRow
                if (selectedRow >= 0) {
                    val modelRow = serverTable.convertRowIndexToModel(selectedRow)
                    val serverData = serverTableModel.data.getOrNull(modelRow)
                    serverData?.let { rowData ->
                        val server = settings.getServers().find { it.name == rowData.serverName }
                        server?.let { selectedServerId = it.id }
                    }
                }
            }
        }

        startAutoRefresh()
    }

    private fun startAutoRefresh() {
        refreshJob = scope.launch {
            while (scope.coroutineContext[Job]?.isActive == true) {
                refreshMetrics()
                val interval = getMinPollingInterval()
                delay(interval * 1000L)
            }
        }
    }

    private fun getMinPollingInterval(): Int {
        return settings.getServers()
            .filter { it.enabled }
            .map { it.pollingIntervalSeconds }
            .minOrNull() ?: 30
    }

    private fun refreshMetrics() {
        scope.launch {
            val servers = settings.getServers().filter { it.enabled }
            val metricsResults = mutableListOf<Pair<ServerConfig, ServerMetrics>>()
            var firstProcesses = emptyList<ProcessInfo>()

            for (server in servers) {
                val executor = SshExecutor(server)
                val collector = MetricsCollector(executor)
                val metrics = collector.collectMetrics()
                metricsResults.add(server to metrics)

                if (firstProcesses.isEmpty() && metrics.isHealthy) {
                    firstProcesses = collector.collectProcesses()
                }
            }

            withContext(Dispatchers.EDT) {
                serverTableModel.updateData(metricsResults)
                // Update combo box with current servers
                updateServerComboBox()
                // Refresh processes for selected server
                if (selectedServerId != null) {
                    val server = servers.find { it.id == selectedServerId }
                    server?.let { s ->
                        val executor = SshExecutor(s)
                        val collector = MetricsCollector(executor)
                        val processes = collector.collectProcesses()
                        processTableModel.updateData(processes)
                    }
                } else {
                    processTableModel.updateData(firstProcesses)
                }
                updateLastUpdateTime()
            }
        }
    }

    private fun updateLastUpdateTime() {
        val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        lastUpdateLabel.text = DevWorkspaceBundle.message("servermonitor.lastUpdate", format.format(Date()))
    }

    private fun updateServerComboBox() {
        val servers = settings.getServers().filter { it.enabled }
        SwingUtilities.invokeLater {
            serverComboBox.model = javax.swing.DefaultComboBoxModel(servers.toTypedArray())
            // Select the previously selected server or the first one
            if (selectedServerId != null) {
                servers.find { it.id == selectedServerId }?.let { serverComboBox.setSelectedItem(it) }
            } else if (servers.isNotEmpty()) {
                serverComboBox.selectedIndex = 0
                selectedServerId = servers.first().id
            }
        }
    }

    private fun refreshProcesses() {
        val serverId = selectedServerId ?: return
        val server = settings.getServers().find { it.id == serverId } ?: return
        
        scope.launch {
            val executor = SshExecutor(server)
            val collector = MetricsCollector(executor)
            val processes = collector.collectProcesses()
            
            withContext(Dispatchers.EDT) {
                processTableModel.updateData(processes)
            }
        }
    }

    override fun dispose() {
        refreshJob?.cancel()
        scope.cancel()
    }

    private inner class RefreshAction : AnAction(DevWorkspaceBundle.message("servermonitor.action.refresh"), null, AllIcons.Actions.Refresh) {
        override fun actionPerformed(e: AnActionEvent) {
            refreshMetrics()
        }

        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }

    private inner class AutoRefreshAction : AnAction(
        DevWorkspaceBundle.message("servermonitor.action.autoRefresh"),
        null,
        AllIcons.Actions.Execute
    ) {
        private var enabled = true

        override fun actionPerformed(e: AnActionEvent) {
            enabled = !enabled
            if (enabled) {
                startAutoRefresh()
            } else {
                refreshJob?.cancel()
            }
        }

        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }

    private inner class AddServerAction : AnAction(
        DevWorkspaceBundle.message("servermonitor.action.addServer"),
        null,
        AllIcons.General.Add
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val dialog = ServerConfigDialog(null)
            dialog.show()
            if (dialog.isOK) {
                val server = dialog.createdServer
                server?.let { settings.setServers(settings.getServers() + it) }
                refreshMetrics()
            }
        }

        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }

    private inner class EditServerAction : AnAction(
        DevWorkspaceBundle.message("servermonitor.action.editServer"),
        null,
        AllIcons.Actions.Edit
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val selectedRow = serverTable.selectedRow
            if (selectedRow >= 0) {
                val modelRow = serverTable.convertRowIndexToModel(selectedRow)
                val serverData = serverTableModel.data.getOrNull(modelRow)
                serverData?.let { rowData ->
                    val server = settings.getServers().find { it.name == rowData.serverName }
                    server?.let { serverToEdit ->
                        val dialog = ServerConfigDialog(serverToEdit)
                        dialog.show()
                        if (dialog.isOK) {
                            val updatedServer = dialog.createdServer
                            updatedServer?.let {
                                val servers = settings.getServers().map { if (it.id == updatedServer.id) updatedServer else it }
                                settings.setServers(servers)
                                refreshMetrics()
                            }
                        }
                    }
                }
            }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = serverTable.selectedRow >= 0
        }

        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }

    private inner class DeleteServerAction : AnAction(
        DevWorkspaceBundle.message("servermonitor.action.deleteServer"),
        null,
        AllIcons.General.Remove
    ) {
        override fun actionPerformed(e: AnActionEvent) {
            val selectedRow = serverTable.selectedRow
            if (selectedRow >= 0) {
                val modelRow = serverTable.convertRowIndexToModel(selectedRow)
                val serverData = serverTableModel.data.getOrNull(modelRow)
                serverData?.let { rowData ->
                    val server = settings.getServers().find { it.name == rowData.serverName }
                    server?.let { serverToDelete ->
                        val confirm = com.intellij.openapi.ui.Messages.showYesNoDialog(
                            DevWorkspaceBundle.message("servermonitor.delete.confirm", serverToDelete.name),
                            DevWorkspaceBundle.message("servermonitor.delete.title"),
                            com.intellij.openapi.ui.Messages.getQuestionIcon()
                        )
                        if (confirm == com.intellij.openapi.ui.Messages.YES) {
                            val servers = settings.getServers().filter { it.id != serverToDelete.id }
                            settings.setServers(servers)
                            if (selectedServerId == serverToDelete.id) {
                                selectedServerId = null
                            }
                            refreshMetrics()
                        }
                    }
                }
            }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = serverTable.selectedRow >= 0
        }

        override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
    }
}

class ServerTableModel : DefaultTableModel(
    arrayOf("", "", "", "", "", "", "", ""), 0
) {
    private val columnNames = arrayOf(
        DevWorkspaceBundle.message("servermonitor.column.server"),
        DevWorkspaceBundle.message("servermonitor.column.cpu"),
        DevWorkspaceBundle.message("servermonitor.column.memory"),
        DevWorkspaceBundle.message("servermonitor.column.disk"),
        DevWorkspaceBundle.message("servermonitor.column.load1"),
        DevWorkspaceBundle.message("servermonitor.column.load5"),
        DevWorkspaceBundle.message("servermonitor.column.load15"),
        DevWorkspaceBundle.message("servermonitor.column.status")
    )

    override fun getColumnCount(): Int = columnNames.size
    override fun getColumnName(column: Int): String = columnNames[column]
    override fun isCellEditable(row: Int, column: Int): Boolean = false

    internal var data: MutableList<ServerRowData> = mutableListOf()
        private set

    data class ServerRowData(
        val serverName: String,
        val cpuUsage: Double?,
        val memoryUsage: Double?,
        val diskUsage: Double?,
        val load1: Double?,
        val load5: Double?,
        val load15: Double?,
        val isHealthy: Boolean,
        val error: String?
    )

    fun updateData(results: List<Pair<com.anmi.devworkspace.servermonitor.domain.ServerConfig, ServerMetrics>>) {
        data = results.map { (server, metrics) ->
            ServerRowData(
                serverName = server.name,
                cpuUsage = metrics.cpuUsagePercent,
                memoryUsage = metrics.memoryUsagePercent,
                diskUsage = metrics.diskUsagePercent,
                load1 = metrics.loadAverage1m,
                load5 = metrics.loadAverage5m,
                load15 = metrics.loadAverage15m,
                isHealthy = metrics.isHealthy,
                error = metrics.error
            )
        }.toMutableList()
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = data.size
    override fun getValueAt(row: Int, column: Int): Any = data.getOrNull(row)?.let { rowData ->
        when (column) {
            0 -> rowData.serverName
            1 -> rowData.cpuUsage?.let { String.format("%.1f%%", it) } ?: "N/A"
            2 -> rowData.memoryUsage?.let { String.format("%.1f%%", it) } ?: "N/A"
            3 -> rowData.diskUsage?.let { String.format("%.1f%%", it) } ?: "N/A"
            4 -> rowData.load1?.let { String.format("%.2f", it) } ?: "N/A"
            5 -> rowData.load5?.let { String.format("%.2f", it) } ?: "N/A"
            6 -> rowData.load15?.let { String.format("%.2f", it) } ?: "N/A"
            7 -> if (rowData.isHealthy) DevWorkspaceBundle.message("servermonitor.status.healthy") else DevWorkspaceBundle.message("servermonitor.status.unhealthy", rowData.error ?: "")
            else -> ""
        }
    } ?: ""
}

class ServerTableCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: javax.swing.JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        val model = table.model as ServerTableModel
        val rowData = model.data.getOrNull(row)

        if (!isSelected) {
            if (column in 1..3) {
                // CPU, Memory, Disk columns - color based on usage
                val usage = when (column) {
                    1 -> rowData?.cpuUsage
                    2 -> rowData?.memoryUsage
                    3 -> rowData?.diskUsage
                    else -> null
                }
                component.background = getUsageColor(usage)
                component.foreground = JBColor(Color.BLACK, Color.WHITE)
            } else if (column == 7) {
                // Status column
                component.foreground = if (rowData?.isHealthy == true) {
                    JBColor(Color.GREEN.darker(), Color.GREEN)
                } else {
                    JBColor(Color.RED.darker(), Color.RED)
                }
                component.background = if (isSelected) table.selectionBackground else table.background
            } else {
                component.background = if (isSelected) table.selectionBackground else table.background
                component.foreground = if (isSelected) table.selectionForeground else table.foreground
            }
        }

        horizontalAlignment = if (column == 0) SwingConstants.LEFT else SwingConstants.CENTER
        border = JBUI.Borders.empty(4, 8)
        return component
    }

    private fun getUsageColor(usage: Double?): Color {
        return when {
            usage == null -> JBColor(Color.LIGHT_GRAY, Color.DARK_GRAY)
            usage >= 90.0 -> JBColor(Color.RED.brighter(), Color.RED.darker())
            usage >= 70.0 -> JBColor(Color.ORANGE, Color.ORANGE.darker())
            usage >= 50.0 -> JBColor(Color.YELLOW.darker(), Color.YELLOW.darker())
            else -> JBColor(Color.GREEN.darker(), Color.GREEN)
        }
    }
}

class ProcessTableModel : DefaultTableModel(
    arrayOf("", "", "", "", ""), 0
) {
    private val columnNames = arrayOf(
        DevWorkspaceBundle.message("servermonitor.process.column.pid"),
        DevWorkspaceBundle.message("servermonitor.process.column.user"),
        DevWorkspaceBundle.message("servermonitor.process.column.cpu"),
        DevWorkspaceBundle.message("servermonitor.process.column.memory"),
        DevWorkspaceBundle.message("servermonitor.process.column.command")
    )

    override fun getColumnCount(): Int = columnNames.size
    override fun getColumnName(column: Int): String = columnNames[column]
    override fun isCellEditable(row: Int, column: Int): Boolean = false

    internal var data: MutableList<ProcessRowData> = mutableListOf()
        private set

    data class ProcessRowData(
        val pid: String,
        val user: String,
        val cpu: String,
        val memory: String,
        val command: String
    )

    fun updateData(processes: List<ProcessInfo>) {
        if (processes.isEmpty()) {
            data = mutableListOf(
                ProcessRowData("N/A", "N/A", "N/A", "N/A", DevWorkspaceBundle.message("servermonitor.process.notAvailable"))
            )
        } else {
            data = processes.map { info ->
                ProcessRowData(
                    pid = info.pid,
                    user = info.user,
                    cpu = "${info.cpu}%",
                    memory = "${info.memory}%",
                    command = info.command
                )
            }.toMutableList()
        }
        fireTableDataChanged()
    }

    override fun getRowCount(): Int = data.size
    override fun getValueAt(row: Int, column: Int): Any = data.getOrNull(row)?.let { rowData ->
        when (column) {
            0 -> rowData.pid
            1 -> rowData.user
            2 -> rowData.cpu
            3 -> rowData.memory
            4 -> rowData.command
            else -> ""
        }
    } ?: ""
}

class ProcessTableCellRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: javax.swing.JTable,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int
    ): Component {
        val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
        border = JBUI.Borders.empty(2, 8)
        horizontalAlignment = if (column == 4) SwingConstants.LEFT else SwingConstants.CENTER
        return component
    }
}
