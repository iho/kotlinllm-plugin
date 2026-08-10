package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmRunInProgress
import java.nio.file.Path
import java.time.Instant

/**
 * "Regenerate everything" reset action. Wipes all materialized scenarios and
 * re-runs the snapshot orchestration from a clean slate, so a stale/broken
 * generated tree (empty tests, deleted @MutationTarget, wrong exclude patterns)
 * is rebuilt from scratch instead of accumulating broken state.
 */
class RegenerateEverythingAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val project = event.project
        event.presentation.isEnabled =
            project != null && !project.snapshotOrchestratorService.isRunning()
        event.presentation.isVisible = project != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        if (project.kotlinLlmRunInProgress.get()) {
            Messages.showWarningDialog(
                project,
                "Stop the active KotlinLLM run before regenerating.",
                "KotlinLLM Regenerate Everything",
            )
            return
        }
        if (project.snapshotOrchestratorService.isRunning()) {
            Messages.showWarningDialog(
                project,
                "Snapshot orchestration is already running for this project.",
                "KotlinLLM Regenerate Everything",
            )
            return
        }

        val confirm = Messages.showYesNoDialog(
            project,
            "This will delete all materialized snapshots and regenerate them from scratch. Continue?",
            "KotlinLLM Regenerate Everything",
            Messages.getQuestionIcon(),
        )
        if (confirm != Messages.YES) return

        val statusSink: (String) -> Unit = { message ->
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    println("KotlinLLM Snapshot: $message")
                }
            }, ModalityState.any())
        }

        // 1. Wipe all materialized scenarios.
        val cleared = SnapshotIo.clearAllScenarios(project)
        statusSink("Cleared $cleared materialized scenario(s).")

        // 2. Reset the live capture so the next run starts fresh.
        project.snapshotCaptureService.reset()

        // 3. Build a fresh empty snapshot and launch orchestration.
        val scenarioId = buildScenarioId(project)
        val snapshot = ScenarioSnapshot(
            state = SnapshotState(
                scenarioId = scenarioId,
                capturedAt = Instant.now().toString(),
            )
        )
        SnapshotIo.write(project, snapshot)

        val targetProjectDir = project.basePath?.let { Path.of(it) }
        statusSink("Regenerating everything for '$scenarioId'...")
        val started = project.snapshotOrchestratorService.launch(
            project = project,
            scenario = snapshot,
            targetProjectDir = targetProjectDir,
            statusSink = statusSink,
        )
        if (!started) {
            Messages.showErrorDialog(
                project,
                "Could not start regeneration.",
                "KotlinLLM Regenerate Everything",
            )
        }
    }

    private fun buildScenarioId(project: Project): String {
        val base = project.name
            .map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("")
        return "project_$base"
    }
}
