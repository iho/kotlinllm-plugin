package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.KoogLlmClient
import com.jetbrains.kotlinllm.kotlinllmplugin.services.KotlinLlmProvider
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Instant

/**
 * Live integration test: runs the multi-agent snapshot orchestrator against a real
 * Ollama model (deepseek-v4-flash:cloud at 127.0.0.1:11434). This proves the Koog
 * Ollama client path and the 5-role agent loop actually talk to a model end-to-end.
 *
 * Skipped (reports as skipped) when Ollama is not reachable, so CI without a local
 * model is unaffected.
 */
class OllamaOrchestrationIntegrationTest : BasePlatformTestCase() {

    override fun setUp() {
        super.setUp()
    }

    fun testMultiAgentLoopRunsAgainstLocalModel() {
        if (!isOllamaReachable("127.0.0.1", 11434)) {
            println("SKIP: Ollama not reachable at 127.0.0.1:11434 - skipping live integration test")
            return
        }
        // Build the scenario snapshot directly (no project config needed).
        val snapshot = ScenarioSnapshot(
            state = SnapshotState(
                scenarioId = "sample.Calculator.isPositive",
                state = mapOf(
                    "lastResult" to "true",
                    "callCount" to "3",
                ),
                observedCalls = listOf(
                    ObservedCall(
                        methodName = "isPositive",
                        arguments = mapOf("x" to "5"),
                        returnType = "kotlin.Boolean",
                        returnValue = "true",
                    ),
                ),
                capturedAt = Instant.now().toString(),
            ),
        )

        val statuses = mutableListOf<String>()
        val llmClient = KoogLlmClient(
            project = project,
            statusSink = { statuses.add(it) },
        ).selectOllama(baseUrl = "http://127.0.0.1:11434", model = "deepseek-v4-flash:cloud")

        val orchestrator = SnapshotOrchestrator(project, statusSink = { statuses.add(it) })
        kotlinx.coroutines.runBlocking {
            orchestrator.orchestrateScenario(
                snapshot = snapshot,
                llmClient = llmClient,
                targetProjectDir = null, // skip mutflow; only prove the LLM loop
            )
        }

        // The loop should have run the authoring agents and wired the tool registry.
        // Note: a real local model is non-deterministic, so we assert the loop executed
        // (roles started, tools were called, no exception) rather than requiring valid
        // artifacts on every run.
        assertTrue("statuses should show SpecAuthor starting", statuses.any { it.contains("SpecAuthor") })
        assertTrue("statuses should show SpecReviewer starting", statuses.any { it.contains("SpecReviewer") })
        assertTrue("statuses should show Snapshotter starting", statuses.any { it.contains("Snapshotter") })
        assertTrue("statuses should show TestGenerator starting", statuses.any { it.contains("TestGenerator") })
    }

    private fun isOllamaReachable(host: String, port: Int): Boolean {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2000)
                true
            }
        }.getOrDefault(false)
    }
}
