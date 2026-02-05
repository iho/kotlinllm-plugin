package com.jetbrains.kotlinllm.kotlinllmplugin.services

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.jetbrains.kotlinllm.kotlinllmplugin.modes.TrackedMethod
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

private const val MAX_PERSISTED_SESSIONS = 100
private const val MAX_PERSISTED_EVENTS = 1_000

@State(
    name = "KotlinLlmStats",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)]
)
@Service(Service.Level.PROJECT)
internal class KotlinLlmStatsService(private val project: Project) : PersistentStateComponent<KotlinLlmStatsState> {
    private val lock = Any()
    private var state = KotlinLlmStatsState()
    private var activeSessionId: String? = null

    override fun getState(): KotlinLlmStatsState = synchronized(lock) { state }

    override fun loadState(state: KotlinLlmStatsState) {
        synchronized(lock) {
            this.state = state
        }
    }

    fun startSession(runConfigurationName: String): String = synchronized(lock) {
        val sessionId = UUID.randomUUID().toString()
        val now = now()
        activeSessionId = sessionId
        state.totalRunsStarted++
        state.updatedAt = now
        state.sessions.add(
            KotlinLlmSessionStats().apply {
                this.sessionId = sessionId
                this.runConfigurationName = runConfigurationName
                startedAt = now
                status = "running"
            }
        )
        trimSessions()
        addEvent(sessionId, "run_started", message = runConfigurationName, timestamp = now)
        sessionId
    }

    fun finishSession(sessionId: String, status: String, error: String? = null) = synchronized(lock) {
        val now = now()
        if (activeSessionId == sessionId) {
            activeSessionId = null
        }
        state.updatedAt = now
        session(sessionId)?.apply {
            finishedAt = now
            this.status = status
            lastError = error.orEmpty()
        }
        when (status) {
            "failed" -> state.totalRunsFailed++
            else -> state.totalRunsCompleted++
        }
        addEvent(sessionId, "run_finished", message = error ?: status, timestamp = now)
    }

    fun recordModeSetup(sessionId: String, mode: String, trackedMethods: Int) = synchronized(lock) {
        val now = touch()
        state.totalTrackedMethods += trackedMethods.toLong()
        session(sessionId)?.apply {
            totalTrackedMethods += trackedMethods
            when (mode) {
                "asLlm" -> asLlmTrackedMethods += trackedMethods
                "mockLlm" -> mockLlmTrackedMethods += trackedMethods
            }
        }
        addEvent(sessionId, "mode_setup", mode = mode, count = trackedMethods, timestamp = now)
    }

    fun recordMethodIntercepted(sessionId: String?, trackedMethod: TrackedMethod, argumentCount: Int) = synchronized(lock) {
        val resolvedSessionId = sessionId ?: activeSessionId
        val now = touch()
        state.totalMethodInterceptions++
        session(resolvedSessionId)?.methodInterceptions++
        addEvent(
            resolvedSessionId,
            "method_intercepted",
            className = trackedMethod.qualifiedImplementationClassName(),
            methodName = trackedMethod.implementationMethodName,
            count = argumentCount,
            timestamp = now,
        )
    }

    fun recordLlmRequestCompleted(
        mode: String,
        success: Boolean,
        durationMs: Long,
        toolCalls: Int,
        responseLength: Int,
        error: String? = null,
    ) = synchronized(lock) {
        val sessionId = activeSessionId
        val now = touch()
        state.totalLlmRequests++
        state.totalLlmDurationMs += durationMs
        state.totalLlmToolCalls += toolCalls.toLong()
        if (!success) state.totalLlmFailures++
        session(sessionId)?.apply {
            llmRequests++
            llmDurationMs += durationMs
            llmToolCalls += toolCalls
            if (!success) llmFailures++
            if (!error.isNullOrBlank()) lastError = error
        }
        addEvent(
            sessionId,
            if (success) "llm_request_succeeded" else "llm_request_failed",
            mode = mode,
            durationMs = durationMs,
            count = toolCalls,
            message = if (success) "responseLength=$responseLength" else error,
            timestamp = now,
        )
    }

    fun recordCodeGenerationFailure(sessionId: String?, message: String) = synchronized(lock) {
        val resolvedSessionId = sessionId ?: activeSessionId
        val now = touch()
        state.totalCodeGenerationFailures++
        session(resolvedSessionId)?.apply {
            codeGenerationFailures++
            lastError = message
        }
        addEvent(resolvedSessionId, "code_generation_failed", message = message, timestamp = now)
    }

