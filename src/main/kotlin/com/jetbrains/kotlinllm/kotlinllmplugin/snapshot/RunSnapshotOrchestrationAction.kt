package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmRunInProgress
import com.intellij.openapi.ui.Messages
import java.nio.file.Path
import java.time.Instant

/**
 * Toolbar/action entry point for the multi-agent snapshot convention.
 *
 * Captures the current project state into a [ScenarioSnapshot], then launches the
 * [SnapshotOrchestratorService] which runs the five role agents (SpecAuthor,
 * SpecReviewer, Snapshotter, TestGenerator, MutationAuditor) against it and drives
 * mutation coverage via mutflow.
 */
class RunSnapshotOrchestrationAction : AnAction() {

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
                "Stop the active KotlinLLM run before starting snapshot orchestration.",
                "KotlinLLM Snapshot Orchestration",
            )
            return
        }
        if (project.snapshotOrchestratorService.isRunning()) {
            Messages.showWarningDialog(
                project,
                "Snapshot orchestration is already running for this project.",
                "KotlinLLM Snapshot Orchestration",
            )
            return
        }

        val statusSink: (String) -> Unit = { message ->
            ApplicationManager.getApplication().invokeLater({
                if (!project.isDisposed) {
                    // Route to a notifications/console sink here if one is wired.
                    println("KotlinLLM Snapshot: $message")
                }
            }, ModalityState.any())
        }

        val scenarioId = buildScenarioId(project)
        // Seed the snapshot from LIVE runtime invocations captured by the last KotlinLLM
        // run (the breakpoint loop records every intercepted asLlm/mockLlm call). If none
        // were captured yet, fall back to the previously-persisted snapshot, then to a
        // fresh empty one.
        val capture = project.snapshotCaptureService
        val liveState = if (!capture.isEmpty()) capture.toSnapshotState(scenarioId) else null
        val snapshot = liveState?.let { s ->
            // Keep any prior agent artifacts (spec/review/tests) so a re-run preserves them;
            // only the state is refreshed from the live capture.
            (SnapshotIo.read(project, scenarioId) ?: ScenarioSnapshot(state = s)).copy(state = s)
        } ?: SnapshotIo.read(project, scenarioId) ?: ScenarioSnapshot(
            state = SnapshotState(
                scenarioId = scenarioId,
                capturedAt = Instant.now().toString(),
            )
        )
        if (liveState != null) {
            statusSink(
                "Captured ${capture.size()} live invocation(s) from the last KotlinLLM run; seeding snapshot state."
            )
        } else {
            statusSink(
                "No live invocations captured yet — run asLlm/mockLlm once first so the snapshot has real state."
            )
        }
        // Materialize the state file immediately so the convention is visible.
        SnapshotIo.write(project, snapshot)

        val targetProjectDir = project.basePath?.let { Path.of(it) }

        statusSink("Launching snapshot orchestration for '$scenarioId'...")
        val started = project.snapshotOrchestratorService.launch(
            project = project,
            scenario = snapshot,
            targetProjectDir = targetProjectDir,
            statusSink = statusSink,
        )
        if (!started) {
            Messages.showErrorDialog(
                project,
                "Could not start snapshot orchestration.",
                "KotlinLLM Snapshot Orchestration",
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
