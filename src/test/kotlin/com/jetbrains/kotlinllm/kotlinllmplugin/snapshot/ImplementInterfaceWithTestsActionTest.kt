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

    @Test
    fun `extracts interface name from file contents not filename`() {
        // The filename "Greeter.kt" does NOT contain "interface X"; the name must
        // come from the file contents. This is the regression that caused
        // "Failed to write implementation file."
        val contents = "package foo\n\ninterface Greeter {\n    fun greet(name: String): String\n}"
        val name = ImplementInterfaceWithTestsAction.interfaceNameFromContentsForTest(contents)
        assertEquals("Greeter", name)
    }
}
