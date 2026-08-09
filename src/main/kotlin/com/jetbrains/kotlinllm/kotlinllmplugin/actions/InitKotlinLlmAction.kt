package com.jetbrains.kotlinllm.kotlinllmplugin.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.ensureGeneratedAsLlmProviderFile
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.ensureGeneratedBootstrapFile
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.ensureGeneratedMockLlmProviderFile
import com.jetbrains.kotlinllm.kotlinllmplugin.services.DEFAULT_ANTHROPIC_MODEL
import com.jetbrains.kotlinllm.kotlinllmplugin.services.DEFAULT_OLLAMA_BASE_URL
import com.jetbrains.kotlinllm.kotlinllmplugin.services.DEFAULT_OLLAMA_MODEL
import com.jetbrains.kotlinllm.kotlinllmplugin.services.KotlinLlmProjectConfig
import com.jetbrains.kotlinllm.kotlinllmplugin.services.KotlinLlmProvider
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmCoroutineScope
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmRunInProgress
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmStatsService
import com.jetbrains.kotlinllm.kotlinllmplugin.services.readKotlinLlmProjectConfig
import com.jetbrains.kotlinllm.kotlinllmplugin.services.resolveConfiguredKotlinLlmSourceRoot
import com.jetbrains.kotlinllm.kotlinllmplugin.services.saveKotlinLlmProjectConfig
import com.jetbrains.kotlinllm.kotlinllmplugin.services.toProjectRelativeKotlinLlmPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextField

class KotlinLlmSettingsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        KotlinLlmSettingsDialog(project, triggeredByRun = false).showAndGet()
    }

    override fun update(event: AnActionEvent) {
        val enabled = event.project != null
        event.presentation.isVisible = enabled
        event.presentation.isEnabled = enabled
    }
}

fun promptForKotlinLlmInitialization(project: Project, triggeredByRun: Boolean): VirtualFile? {
    val dialog = KotlinLlmSettingsDialog(project, triggeredByRun)
    return if (dialog.showAndGet()) dialog.selectedFolder else null
}

suspend fun promptForKotlinLlmInitializationLater(project: Project, triggeredByRun: Boolean): VirtualFile? {
    val result = CompletableDeferred<VirtualFile?>()
    ApplicationManager.getApplication().invokeLater({
        if (project.isDisposed) {
            result.complete(null)
            return@invokeLater
        }

        try {
            result.complete(promptForKotlinLlmInitialization(project, triggeredByRun))
        } catch (t: Throwable) {
            result.completeExceptionally(t)
        }
    }, ModalityState.nonModal())
    return result.await()
}

suspend fun createKotlinLlmBootstrapFiles(project: Project) {
    ensureGeneratedAsLlmProviderFile(project)
    ensureGeneratedMockLlmProviderFile(project)
    ensureGeneratedBootstrapFile(project)
}

fun showKotlinLlmInitializationCompleted(project: Project, selectedFolder: VirtualFile) {
    Messages.showInfoMessage(
        project,
        """
        KotlinLLM generated files are initialized for this project.

        Generated source root:
        ${toProjectRelativeKotlinLlmPath(project, Path.of(selectedFolder.path))}/com/jetbrains/kotlinllm/generated

        Runtime files:
        core

        Configuration:
        .kotlinllm stores the KotlinLLM settings for this project.
        """.trimIndent(),
        "KotlinLLM Settings"
    )
}

private fun showKotlinLlmInitializationCompletedLater(project: Project, selectedFolder: VirtualFile) {
    ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) {
            showKotlinLlmInitializationCompleted(project, selectedFolder)
        }
    }, ModalityState.nonModal())
}

