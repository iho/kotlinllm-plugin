package sample.buggy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Documents the CORRECT expected behavior of BuggyCalculator. These tests fail
 * against the buggy implementation and pass once the BugFixer agent corrects it.
 */
class BuggyCalculatorTest {

    @Test
    fun `isPositive returns false for zero`() {
        assertFalse(BuggyCalculator.isPositive(0))
    }

    @Test
    fun `isPositive returns true for positive`() {
        assertTrue(BuggyCalculator.isPositive(1))
    }

    @Test
    fun `isInRange includes upper bound`() {
        assertTrue(BuggyCalculator.isInRange(10, 0, 10))
    }

    @Test
    fun `subtract returns difference`() {
        assertEquals(7, BuggyCalculator.subtract(10, 3))
    }

    @Test
    fun `abs returns absolute value`() {
        assertEquals(5, BuggyCalculator.abs(-5))
        assertEquals(5, BuggyCalculator.abs(5))
    }

    @Test
    fun `lastOf returns last element`() {
        assertEquals(3, BuggyCalculator.lastOf(listOf(1, 2, 3)))
    }
}
