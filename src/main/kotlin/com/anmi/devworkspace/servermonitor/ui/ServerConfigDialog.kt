package com.anmi.devworkspace.servermonitor.ui

import com.anmi.devworkspace.DevWorkspaceBundle
import com.anmi.devworkspace.servermonitor.domain.AuthType
import com.anmi.devworkspace.servermonitor.domain.ServerConfig
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.JBCheckBox
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.FormBuilder
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import javax.swing.JComponent

class ServerConfigDialog(private val serverToEdit: ServerConfig?) : DialogWrapper(true) {

    private var nameField: JBTextField? = null
    private var hostField: JBTextField? = null
    private var portField: JBTextField? = null
    private var usernameField: JBTextField? = null
    private var authTypeCombo: ComboBox<AuthType>? = null
    private var passwordField: JBPasswordField? = null
    private var keyFileField: TextFieldWithBrowseButton? = null
    private var keyPassphraseField: JBPasswordField? = null
    private var enabledCheckBox: JBCheckBox? = null
    private var intervalField: JBTextField? = null
    var createdServer: ServerConfig? = null

    init {
        title = if (serverToEdit == null) DevWorkspaceBundle.message("servermonitor.dialog.addServer.title") else DevWorkspaceBundle.message("servermonitor.dialog.editServer.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        nameField = JBTextField(serverToEdit?.name ?: "")
        hostField = JBTextField(serverToEdit?.host ?: "")
        portField = JBTextField(serverToEdit?.port?.toString() ?: "22")
        usernameField = JBTextField(serverToEdit?.username ?: "")
        authTypeCombo = ComboBox<AuthType>(AuthType.values()).apply {
            renderer = object : ColoredListCellRenderer<AuthType>() {
                override fun customizeCellRenderer(
                    list: javax.swing.JList<out AuthType>,
                    value: AuthType,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean,
                ) {
                    append(when (value) {
                        AuthType.PASSWORD -> DevWorkspaceBundle.message("servermonitor.dialog.authType.password")
                        AuthType.KEY_FILE -> DevWorkspaceBundle.message("servermonitor.dialog.authType.keyFile")
                    })
                }
            }
            if (serverToEdit != null) {
                setSelectedItem(serverToEdit.authType)
            }
        }
        passwordField = JBPasswordField().apply { text = serverToEdit?.password ?: "" }
        keyFileField = TextFieldWithBrowseButton().apply {
            textField.text = serverToEdit?.keyFilePath ?: ""
            val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            addBrowseFolderListener(
                DevWorkspaceBundle.message("servermonitor.dialog.keyFile.choose"),
                DevWorkspaceBundle.message("servermonitor.dialog.keyFile.choose.description"),
                null,
                descriptor,
            )
        }
        keyPassphraseField = JBPasswordField().apply { text = serverToEdit?.keyPassphrase ?: "" }
        enabledCheckBox = JBCheckBox(DevWorkspaceBundle.message("servermonitor.dialog.enabled"), serverToEdit?.enabled ?: true)
        intervalField = JBTextField((serverToEdit?.pollingIntervalSeconds ?: 30).toString())

        // Panel for password auth
        val passwordPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(DevWorkspaceBundle.message("servermonitor.dialog.password"), passwordField!!)
            .panel

        // Panel for key file auth
        val keyFilePanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(DevWorkspaceBundle.message("servermonitor.dialog.keyFile"), keyFileField!!)
            .addLabeledComponent(DevWorkspaceBundle.message("servermonitor.dialog.keyPassphrase"), keyPassphraseField!!)
            .panel

        val mainPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(DevWorkspaceBundle.message("servermonitor.dialog.name"), nameField!!)
            .addLabeledComponent(DevWorkspaceBundle.message("servermonitor.dialog.host"), hostField!!)
            .addLabeledComponent(DevWorkspaceBundle.message("servermonitor.dialog.port"), portField!!)
            .addLabeledComponent(DevWorkspaceBundle.message("servermonitor.dialog.username"), usernameField!!)
            .addLabeledComponent(DevWorkspaceBundle.message("servermonitor.dialog.authType"), authTypeCombo!!)
            .addComponent(passwordPanel)
            .addComponent(keyFilePanel)
            .addComponent(enabledCheckBox!!)
            .addLabeledComponent(DevWorkspaceBundle.message("servermonitor.dialog.pollingInterval"), intervalField!!)
            .panel

        // Show/hide password/key fields based on auth type
        authTypeCombo?.addActionListener {
            val selectedAuthType = authTypeCombo?.selectedItem as? AuthType ?: AuthType.PASSWORD
            passwordPanel.isVisible = selectedAuthType == AuthType.PASSWORD
            keyFilePanel.isVisible = selectedAuthType == AuthType.KEY_FILE
            mainPanel.revalidate()
            mainPanel.repaint()
        }

        // Initialize visibility
        val initialAuthType = serverToEdit?.authType ?: AuthType.PASSWORD
        passwordPanel.isVisible = initialAuthType == AuthType.PASSWORD
        keyFilePanel.isVisible = initialAuthType == AuthType.KEY_FILE

        return mainPanel
    }

    override fun doOKAction() {
        val name = nameField?.text?.trim() ?: return
        val host = hostField?.text?.trim() ?: return
        val port = portField?.text?.toIntOrNull() ?: return
        val username = usernameField?.text?.trim() ?: return
        val authType = authTypeCombo?.selectedItem as? AuthType ?: AuthType.PASSWORD
        val password = if (authType == AuthType.PASSWORD) String(passwordField?.password ?: charArrayOf()) else ""
        val keyFilePath = if (authType == AuthType.KEY_FILE) keyFileField?.text?.trim() ?: "" else ""
        val keyPassphrase = if (authType == AuthType.KEY_FILE) String(keyPassphraseField?.password ?: charArrayOf()) else ""
        val enabled = enabledCheckBox?.isSelected ?: true
        val interval = intervalField?.text?.toIntOrNull() ?: 30

        createdServer = ServerConfig(
            id = serverToEdit?.id ?: java.util.UUID.randomUUID().toString(),
            name = name,
            host = host,
            port = port,
            username = username,
            authType = authType,
            password = password,
            keyFilePath = keyFilePath,
            keyPassphrase = keyPassphrase,
            enabled = enabled,
            pollingIntervalSeconds = interval,
        )
        super.doOKAction()
    }
}
