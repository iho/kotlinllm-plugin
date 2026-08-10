package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.agents.SnapshotAgentTools
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.junit.Assert.assertTrue
import java.nio.file.Files
import java.nio.file.Path

/**
 * Verifies the discoverMutationTargets tool: the TestGenerator agent can find the
 * project's real @MutationTarget classes so it writes tests that exercise them
 * (instead of vacuous placeholders that yield 0 mutations).
 */
class DiscoverMutationTargetsTest : BasePlatformTestCase() {

    fun testDiscoversMutationTargetInSourceRoot() {
        val base = Path.of(project.basePath!!)
        val srcDir = base.resolve("src/main/kotlin/generated/snapshot")
        Files.createDirectories(srcDir)
        val targetFile = srcDir.resolve("GreeterImpl.kt")
        Files.writeString(
            targetFile,
            """
            package generated.snapshot

            import io.github.anschnapp.mutflow.MutationTarget

            @MutationTarget
            class GreeterImpl {
                fun greet(name: String): String = "Hello, $name!"
                fun isPositive(x: Int): Boolean = x >= 0
            }
            """.trimIndent()
        )

        val snapshot = ScenarioSnapshot(
            state = SnapshotState(scenarioId = "project_test", capturedAt = "now")
        )
        val tools = SnapshotAgentTools(project, snapshot)
        val result = tools.discoverMutationTargets()

        assertTrue("should find GreeterImpl", result.contains("GreeterImpl"))
        assertTrue("should include package", result.contains("generated.snapshot"))
        assertTrue("should include source", result.contains("isPositive"))
    }

    fun testNoTargetsReturnsMessage() {
        // Remove any @MutationTarget file the other test created in this shared project.
        val base = Path.of(project.basePath!!)
        val srcDir = base.resolve("src/main/kotlin/generated/snapshot")
        val targetFile = srcDir.resolve("GreeterImpl.kt")
        if (Files.exists(targetFile)) Files.delete(targetFile)

        val snapshot = ScenarioSnapshot(
            state = SnapshotState(scenarioId = "project_empty", capturedAt = "now")
        )
        val tools = SnapshotAgentTools(project, snapshot)
        val result = tools.discoverMutationTargets()
        assertTrue("should report no targets", result.contains("no @MutationTarget"))
    }
}
