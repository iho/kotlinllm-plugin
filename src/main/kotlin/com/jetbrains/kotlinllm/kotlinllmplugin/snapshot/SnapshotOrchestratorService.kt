package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.KoogLlmClient
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmCoroutineScope
import com.jetbrains.kotlinllm.kotlinllmplugin.services.readKotlinLlmProjectConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Project-scoped holder for snapshot-orchestration runs. Serializes launches so the
 * orchestrator does not overlap with a running KotlinLLM session, and exposes the
 * coroutine scope used to run the multi-agent loop off the EDT.
 */
@Service(Service.Level.PROJECT)
internal class SnapshotOrchestratorService : Disposable {

    @Volatile
    var activeJob: Job? = null
        private set

    fun isRunning(): Boolean = activeJob?.isActive == true

    /**
     * Kick off the multi-agent snapshot loop for the given scenario.
     *
     * @param scenario the in-memory snapshot to drive (state already captured).
     * @param targetProjectDir target project root for mutflow emission/invocation (null to skip).
     * @param statusSink UI/log sink for progress messages.
     * @return true if a run was started, false if one is already active.
     */
    fun launch(
        project: Project,
        scenario: ScenarioSnapshot,
        targetProjectDir: java.nio.file.Path?,
        statusSink: (String) -> Unit,
    ): Boolean {
        if (isRunning()) return false

        activeJob = project.kotlinLlmCoroutineScope.launch {
            try {
                val config = readKotlinLlmProjectConfig(project)
                val llmClient = KoogLlmClient(project = project, statusSink = statusSink)
                val orchestrator = SnapshotOrchestrator(
                    project,
                    statusSink,
                    agentModels = config.agentModels,
                    customAgents = config.customAgents,
                )
                orchestrator.orchestrateScenario(scenario, llmClient, targetProjectDir)
            } finally {
                activeJob = null
            }
        }
        return true
    }

    override fun dispose() {
        activeJob?.cancel()
        activeJob = null
    }
}

internal val Project.snapshotOrchestratorService: SnapshotOrchestratorService
    get() = service()
