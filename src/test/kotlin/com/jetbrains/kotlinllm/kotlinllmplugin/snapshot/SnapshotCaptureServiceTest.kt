package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualValue
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualType
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedMethod
import com.jetbrains.kotlinllm.kotlinllmplugin.modes.TrackedMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotCaptureServiceTest {

    private fun trackedMethod(name: String, cls: String = "GreeterMock") = TrackedMethod(
        mockedMethod = MockedMethod(
            name = name,
            actualType = ActualType(name = "kotlin.Int"),
            parameters = emptyList(),
            packageName = "com.jetbrains.kotlinllm.generated.mockLlm",
        ),
        returnType = ActualType(name = "kotlin.Int"),
        implementationClassName = cls,
        implementationPackageName = "com.jetbrains.kotlinllm.generated.mockLlm",
        implementationMethodName = name,
    )

    @Test
    fun `recorded invocations seed state and observed calls`() {
        val capture = SnapshotCaptureService()
        capture.recordInvocation(
            trackedMethod("pow"),
            listOf(
                ActualValue("base", "kotlin.Int", "2"),
                ActualValue("exponent", "kotlin.Int", "10"),
            )
        )
        capture.recordInvocation(
            trackedMethod("factorial"),
            listOf(ActualValue("n", "kotlin.Int", "5"))
        )

        assertFalse(capture.isEmpty())
        assertEquals(2, capture.size())

        val state = capture.toSnapshotState("project_ollama_test")
        assertEquals("project_ollama_test", state.scenarioId)
        assertEquals("2", state.state["pow.base"])
        assertEquals("10", state.state["pow.exponent"])
        assertEquals("5", state.state["factorial.n"])
        assertEquals(2, state.observedCalls.size)
        assertEquals("GreeterMock.pow", state.observedCalls[0].methodName)
        assertEquals("kotlin.Int", state.observedCalls[0].returnType)
        assertTrue("capturedAt should be set", state.capturedAt.isNotBlank())
    }

    @Test
    fun `empty capture yields empty state`() {
        val capture = SnapshotCaptureService()
        assertTrue(capture.isEmpty())
        val state = capture.toSnapshotState("empty")
        assertTrue(state.state.isEmpty())
        assertTrue(state.observedCalls.isEmpty())
    }

    @Test
    fun `reset clears captures`() {
        val capture = SnapshotCaptureService()
        capture.recordInvocation(trackedMethod("greet"), listOf(ActualValue("name", "kotlin.String", "World")))
        assertEquals(1, capture.size())
        capture.reset()
        assertTrue(capture.isEmpty())
    }
}
