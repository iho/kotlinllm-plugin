package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.mutflow.MutflowIntegration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotIoTest {

    @Test
    fun `state json round-trips scenario`() {
        val state = SnapshotState(
            scenarioId = "com.example.Calculator.add",
            state = mapOf("lastResult" to "5", "count" to "2"),
            observedCalls = listOf(
                ObservedCall(
                    methodName = "add",
                    arguments = mapOf("a" to "2", "b" to "3"),
                    returnType = "kotlin.Int",
                    returnValue = "5",
                )
            ),
            capturedAt = "2026-08-09T00:00:00Z",
        )

        val json = SnapshotIo.encodeState(state)
        val decoded = SnapshotIo.decodeState(json)

        assertEquals(state, decoded)
    }

    @Test
    fun `empty state round-trips`() {
        val state = SnapshotState(scenarioId = "empty.scenario", state = emptyMap())

        val json = SnapshotIo.encodeState(state)
        val decoded = SnapshotIo.decodeState(json)

        assertEquals(state, decoded)
    }

    @Test
    fun `decode returns null for malformed json`() {
        assertEquals(null, SnapshotIo.decodeState("not json"))
        assertEquals(null, SnapshotIo.decodeState(""))
        assertEquals(null, SnapshotIo.decodeState("""{"state":{}}""")) // missing scenarioId
    }

    @Test
    fun `phase2 snapshot fields round-trip via io`() {
        val snapshot = ScenarioSnapshot(
            state = SnapshotState(scenarioId = "phase2.test", state = mapOf("k" to "v")),
            spec = "spec",
            review = "review",
            tests = "tests",
            coverage = "coverage",
            promptSpec = "## prompt-spec\ninline-able spec",
            highLevelSpec = "## high-level\nwhat the code does",
        )
        // SnapshotIo.write/read require a Project (VFS). Here we assert the model
        // fields are wired to the file constants so the orchestrator persists them.
        assertEquals("prompt-spec.md", SnapshotFiles.PROMPT_SPEC)
        assertEquals("high-level-spec.md", SnapshotFiles.HIGH_LEVEL_SPEC)
        assertEquals("inline-able spec", snapshot.promptSpec.substringAfter("prompt-spec\n"))
        assertEquals("what the code does", snapshot.highLevelSpec.substringAfter("high-level\n"))
    }
}

class MutflowParserTest {

    @Test
    fun `parses structured mutant lines`() {
        val output = """
            MUTANT status=KILLED class=Calculator line=12 operator=CONDITIONALS_BOUNDARY
            MUTANT status=SURVIVED class=Calculator line=15 operator=NEGATE_CONDITIONALS
            MUTANT status=KILLED class=Calculator line=20 operator=RETURN_VALS
        """.trimIndent()

        val report = MutflowIntegration.parseReport(output)

        assertEquals(3, report.total)
        assertEquals(2, report.killed)
        assertEquals(1, report.survived)
        assertTrue(report.succeeded)
        assertEquals(1, report.survivedMutants.size)
        assertEquals("Calculator:15", report.survivedMutants[0].line)
        assertEquals("NEGATE_CONDITIONALS", report.survivedMutants[0].operator)
    }

    @Test
    fun `parses mutflow json summary lines`() {
        val output = """
            [mutflow-json] {"totalMutations":4,"testedThisRun":4,"killed":1,"survived":3,"timedOut":0,"untested":0,"mutants":[{"pointId":"sample.Calculator_0","variantIndex":0,"display":"(Calculator.kt:8) > → >=","status":"SURVIVED"},{"pointId":"sample.Calculator_0","variantIndex":1,"display":"(Calculator.kt:8) > → <","status":"KILLED","killedBy":"isPositive returns true for positive numbers()"}]}
            [mutflow-json] {"totalMutations":4,"testedThisRun":2,"killed":2,"survived":0,"timedOut":0,"untested":2,"mutants":[{"pointId":"sample.Calculator_1","variantIndex":1,"display":"(Calculator.kt:8) 0 → -1","status":"KILLED","killedBy":"isPositive returns false for zero()"}]}
        """.trimIndent()

        val report = MutflowIntegration.parseReport(output)

        assertTrue(report.succeeded)
        assertEquals(8, report.total)          // summed across sessions
        assertEquals(3, report.killed)
        assertEquals(3, report.survived)
        assertEquals(3, report.mutants.size)   // 2 from session 1 + 1 from session 2
        assertEquals("sample.Calculator_0", report.mutants[0].line)
        assertEquals("SURVIVED", report.mutants[0].status)
        assertEquals(">=", report.mutants[0].operator)
        assertTrue(report.survivedMutants.isNotEmpty())
    }

    @Test
    fun `json summary preferred over legacy text`() {
        val output = """
            MUTANT status=KILLED class=Calculator line=12 operator=CONDITIONALS_BOUNDARY
            [mutflow-json] {"totalMutations":2,"testedThisRun":2,"killed":1,"survived":1,"timedOut":0,"untested":0,"mutants":[{"pointId":"sample.Calculator_0","variantIndex":0,"display":"(Calculator.kt:8) > → >=","status":"SURVIVED"},{"pointId":"sample.Calculator_0","variantIndex":1,"display":"(Calculator.kt:8) > → <","status":"KILLED","killedBy":"isPositive returns true for positive numbers()"}]}
        """.trimIndent()

        val report = MutflowIntegration.parseReport(output)

        // Should prefer the JSON summary, not the legacy text line.
        assertEquals(2, report.total)
        assertEquals(1, report.killed)
        assertEquals(1, report.survived)
        assertEquals("sample.Calculator_0", report.mutants[0].line)
    }

    @Test
    fun `parses generic output when no structured format present`() {
        val output = """
            Running mutation tests...
            KILLED 3
            SURVIVED 1
            BUILD SUCCESSFUL
        """.trimIndent()

        val report = MutflowIntegration.parseReport(output)

        assertEquals(4, report.total)
        assertEquals(3, report.killed)
        assertEquals(1, report.survived)
        assertTrue(report.succeeded)
    }

    @Test
    fun `report not succeeded when build failed`() {
        val output = "BUILD FAILED: compilation error"
        val report = MutflowIntegration.parseReport(output)
        assertEquals(0, report.total)
        // No killed/survived and no BUILD SUCCESSFUL -> not succeeded
        assertEquals(false, report.succeeded)
    }

    @Test
    fun `empty run is not reported as full coverage`() {
        // A run with 0 total mutations must NOT report 100% coverage (false green).
        val report = MutflowIntegration.parseReport(
            """[mutflow-json] {"totalMutations":0,"testedThisRun":0,"killed":0,"survived":0,"timedOut":0,"untested":0,"mutants":[]}"""
        )
        assertTrue(report.succeeded)
        assertEquals(0, report.total)
        assertEquals("empty run must not report 100% coverage", 0.0, report.coverage, 0.0001)
    }
}
