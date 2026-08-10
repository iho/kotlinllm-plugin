package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.mutflow.MutflowIntegration
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies the emit-bridge: the TestGenerator agent's snapshot.test.kt content is
 * written into the target project's test source set, so a subsequent mutflow run
 * exercises the agent-written tests rather than whatever tests already exist.
 */
class EmitAgentTestsTest : BasePlatformTestCase() {

    fun testEmitsAgentTestsIntoTargetProject() {
        val targetDir = Files.createTempDirectory("mutflow-target")
        val scenarioId = "sample.buggy.BuggyCalculator"
        val agentTests = """
            package generated.snapshot.sample.buggy

            import io.github.anschnapp.mutflow.junit.MutFlowTest
            import io.github.anschnapp.mutflow.MutFlow
            import kotlin.test.Test
            import kotlin.test.assertEquals

            @MutFlowTest
            class BuggyCalculatorAgentTest {
                @Test
                fun `isPositive boundary`() {
                    val r = MutFlow.underTest { sample.buggy.BuggyCalculator.isPositive(0) }
                    assertEquals(false, r)
                }
            }
        """.trimIndent()

        val config = MutflowIntegration.MutflowRunConfig(
            project = project,
            targetProjectDir = targetDir,
            testPackage = "generated.snapshot.sample.buggy",
        )
        val emitted = MutflowIntegration.emitTestFile(config, scenarioId, agentTests)

        assertTrue("test file should be emitted", emitted != null)
        val expectedPath = targetDir.resolve(
            "src/test/kotlin/generated/snapshot/sample/buggy/${scenarioId.sanitized()}.kt"
        )
        assertEquals(expectedPath, emitted)
        assertTrue("file should exist on disk", Files.exists(expectedPath))
        val written = Files.readString(expectedPath)
        assertTrue("should contain the agent's @MutFlowTest class", written.contains("BuggyCalculatorAgentTest"))
        assertTrue("should contain MutFlow.underTest", written.contains("MutFlow.underTest"))
    }

    fun testRecreatesRemovedTestFile() {
        val targetDir = Files.createTempDirectory("mutflow-recreate")
        val scenarioId = "sample.buggy.BuggyCalculator"
        val agentTests = """
            @MutFlowTest
            class RecreatedTest {
                @Test fun x() = assertEquals(1, 1)
            }
        """.trimIndent()
        val config = MutflowIntegration.MutflowRunConfig(
            project = project,
            targetProjectDir = targetDir,
            testPackage = "generated.snapshot",
        )
        val expectedPath = targetDir.resolve("src/test/kotlin/generated/snapshot/${scenarioId.sanitized()}.kt")

        // First emit, then delete the file (simulates the developer removing tests).
        assertTrue(MutflowIntegration.emitTestFile(config, scenarioId, agentTests) != null)
        assertTrue("file should exist after first emit", Files.exists(expectedPath))
        Files.delete(expectedPath)
        assertTrue("file should be gone after manual delete", !Files.exists(expectedPath))

        // Re-emitting must recreate the removed file.
        val reEmitted = MutflowIntegration.emitTestFile(config, scenarioId, agentTests)
        assertTrue("re-emit should recreate the removed test file", reEmitted != null)
        assertTrue("file should exist again after re-emit", Files.exists(expectedPath))
        assertTrue("recreated file should contain the tests", Files.readString(expectedPath).contains("RecreatedTest"))
    }

    private fun String.sanitized(): String =
        map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")
}
