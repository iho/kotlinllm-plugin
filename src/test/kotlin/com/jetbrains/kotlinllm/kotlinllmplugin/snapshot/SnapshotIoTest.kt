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
}