    fun recordCompilationCompleted(sessionId: String?, success: Boolean, durationMs: Long, message: String? = null) =
        synchronized(lock) {
            val resolvedSessionId = sessionId ?: activeSessionId
            val now = touch()
            state.totalCompilationAttempts++
            state.totalCompilationDurationMs += durationMs
            if (success) {
                state.totalCompilationSuccesses++
            } else {
                state.totalCompilationFailures++
            }
            session(resolvedSessionId)?.apply {
                compilationAttempts++
                compilationDurationMs += durationMs
                if (success) {
                    compilationSuccesses++
                } else {
                    compilationFailures++
                    lastError = message.orEmpty()
                }
            }
            addEvent(
                resolvedSessionId,
                if (success) "compilation_succeeded" else "compilation_failed",
                durationMs = durationMs,
                message = message,
                timestamp = now,
            )
        }

    fun recordHotReloadCompleted(
        sessionId: String?,
        success: Boolean,
        classCount: Int,
        durationMs: Long,
        message: String? = null,
    ) = synchronized(lock) {
        val resolvedSessionId = sessionId ?: activeSessionId
        val now = touch()
        state.totalHotReloadAttempts++
        state.totalHotReloadDurationMs += durationMs
        if (success) {
            state.totalHotReloadSuccesses++
        } else {
            state.totalHotReloadFailures++
        }
        session(resolvedSessionId)?.apply {
            hotReloadAttempts++
            hotReloadDurationMs += durationMs
            if (success) {
                hotReloadSuccesses++
            } else {
                hotReloadFailures++
                lastError = message.orEmpty()
            }
        }
        addEvent(
            resolvedSessionId,
            if (success) "hot_reload_succeeded" else "hot_reload_failed",
            count = classCount,
            durationMs = durationMs,
            message = message,
            timestamp = now,
        )
    }

    fun recordGeneratedUpdateAccepted(mode: String, trackedMethod: TrackedMethod) = synchronized(lock) {
        val sessionId = activeSessionId
        val now = touch()
        state.totalGeneratedUpdatesAccepted++
        session(sessionId)?.generatedUpdatesAccepted++
        addEvent(
            sessionId,
            "generated_update_accepted",
            mode = mode,
            className = trackedMethod.qualifiedImplementationClassName(),
            methodName = trackedMethod.implementationMethodName,
            timestamp = now,
        )
    }

    fun recordInvocationRestarted(sessionId: String?, callerDescription: String) = synchronized(lock) {
        val resolvedSessionId = sessionId ?: activeSessionId
        val now = touch()
        state.totalInvocationRestarts++
        session(resolvedSessionId)?.invocationRestarts++
        addEvent(resolvedSessionId, "invocation_restarted", message = callerDescription, timestamp = now)
    }

    fun exportAsJson(): String = synchronized(lock) {
        buildString {
            appendLine("{")
            appendJsonField("schemaVersion", state.schemaVersion, comma = true, indent = 2)
            appendJsonField("projectName", project.name, comma = true, indent = 2)
            appendJsonField("createdAt", state.createdAt, comma = true, indent = 2)
            appendJsonField("updatedAt", state.updatedAt, comma = true, indent = 2)
            appendLine("  \"totals\": {")
            appendJsonField("runsStarted", state.totalRunsStarted, comma = true, indent = 4)
            appendJsonField("runsCompleted", state.totalRunsCompleted, comma = true, indent = 4)
            appendJsonField("runsFailed", state.totalRunsFailed, comma = true, indent = 4)
            appendJsonField("trackedMethods", state.totalTrackedMethods, comma = true, indent = 4)
            appendJsonField("methodInterceptions", state.totalMethodInterceptions, comma = true, indent = 4)
            appendJsonField("llmRequests", state.totalLlmRequests, comma = true, indent = 4)
            appendJsonField("llmFailures", state.totalLlmFailures, comma = true, indent = 4)
            appendJsonField("llmToolCalls", state.totalLlmToolCalls, comma = true, indent = 4)
            appendJsonField("llmDurationMs", state.totalLlmDurationMs, comma = true, indent = 4)
            appendJsonField("codeGenerationFailures", state.totalCodeGenerationFailures, comma = true, indent = 4)
            appendJsonField("compilationAttempts", state.totalCompilationAttempts, comma = true, indent = 4)
            appendJsonField("compilationSuccesses", state.totalCompilationSuccesses, comma = true, indent = 4)
            appendJsonField("compilationFailures", state.totalCompilationFailures, comma = true, indent = 4)
            appendJsonField("compilationDurationMs", state.totalCompilationDurationMs, comma = true, indent = 4)
            appendJsonField("hotReloadAttempts", state.totalHotReloadAttempts, comma = true, indent = 4)
            appendJsonField("hotReloadSuccesses", state.totalHotReloadSuccesses, comma = true, indent = 4)
            appendJsonField("hotReloadFailures", state.totalHotReloadFailures, comma = true, indent = 4)
            appendJsonField("hotReloadDurationMs", state.totalHotReloadDurationMs, comma = true, indent = 4)
            appendJsonField("generatedUpdatesAccepted", state.totalGeneratedUpdatesAccepted, comma = true, indent = 4)
            appendJsonField("invocationRestarts", state.totalInvocationRestarts, comma = false, indent = 4)
            appendLine("  },")
            appendLine("  \"sessions\": [")
            state.sessions.forEachIndexed { index, session ->
                appendSession(session, comma = index < state.sessions.lastIndex)
            }
            appendLine("  ],")
            appendLine("  \"recentEvents\": [")
            state.recentEvents.forEachIndexed { index, event ->
                appendEvent(event, comma = index < state.recentEvents.lastIndex)
            }
            appendLine("  ]")
            appendLine("}")
        }
    }

