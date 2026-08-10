package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the pure logic of ImplementInterfaceWithTestsAction: stripping markdown
 * code fences from the LLM's implementation output, and deriving the impl filename.
 */
class ImplementInterfaceWithTestsActionTest {

    @Test
    fun `strips kotlin code fences`() {
        val raw = "```kotlin\npackage foo\n\nclass Bar\n```"
        val stripped = ImplementInterfaceWithTestsAction.stripCodeFencesForTest(raw)
        assertEquals("package foo\n\nclass Bar", stripped)
    }

    @Test
    fun `strips plain code fences`() {
        val raw = "```\nclass Bar\n```"
        val stripped = ImplementInterfaceWithTestsAction.stripCodeFencesForTest(raw)
        assertEquals("class Bar", stripped)
    }

    @Test
    fun `leaves non-fenced output unchanged`() {
        val raw = "package foo\nclass Bar"
        assertEquals(raw, ImplementInterfaceWithTestsAction.stripCodeFencesForTest(raw))
    }

    @Test
    fun `derives impl filename from interface name`() {
        val name = ImplementInterfaceWithTestsAction.implFileNameForTest("Calculator.kt")
        assertEquals("CalculatorImpl.kt", name)
    }
}
