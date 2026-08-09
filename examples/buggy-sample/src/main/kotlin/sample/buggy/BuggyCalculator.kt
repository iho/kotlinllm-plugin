package sample.buggy

/**
 * A deliberately buggy utility class used to exercise the multi-agent snapshot
 * convention. Each function has a known, documented bug. The BugFixer agent is
 * expected to read this source, identify the bugs, and write corrected code back.
 */
object BuggyCalculator {

    /**
     * BUG: returns true for x == 0 (should be false). Off-by-one at the boundary.
     * Expected: isPositive(0) == false, isPositive(1) == true.
     */
    fun isPositive(x: Int): Boolean = x >= 0

    /**
     * BUG: uses `>` instead of `>=`, so the upper bound is excluded.
     * Expected: isInRange(10, 0, 10) == true.
     */
    fun isInRange(value: Int, min: Int, max: Int): Boolean = value > min && value < max

    /**
     * BUG: returns the sum instead of the difference.
     * Expected: subtract(10, 3) == 7.
     */
    fun subtract(a: Int, b: Int): Int = a + b

    /**
     * BUG: returns 0 for any input (should return the absolute value).
     * Expected: abs(-5) == 5, abs(5) == 5.
     */
    fun abs(x: Int): Int = 0

    /**
     * BUG: returns the first element instead of the last.
     * Expected: lastOf(listOf(1,2,3)) == 3.
     */
    fun lastOf(items: List<Int>): Int = items.first()
}