private class KotlinLlmSettingsDialog(
    private val project: Project,
    private val triggeredByRun: Boolean
) : DialogWrapper(project) {
    private val initialConfig = readKotlinLlmProjectConfig(project)
    private var currentFolder: VirtualFile? = resolveConfiguredKotlinLlmSourceRoot(project)
    private var savedFolder: VirtualFile? = currentFolder

    private val folderField = JTextField(
        currentFolder?.let { toProjectRelativeKotlinLlmPath(project, Path.of(it.path)) }
            ?: displayProjectRelativePath(initialConfig.generatedFolder)
    ).apply {
        isEditable = false
    }
    private val llmProviderBox = ComboBox(KotlinLlmProvider.entries.toTypedArray()).apply {
        selectedItem = initialConfig.llmProvider
    }
    private val apiKeyField = JPasswordField(initialConfig.apiKey)
    private val buildsFolderField = JTextField(displayProjectRelativePath(initialConfig.buildsFolder))
    private val ollamaBaseUrlField = JTextField(initialConfig.ollamaBaseUrl)
    private val ollamaModelField = JTextField(initialConfig.ollamaModel)
    private val anthropicModelField = JTextField(initialConfig.anthropicModel)
    private val advancedPanel = JPanel(GridBagLayout()).apply {
        isVisible = false
    }
    private val advancedToggleButton = JButton("Show Advanced")

    val selectedFolder: VirtualFile?
        get() = savedFolder

    init {
        title = if (triggeredByRun) "KotlinLLM Settings Before Running" else "KotlinLLM Settings"
        setOKButtonText(if (triggeredByRun) "Save and Continue" else "Save")
        init()
    }

    override fun createCenterPanel(): JComponent {
        return JPanel(BorderLayout(0, 14)).apply {
            preferredSize = Dimension(680, 380)
            add(createIntroPane(), BorderLayout.NORTH)
            add(createSettingsPanel(), BorderLayout.CENTER)
        }
    }

    override fun doOKAction() {
        if (!saveSettingsFromFields(requireFolder = triggeredByRun)) return
        super.doOKAction()
    }

    private fun createIntroPane(): JComponent {
        val runIntro = if (triggeredByRun) {
            "<p><b>This project needs KotlinLLM settings before the run can start.</b></p>"
        } else {
            "<p><b>Configure KotlinLLM for this project.</b></p>"
        }
        return JEditorPane("text/html", """
            <html>
            <body style="font-family: sans-serif; font-size: 12px;">
            $runIntro
            <p>Settings are stored in <code>.kotlinllm</code> at the project root.</p>
            <p><b>Warning:</b> the API key or Grazie JWT is stored as plaintext in <code>.kotlinllm</code>. Do not commit this file.</p>
            </body>
            </html>
        """.trimIndent()).apply {
            isEditable = false
            isFocusable = false
            isOpaque = false
            border = null
        }
    }

    private fun createSettingsPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(0, 0, 6, 8)
        }

        addLabel(panel, constraints, "LLM provider:")
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        panel.add(llmProviderBox, constraints)

        nextRow(constraints)
        addLabel(panel, constraints, "API key / Grazie JWT:")
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        panel.add(apiKeyField, constraints)

        nextRow(constraints)
        constraints.gridx = 1
        constraints.gridwidth = 2
        panel.add(JLabel("Stored as plaintext in .kotlinllm. Grazie may also read GRAZIE_JWT_TOKEN."), constraints)

        nextRow(constraints)
        constraints.gridwidth = 1
        addLabel(panel, constraints, "Ollama base URL:")
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        panel.add(ollamaBaseUrlField, constraints)

        nextRow(constraints)
        constraints.gridwidth = 1
        addLabel(panel, constraints, "Ollama model:")
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        panel.add(ollamaModelField, constraints)

        nextRow(constraints)
        constraints.gridwidth = 1
        addLabel(panel, constraints, "Anthropic model:")
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        panel.add(anthropicModelField, constraints)

        nextRow(constraints)
        constraints.gridwidth = 1
        addLabel(panel, constraints, "Generated source root:")
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        panel.add(folderField, constraints)
        constraints.gridx = 2
        constraints.weightx = 0.0
        constraints.fill = GridBagConstraints.NONE
        panel.add(JButton("Browse...").apply {
            addActionListener {
                chooseSourceFolder()?.let { selected ->
                    currentFolder = selected
                    folderField.text = toProjectRelativeKotlinLlmPath(project, Path.of(selected.path))
                }
            }
        }, constraints)

        nextRow(constraints)
        constraints.gridx = 1
        constraints.gridwidth = 2
        panel.add(JLabel("KotlinLLM creates com/jetbrains/kotlinllm/generated below this root."), constraints)

        nextRow(constraints)
        constraints.gridwidth = 1
        addLabel(panel, constraints, "Builds folder:")
        constraints.gridx = 1
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        panel.add(buildsFolderField, constraints)
        constraints.gridx = 2
        constraints.weightx = 0.0
        constraints.fill = GridBagConstraints.NONE
        panel.add(JButton("Browse...").apply {
            addActionListener {
                chooseBuildsFolder()?.let { selected ->
                    buildsFolderField.text = toProjectRelativeKotlinLlmPath(project, Path.of(selected.path))
                }
            }
        }, constraints)

        nextRow(constraints)
        constraints.gridx = 1
        constraints.gridwidth = 2
        panel.add(JLabel("Used to locate compiled class files for hot reload."), constraints)

        nextRow(constraints)
        constraints.gridx = 0
        constraints.gridwidth = 3
        advancedToggleButton.addActionListener {
            advancedPanel.isVisible = !advancedPanel.isVisible
            advancedToggleButton.text = if (advancedPanel.isVisible) "Hide Advanced" else "Show Advanced"
            advancedPanel.revalidate()
            advancedPanel.repaint()
        }
        panel.add(advancedToggleButton, constraints)

        nextRow(constraints)
        constraints.gridx = 0
        constraints.gridwidth = 3
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        panel.add(createAdvancedPanel(), constraints)

        return panel
    }

    private fun createAdvancedPanel(): JComponent {
        val constraints = GridBagConstraints().apply {
            gridx = 0
            gridy = 0
            anchor = GridBagConstraints.WEST
            insets = JBUI.insets(4, 0, 4, 8)
        }

        advancedPanel.add(JButton("Initialize Files").apply {
            addActionListener { initializeGeneratedFiles() }
        }, constraints)

        constraints.gridx = 1
        advancedPanel.add(JButton("Export Logs").apply {
            addActionListener { exportLogs() }
        }, constraints)

        constraints.gridx = 2
        advancedPanel.add(JButton("Clear Logs").apply {
            addActionListener { clearLogs() }
        }, constraints)

        constraints.gridx = 0
        constraints.gridy = 1
        constraints.gridwidth = 3
        constraints.weightx = 1.0
        constraints.fill = GridBagConstraints.HORIZONTAL
        advancedPanel.add(
            JLabel("Advanced actions run immediately and use the settings currently shown above."),
            constraints
        )

        return advancedPanel
    }

    private fun initializeGeneratedFiles() {
        if (!saveSettingsFromFields(requireFolder = true)) return
        val selectedFolder = savedFolder ?: return
        project.kotlinLlmCoroutineScope.launch {
            try {
                createKotlinLlmBootstrapFiles(project)
                showKotlinLlmInitializationCompletedLater(project, selectedFolder)
            } catch (error: Throwable) {
                showErrorLater(project, "Failed to initialize KotlinLLM files: ${error.message ?: error::class.simpleName.orEmpty()}")
            }
        }
    }

    private fun exportLogs() {
        val descriptor = FileSaverDescriptor(
            "Export KotlinLLM Logs",
            "Choose where to save KotlinLLM logs.",
            "json",
        )
        val defaultFileName = "kotlinllm-logs-${LocalDateTime.now().format(FILE_TIMESTAMP)}.json"
        val selectedFile = FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
            .save(project.basePath?.let(Path::of), defaultFileName)
            ?.file
            ?: return

        val exportPath = selectedFile.toPath().withJsonExtension()
        val json = project.kotlinLlmStatsService.exportAsJson()
        project.kotlinLlmCoroutineScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    exportPath.parent?.let(Files::createDirectories)
                    Files.writeString(exportPath, json)
                }
                showInfoLater(project, "KotlinLLM logs exported to:\n$exportPath")
            } catch (error: Throwable) {
                showErrorLater(project, "Failed to export KotlinLLM logs: ${error.message ?: error::class.simpleName.orEmpty()}")
            }
        }
    }

    private fun clearLogs() {
        if (project.kotlinLlmRunInProgress.get()) {
            Messages.showErrorDialog(
                project,
                "Stop the active KotlinLLM run before clearing logs.",
                "KotlinLLM Settings",
            )
            return
        }

        val confirmed = Messages.showYesNoDialog(
            project,
            "Clear all persisted KotlinLLM logs for this project?",
            "KotlinLLM Settings",
            Messages.getWarningIcon(),
        ) == Messages.YES
        if (!confirmed) return

        project.kotlinLlmStatsService.clear()
        Messages.showInfoMessage(project, "KotlinLLM logs were cleared.", "KotlinLLM Settings")
    }

    private fun saveSettingsFromFields(requireFolder: Boolean): Boolean {
        if (requireFolder && currentFolder == null) {
            Messages.showErrorDialog(project, "Choose a Kotlin source root before continuing.", "KotlinLLM Settings")
            return false
        }

        val generatedRaw = currentFolder?.path ?: folderField.text
        val generatedFolder = normalizeProjectRelativePath(generatedRaw, "generated source root")
            ?: return false
        val buildsFolder = normalizeProjectRelativePath(buildsFolderField.text, "builds folder", allowBlank = false)
            ?: return false
        val config = KotlinLlmProjectConfig(
            generatedFolder = generatedFolder,
            apiKey = String(apiKeyField.password).trim(),
            llmProvider = llmProviderBox.selectedItem as? KotlinLlmProvider ?: KotlinLlmProvider.OpenAI,
            buildsFolder = buildsFolder,
            ollamaBaseUrl = ollamaBaseUrlField.text.trim().ifBlank { DEFAULT_OLLAMA_BASE_URL },
            ollamaModel = ollamaModelField.text.trim().ifBlank { DEFAULT_OLLAMA_MODEL },
            anthropicModel = anthropicModelField.text.trim().ifBlank { DEFAULT_ANTHROPIC_MODEL },
        )
        saveKotlinLlmProjectConfig(project, config)
        folderField.text = generatedFolder
        buildsFolderField.text = buildsFolder
        savedFolder = currentFolder
        return true
    }

    private fun chooseSourceFolder(): VirtualFile? {
        return FileChooser.chooseFile(kotlinLlmSourceFolderDescriptor(), project, currentFolder)
    }

    private fun chooseBuildsFolder(): VirtualFile? {
        return FileChooser.chooseFile(kotlinLlmBuildsFolderDescriptor(), project, currentBuildsFolder())
    }

    private fun currentBuildsFolder(): VirtualFile? {
        val raw = buildsFolderField.text.trim()
        if (raw.isBlank()) return null
        val projectBasePath = project.basePath
        val path = Path.of(raw).let { path ->
            if (path.isAbsolute || projectBasePath == null) path else Path.of(projectBasePath, raw)
        }
        return LocalFileSystem.getInstance().findFileByNioFile(path)
    }

    private fun normalizeProjectRelativePath(rawPath: String, label: String, allowBlank: Boolean = true): String? {
        val trimmed = rawPath.trim()
        if (trimmed.isBlank()) {
            if (!allowBlank) {
                Messages.showErrorDialog(project, "Specify a $label before continuing.", "KotlinLLM Settings")
                return null
            }
            return ""
        }
        val projectBasePath = project.basePath
        if (projectBasePath == null) {
            Messages.showErrorDialog(project, "Cannot save $label without a project base path.", "KotlinLLM Settings")
            return null
        }

        val projectRoot = Path.of(projectBasePath).normalize()
        val raw = Path.of(trimmed)
        val resolved = if (raw.isAbsolute) raw.normalize() else projectRoot.resolve(raw).normalize()
        if (!resolved.startsWith(projectRoot)) {
            Messages.showErrorDialog(project, "Choose a $label inside the project so it can be stored as a relative path.", "KotlinLLM Settings")
            return null
        }
        return projectRoot.relativize(resolved).toString()
    }

    private fun displayProjectRelativePath(rawPath: String): String {
        val trimmed = rawPath.trim()
        if (trimmed.isBlank()) return ""
        val projectBasePath = project.basePath ?: return trimmed
        val projectRoot = Path.of(projectBasePath).normalize()
        val raw = Path.of(trimmed)
        val resolved = if (raw.isAbsolute) raw.normalize() else projectRoot.resolve(raw).normalize()
        return if (resolved.startsWith(projectRoot)) {
            projectRoot.relativize(resolved).toString()
        } else {
            trimmed
        }
    }

    private fun addLabel(panel: JPanel, constraints: GridBagConstraints, text: String) {
        constraints.gridx = 0
        constraints.weightx = 0.0
        constraints.fill = GridBagConstraints.NONE
        panel.add(JLabel(text), constraints)
    }

    private fun nextRow(constraints: GridBagConstraints) {
        constraints.gridx = 0
        constraints.gridy += 1
        constraints.gridwidth = 1
        constraints.weightx = 0.0
        constraints.fill = GridBagConstraints.NONE
        constraints.insets = JBUI.insets(0, 0, 6, 8)
    }

    private fun Path.withJsonExtension(): Path {
        val fileName = fileName?.toString().orEmpty()
        if (fileName.endsWith(".json", ignoreCase = true)) return this
        return resolveSibling("$fileName.json")
    }

    private companion object {
        val FILE_TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

private fun kotlinLlmSourceFolderDescriptor(): FileChooserDescriptor {
    return FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
        title = "Select Kotlin Source Root for KotlinLLM Generated Files"
        description = "Choose a Kotlin source root. KotlinLLM will create com/jetbrains/kotlinllm/generated under it."
    }
}

private fun kotlinLlmBuildsFolderDescriptor(): FileChooserDescriptor {
    return FileChooserDescriptorFactory.createSingleFolderDescriptor().apply {
        title = "Select KotlinLLM Builds Folder"
        description = "Choose the folder KotlinLLM should scan for compiled class files."
    }
}

private fun showInfoLater(project: Project, message: String) {
    ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) Messages.showInfoMessage(project, message, "KotlinLLM Settings")
    }, ModalityState.nonModal())
}

private fun showErrorLater(project: Project, message: String) {
    ApplicationManager.getApplication().invokeLater({
        if (!project.isDisposed) Messages.showErrorDialog(project, message, "KotlinLLM Settings")
    }, ModalityState.nonModal())
}