    fun clear() = synchronized(lock) {
        state = KotlinLlmStatsState()
        activeSessionId = null
    }

    private fun session(sessionId: String?): KotlinLlmSessionStats? {
        if (sessionId == null) return null
        return state.sessions.firstOrNull { it.sessionId == sessionId }
    }

    private fun touch(): String {
        val now = now()
        state.updatedAt = now
        return now
    }

    private fun addEvent(
        sessionId: String?,
        type: String,
        mode: String = "",
        className: String = "",
        methodName: String = "",
        count: Int = 0,
        durationMs: Long = -1,
        message: String? = null,
        timestamp: String = now(),
    ) {
        state.recentEvents.add(
            KotlinLlmStatsEvent().apply {
                this.timestamp = timestamp
                this.sessionId = sessionId.orEmpty()
                this.type = type
                this.mode = mode
                this.className = className
                this.methodName = methodName
                this.count = count
                this.durationMs = durationMs
                this.message = message.orEmpty().take(2_000)
            }
        )
        while (state.recentEvents.size > MAX_PERSISTED_EVENTS) {
            state.recentEvents.removeAt(0)
        }
    }

    private fun trimSessions() {
        while (state.sessions.size > MAX_PERSISTED_SESSIONS) {
            state.sessions.removeAt(0)
        }
    }

    private fun TrackedMethod.qualifiedImplementationClassName(): String {
        return if (implementationPackageName == "<root>" || implementationPackageName.isBlank()) {
            implementationClassName
        } else {
            "$implementationPackageName.$implementationClassName"
        }
    }

    private fun now(): String = Instant.now().toString()
}

internal class KotlinLlmStatsState {
    var schemaVersion: Int = 1
    var createdAt: String = Instant.now().toString()
    var updatedAt: String = createdAt
    var totalRunsStarted: Long = 0
    var totalRunsCompleted: Long = 0
    var totalRunsFailed: Long = 0
    var totalTrackedMethods: Long = 0
    var totalMethodInterceptions: Long = 0
    var totalLlmRequests: Long = 0
    var totalLlmFailures: Long = 0
    var totalLlmToolCalls: Long = 0
    var totalLlmDurationMs: Long = 0
    var totalCodeGenerationFailures: Long = 0
    var totalCompilationAttempts: Long = 0
    var totalCompilationSuccesses: Long = 0
    var totalCompilationFailures: Long = 0
    var totalCompilationDurationMs: Long = 0
    var totalHotReloadAttempts: Long = 0
    var totalHotReloadSuccesses: Long = 0
    var totalHotReloadFailures: Long = 0
    var totalHotReloadDurationMs: Long = 0
    var totalGeneratedUpdatesAccepted: Long = 0
    var totalInvocationRestarts: Long = 0
    var sessions: MutableList<KotlinLlmSessionStats> = mutableListOf()
    var recentEvents: MutableList<KotlinLlmStatsEvent> = mutableListOf()
}

internal class KotlinLlmSessionStats {
    var sessionId: String = ""
    var runConfigurationName: String = ""
    var startedAt: String = ""
    var finishedAt: String = ""
    var status: String = ""
    var asLlmTrackedMethods: Int = 0
    var mockLlmTrackedMethods: Int = 0
    var totalTrackedMethods: Int = 0
    var methodInterceptions: Int = 0
    var llmRequests: Int = 0
    var llmFailures: Int = 0
    var llmToolCalls: Int = 0
    var llmDurationMs: Long = 0
    var codeGenerationFailures: Int = 0
    var compilationAttempts: Int = 0
    var compilationSuccesses: Int = 0
    var compilationFailures: Int = 0
    var compilationDurationMs: Long = 0
    var hotReloadAttempts: Int = 0
    var hotReloadSuccesses: Int = 0
    var hotReloadFailures: Int = 0
    var hotReloadDurationMs: Long = 0
    var generatedUpdatesAccepted: Int = 0
    var invocationRestarts: Int = 0
    var lastError: String = ""
}

