package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.KoogLlmClient
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmCoroutineScope
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmRunInProgress
import com.jetbrains.kotlinllm.kotlinllmplugin.services.readKotlinLlmProjectConfig
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.time.Instant

/**
 * "Implement Interface with Tests" — the end-to-end flow:
 *
 *   1. Reads the interface the user has open in the editor (or the file they pick).
 *   2. Asks the LLM to write a real `@MutationTarget` implementation class.
 *   3. Writes that implementation to a real source file (next to the interface).
 *   4. Runs the snapshot orchestration (TestGenerator + mutflow) against it, so the
 *      agent writes genuinely useful @MutFlowTest tests and mutflow drives coverage.
 *
 * All progress and errors are surfaced as visible IDE notifications so the action
 * never looks like it "did nothing."
 */
class ImplementInterfaceWithTestsAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)
        // Always enable when there's a project + a .kt file, so the action is never
        // silently greyed out. The running-state check happens in actionPerformed with
        // a visible warning, so the user always gets feedback.
        event.presentation.isEnabled =
            project != null && file != null && file.extension == "kt"
        event.presentation.isVisible = project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        if (project.kotlinLlmRunInProgress.get()) {
            Messages.showWarningDialog(
                project,
                "Stop the active KotlinLLM run before implementing an interface.",
                "KotlinLLM Implement Interface",
            )
            return
        }
        if (project.snapshotOrchestratorService.isRunning()) {
            Messages.showWarningDialog(
                project,
                "Snapshot orchestration is already running for this project.",
                "KotlinLLM Implement Interface",
            )
            return
        }

        val interfaceSource = runCatching { file.contentsToByteArray().toString(Charsets.UTF_8) }
            .getOrElse {
                Messages.showErrorDialog(project, "Could not read ${file.path}", "KotlinLLM Implement Interface")
                return
            }
        if (!interfaceSource.contains("interface ")) {
            Messages.showWarningDialog(
                project,
                "The selected file does not contain an interface declaration.",
                "KotlinLLM Implement Interface",
            )
            return
        }

        val statusSink: (String) -> Unit = { message ->
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    notify(project, message, NotificationType.INFORMATION)
                }
            }, ModalityState.any())
        }

        val targetProjectDir = project.basePath?.let { Path.of(it) }
        val interfaceFile = file

        // Visible start feedback so the user knows the action is running.
        notify(project, "Implementing interface ${interfaceFile.name}...", NotificationType.INFORMATION)

        project.kotlinLlmCoroutineScope.launch {
            try {
                val config = readKotlinLlmProjectConfig(project)
                val llmClient = KoogLlmClient(project = project, statusSink = statusSink)

                // 1. Ask the LLM to implement the interface as a @MutationTarget class.
                statusSink("Asking LLM to implement the interface...")
                val implementation = llmClient.chat(
                    system = buildString {
                        appendLine("You are a Kotlin implementation generator. Given an interface, write a concrete class that implements it.")
                        appendLine("Requirements:")
                        appendLine("- The class MUST be annotated with @io.github.anschnapp.mutflow.MutationTarget")
                        appendLine("- Implement EVERY method of the interface with real, correct logic (not stubs)")
                        appendLine("- Use the same package as the interface")
                        appendLine("- Name the class <InterfaceName>Impl")
                        appendLine("- Output ONLY the Kotlin source, no markdown fences, no explanation")
                    },
                    user = "Implement this interface:\n\n$interfaceSource",
                )

                // 2. Write the implementation to a real source file next to the interface.
                val implSource = stripCodeFences(implementation)
                val implFile = writeImplementation(interfaceFile, implSource)
                if (implFile == null) {
                    statusSink("Failed to write implementation file.")
                    notify(project, "Failed to write implementation file.", NotificationType.ERROR)
                    return@launch
                }
                statusSink("Wrote implementation to ${implFile.path}")
                notify(project, "Wrote implementation to ${implFile.path}", NotificationType.INFORMATION)

                // 3. Build a snapshot seeded with the interface as the target, then run
                //    the orchestration (TestGenerator + mutflow) against it.
                val scenarioId = "project_${project.name}"
                val snapshot = ScenarioSnapshot(
                    state = SnapshotState(
                        scenarioId = scenarioId,
                        capturedAt = Instant.now().toString(),
                    )
                )
                SnapshotIo.write(project, snapshot)
                statusSink("Launching snapshot orchestration to generate tests + run mutflow...")
                project.snapshotOrchestratorService.launch(
                    project = project,
                    scenario = snapshot,
                    targetProjectDir = targetProjectDir,
                    statusSink = statusSink,
                )
            } catch (e: Exception) {
                statusSink("Implement-interface flow failed: ${e.message}")
                notify(project, "Implement-interface flow failed: ${e.message}", NotificationType.ERROR)
            }
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        Notifications.Bus.notify(
            Notification("KotlinLLM", "KotlinLLM Implement Interface", message, type),
            project,
        )
    }

    private fun stripCodeFences(text: String): String = stripCodeFencesForTest(text)

    private fun writeImplementation(interfaceFile: VirtualFile, implSource: String): VirtualFile? {
        val interfaceName = Regex("interface\\s+(\\w+)").find(interfaceFile.name)?.groupValues?.get(1)
            ?: return null
        val implFileName = implFileNameForTest(interfaceFile.name)
        val dir = interfaceFile.parent ?: return null
        val implFile = dir.findChild(implFileName) ?: dir.createChildData(this, implFileName)
        runCatching { implFile.setBinaryContent(implSource.toByteArray(Charsets.UTF_8)) }
            .getOrElse { return null }
        return implFile
    }

    companion object {
        /** Testable: strip markdown code fences from LLM output. */
        internal fun stripCodeFencesForTest(text: String): String {
            val trimmed = text.trim()
            // Remove ```kotlin ... ``` or ``` ... ``` fences if present.
            return trimmed
                .removePrefix("```kotlin")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
        }

        /** Testable: derive the impl filename from the interface filename. */
        internal fun implFileNameForTest(interfaceFileName: String): String {
            val interfaceName = Regex("interface\\s+(\\w+)").find(interfaceFileName)?.groupValues?.get(1)
                ?: interfaceFileName.removeSuffix(".kt")
            return "${interfaceName}Impl.kt"
        }
    }
}
