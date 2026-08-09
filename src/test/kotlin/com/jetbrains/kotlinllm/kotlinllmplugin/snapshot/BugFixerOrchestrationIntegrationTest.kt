package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.llm.KoogLlmClient
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Live integration test: runs the multi-agent snapshot orchestrator (including the
 * BugFixer role) against the buggy-sample project, using a real local Ollama model
 * (deepseek-v4-flash:cloud at 127.0.0.1:11434). Verifies the BugFixer actually edits
 * the buggy source file.
 *
 * Self-skips when Ollama is not reachable. The buggy source is copied to a temp file
 * so the checked-in sample stays buggy for repeat runs.
 */
class BugFixerOrchestrationIntegrationTest : BasePlatformTestCase() {

    fun testBugFixerEditsBuggySource() {
        if (!isOllamaReachable("127.0.0.1", 11434)) {
            println("SKIP: Ollama not reachable at 127.0.0.1:11434 - skipping live integration test")
            return
        }

        // Copy the buggy source to a temp file so the sample stays buggy for repeat runs.
        val sampleSource = Path.of(
            "examples/buggy-sample/src/main/kotlin/sample/buggy/BuggyCalculator.kt"
        ).toAbsolutePath()
        assertTrue("sample source missing: $sampleSource", Files.exists(sampleSource))
        val original = Files.readString(sampleSource)

        val tempDir = Files.createTempDirectory("bugfixer-test")
        val buggyCopy = tempDir.resolve("BuggyCalculator.kt")
        Files.writeString(buggyCopy, original)

        val snapshot = ScenarioSnapshot(
            state = SnapshotState(
                scenarioId = "sample.buggy.BuggyCalculator",
                state = mapOf("fixed" to "false"),
                observedCalls = listOf(
                    ObservedCall("isPositive", mapOf("x" to "0"), "kotlin.Boolean", "true"),
                    ObservedCall("subtract", mapOf("a" to "10", "b" to "3"), "kotlin.Int", "13"),
                ),
                capturedAt = Instant.now().toString(),
            ),
        )

        val statuses = mutableListOf<String>()
        val llmClient = KoogLlmClient(
            project = project,
            statusSink = { statuses.add(it) },
        ).selectOllama(baseUrl = "http://127.0.0.1:11434", model = "deepseek-v4-flash:cloud")

        val orchestrator = SnapshotOrchestrator(
            project,
            statusSink = { statuses.add(it) },
            sourceFilePath = buggyCopy,
        )
        kotlinx.coroutines.runBlocking {
            orchestrator.orchestrateScenario(
                snapshot = snapshot,
                llmClient = llmClient,
                targetProjectDir = null,
            )
        }

        // The BugFixer role should have run.
        assertTrue("BugFixer should have started", statuses.any { it.contains("BugFixer") })

        // The buggy source should have been edited (content differs from the original).
        val edited = Files.readString(buggyCopy)
        assertTrue(
            "BugFixer should have modified the source (original vs edited differ)",
            edited != original,
        )
        println("BugFixer edited source: ${edited.length} chars (original ${original.length})")
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
