package com.jetbrains.kotlinllm.kotlinllmplugin.snapshot

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualValue
import com.jetbrains.kotlinllm.kotlinllmplugin.modes.TrackedMethod
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Project-scoped capture of live runtime invocations observed by the KotlinLLM
 * breakpoint loop. This is the missing bridge between a real `asLlm`/`mockLlm`
 * run and the snapshot orchestration: when a method is intercepted at a
 * breakpoint, its argument values are recorded here so that a later
 * `RunSnapshotOrchestrationAction` can seed `snapshot.state` with real observed
 * calls instead of an empty `{}`.
 *
 * The loop runs off-thread and interleaves with the EDT, so captures are
 * stored in a thread-safe concurrent queue and snapshotted on demand.
 */
@Service(Service.Level.PROJECT)
internal class SnapshotCaptureService {

    private val observed = ConcurrentLinkedQueue<CapturedInvocation>()

    /** Record one intercepted invocation from the breakpoint loop. */
    fun recordInvocation(
        trackedMethod: TrackedMethod,
        arguments: List<ActualValue>,
    ) {
        observed.add(
            CapturedInvocation(
                methodName = trackedMethod.implementationMethodName,
                classSimpleName = trackedMethod.implementationClassName,
                arguments = arguments.associate { it.name to it.value },
                returnType = trackedMethod.returnType.name,
                capturedAt = Instant.now().toString(),
            )
        )
    }

    /** Number of invocations captured so far. */
    fun size(): Int = observed.size

    /** Whether any real runtime invocation has been captured. */
    fun isEmpty(): Boolean = observed.isEmpty()

    /** Clear the captured invocations (e.g. when a new run starts). */
    fun reset() {
        observed.clear()
    }

    /**
     * Build a [SnapshotState] seeded from the captured invocations, keyed by
     * [scenarioId]. The state map folds all observed arguments; the
     * [ObservedCall] list preserves per-invocation structure for the agents.
     */
    fun toSnapshotState(scenarioId: String): SnapshotState {
        val calls = observed.map { it.toObservedCall() }
        val stateMap = buildMap {
            observed.forEach { inv ->
                inv.arguments.forEach { (k, v) ->
                    put("${inv.methodName}.$k", v)
                }
            }
        }
        return SnapshotState(
            scenarioId = scenarioId,
            state = stateMap,
            observedCalls = calls,
            capturedAt = observed.lastOrNull()?.capturedAt ?: Instant.now().toString(),
        )
    }

    private data class CapturedInvocation(
        val methodName: String,
        val classSimpleName: String,
        val arguments: Map<String, String>,
        val returnType: String,
        val capturedAt: String,
    ) {
        fun toObservedCall(): ObservedCall = ObservedCall(
            methodName = "$classSimpleName.$methodName",
            arguments = arguments,
            returnType = returnType,
            returnValue = null,
        )
    }
}

internal val Project.snapshotCaptureService: SnapshotCaptureService
    get() = service()