internal class KotlinLlmStatsEvent {
    var timestamp: String = ""
    var sessionId: String = ""
    var type: String = ""
    var mode: String = ""
    var className: String = ""
    var methodName: String = ""
    var count: Int = 0
    var durationMs: Long = -1
    var message: String = ""
}

internal val Project.kotlinLlmStatsService: KotlinLlmStatsService
    get() = service()

internal fun elapsedMillis(startedNanos: Long, finishedNanos: Long): Long =
    TimeUnit.NANOSECONDS.toMillis(finishedNanos - startedNanos)

private fun StringBuilder.appendSession(session: KotlinLlmSessionStats, comma: Boolean) {
    appendLine("    {")
    appendJsonField("sessionId", session.sessionId, comma = true, indent = 6)
    appendJsonField("runConfigurationName", session.runConfigurationName, comma = true, indent = 6)
    appendJsonField("startedAt", session.startedAt, comma = true, indent = 6)
    appendJsonField("finishedAt", session.finishedAt, comma = true, indent = 6)
    appendJsonField("status", session.status, comma = true, indent = 6)
    appendJsonField("asLlmTrackedMethods", session.asLlmTrackedMethods, comma = true, indent = 6)
    appendJsonField("mockLlmTrackedMethods", session.mockLlmTrackedMethods, comma = true, indent = 6)
    appendJsonField("totalTrackedMethods", session.totalTrackedMethods, comma = true, indent = 6)
    appendJsonField("methodInterceptions", session.methodInterceptions, comma = true, indent = 6)
    appendJsonField("llmRequests", session.llmRequests, comma = true, indent = 6)
    appendJsonField("llmFailures", session.llmFailures, comma = true, indent = 6)
    appendJsonField("llmToolCalls", session.llmToolCalls, comma = true, indent = 6)
    appendJsonField("llmDurationMs", session.llmDurationMs, comma = true, indent = 6)
    appendJsonField("codeGenerationFailures", session.codeGenerationFailures, comma = true, indent = 6)
    appendJsonField("compilationAttempts", session.compilationAttempts, comma = true, indent = 6)
    appendJsonField("compilationSuccesses", session.compilationSuccesses, comma = true, indent = 6)
    appendJsonField("compilationFailures", session.compilationFailures, comma = true, indent = 6)
    appendJsonField("compilationDurationMs", session.compilationDurationMs, comma = true, indent = 6)
    appendJsonField("hotReloadAttempts", session.hotReloadAttempts, comma = true, indent = 6)
    appendJsonField("hotReloadSuccesses", session.hotReloadSuccesses, comma = true, indent = 6)
    appendJsonField("hotReloadFailures", session.hotReloadFailures, comma = true, indent = 6)
    appendJsonField("hotReloadDurationMs", session.hotReloadDurationMs, comma = true, indent = 6)
    appendJsonField("generatedUpdatesAccepted", session.generatedUpdatesAccepted, comma = true, indent = 6)
    appendJsonField("invocationRestarts", session.invocationRestarts, comma = true, indent = 6)
    appendJsonField("lastError", session.lastError, comma = false, indent = 6)
    append("    }")
    if (comma) append(',')
    appendLine()
}

private fun StringBuilder.appendEvent(event: KotlinLlmStatsEvent, comma: Boolean) {
    appendLine("    {")
    appendJsonField("timestamp", event.timestamp, comma = true, indent = 6)
    appendJsonField("sessionId", event.sessionId, comma = true, indent = 6)
    appendJsonField("type", event.type, comma = true, indent = 6)
    appendJsonField("mode", event.mode, comma = true, indent = 6)
    appendJsonField("className", event.className, comma = true, indent = 6)
    appendJsonField("methodName", event.methodName, comma = true, indent = 6)
    appendJsonField("count", event.count, comma = true, indent = 6)
    appendJsonField("durationMs", event.durationMs, comma = true, indent = 6)
    appendJsonField("message", event.message, comma = false, indent = 6)
    append("    }")
    if (comma) append(',')
    appendLine()
}

private fun StringBuilder.appendJsonField(name: String, value: String, comma: Boolean, indent: Int) {
    append(" ".repeat(indent))
    appendJsonString(name)
    append(": ")
    appendJsonString(value)
    if (comma) append(',')
    appendLine()
}

private fun StringBuilder.appendJsonField(name: String, value: Number, comma: Boolean, indent: Int) {
    append(" ".repeat(indent))
    appendJsonString(name)
    append(": ")
    append(value)
    if (comma) append(',')
    appendLine()
}

private fun StringBuilder.appendJsonString(value: String) {
    append('"')
    value.forEach { char ->
        when (char) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> {
                if (char.code < 0x20) {
                    append("\\u")
                    append(char.code.toString(16).padStart(4, '0'))
                } else {
                    append(char)
                }
            }
        }
    }
    append('"')
}
