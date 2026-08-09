package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

/**
 * Snapshot convention for multi-agent spec generation.
 *
 * Each scenario is materialized under `<generatedFolder>/snapshots/<scenario-name>/`:
 *
 * ```
 * snapshots/<scenario-name>/
 *     snapshot.json        # machine-readable current-state snapshot
 *     snapshot.spec.kt     # SpecAuthor: behavior spec / invariants
 *     snapshot.review.kt   # SpecReviewer: gaps, counter-examples, feedback
 *     snapshot.test.kt     # TestGenerator: @MutFlowTest + MutFlow.underTest { } tests
 *     coverage.md          # MutationAuditor: mutflow killed/survived report
 * ```
 *
 * The in-memory [ScenarioSnapshot] mirrors this shape so agents can pass data around
 * without re-parsing files, and [SnapshotIo] serializes it to/from disk.
 */

/** File names used by the convention. */
object SnapshotFiles {
    const val SNAPSHOT_JSON = "snapshot.json"
    const val SPEC = "snapshot.spec.kt"
    const val REVIEW = "snapshot.review.kt"
    const val TESTS = "snapshot.test.kt"
    const val COVERAGE = "coverage.md"
}

/** Root folder (relative to the generated source root) where scenarios live. */
const val SNAPSHOT_ROOT = "snapshots"

/** One materialized scenario: the spec, its review, its tests, and its coverage. */
data class ScenarioSnapshot(
    /** Machine-readable current-state snapshot captured from a runtime run. */
    val state: SnapshotState,
    /** SpecAuthor output: behavior spec / invariants. */
    var spec: String = "",
    /** SpecReviewer output: gaps, counter-examples, feedback. */
    var review: String = "",
    /** TestGenerator output: Kotlin @MutFlowTest source. */
    var tests: String = "",
    /** MutationAuditor output: coverage report (markdown). */
    var coverage: String = "",
    /** Whether the current state has changed since the last materialization. */
    var dirty: Boolean = true,
)

/** Machine-readable snapshot of a mock/scenario's current state. */
data class SnapshotState(
    /** Stable identifier for the scenario (e.g. the tracked method FQN). */
    val scenarioId: String,
    /** The captured state map, mirroring `stateSnapshot`. */
    val state: Map<String, String> = emptyMap(),
    /** The observed call values that produced this snapshot. */
    val observedCalls: List<ObservedCall> = emptyList(),
    /** ISO timestamp when the snapshot was taken. */
    val capturedAt: String = "",
)

/** A single observed invocation that contributed to a snapshot. */
data class ObservedCall(
    val methodName: String,
    val arguments: Map<String, String> = emptyMap(),
    val returnType: String = "",
    val returnValue: String? = null,
)
