package com.jetbrains.kotlinllm.kotlinllmplugin.services

import com.intellij.build.BuildViewManager
import com.intellij.build.events.*
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.task.ProjectTaskManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.concurrency.await
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.pathString

data class ProjectProblem(
    val message: String,
    val kind: String = MessageEvent.Kind.ERROR.name,
    val group: String? = null,
    val description: String? = null,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null
)

data class BuildProjectResult(
    val timedOut: Boolean,
    val isSuccess: Boolean,
    val problems: List<ProjectProblem>
)

class BuildProjectService(private val project: Project) {
    private companion object {
        const val BUILD_EVENT_DRAIN_TIMEOUT_MS = 500L
    }

    suspend fun buildProject(
        rebuild: Boolean = false,
        filesToRebuild: List<String>? = null,
        timeoutMs: Int = 120_000,
        progressLogger: ((String) -> Unit)? = null
    ): BuildProjectResult {
        val taskManager = ProjectTaskManager.getInstance(project)
        val problems = CopyOnWriteArrayList<ProjectProblem>()
        val buildFinished = CompletableDeferred<Unit>()
        val buildStarted = AtomicBoolean(false)
        val startedNanos = System.nanoTime()

        progressLogger?.invoke(
            "Build request started: mode=${if (rebuild) "rebuild" else "build"}, " +
                "files=${filesToRebuild?.joinToString().orEmpty().ifBlank { "<all modules>" }}, " +
                "timeout=${timeoutMs}ms."
        )

        val listenerDisposable: Disposable = Disposer.newDisposable("KotlinLLM-Build-Listener")
        progressLogger?.invoke("BuildView listener registered.")
        project.service<BuildViewManager>().addListener(
            { _, event ->
                when (event) {
                    is StartBuildEvent -> {
                        buildStarted.set(true)
                        progressLogger?.invoke("BuildView start event: ${event.message}")
                    }

                    is FileMessageEvent -> {
                        if (event.kind == MessageEvent.Kind.ERROR || event.kind == MessageEvent.Kind.WARNING) {
                            val filePosition = event.filePosition
                            val filePath = filePosition.file?.toPath()?.pathString?.let { toProjectRelativePath(it) }
                            problems.add(
                                ProjectProblem(
                                    message = event.message,
                                    kind = event.kind.name,
                                    group = event.group,
                                    description = event.description,
                                    file = filePath,
                                    line = filePosition.startLine,
                                    column = filePosition.startColumn
                                )
                            )
                            progressLogger?.invoke("BuildView ${event.kind.name.lowercase()} for ${filePath ?: "<unknown file>"}: ${event.message}")
                        }
                    }

                    is BuildIssueEvent -> {
                        if (event.kind == MessageEvent.Kind.ERROR || event.kind == MessageEvent.Kind.WARNING) {
                            problems.add(
                                ProjectProblem(
                                    message = event.message,
                                    kind = event.kind.name,
                                    group = event.group,
                                    description = event.description ?: event.issue.description
                                )
                            )
                            progressLogger?.invoke("BuildView ${event.kind.name.lowercase()}: ${event.message}")
                        }
                    }

                    is FinishBuildEvent -> {
                        val eventResult = event.result
                        progressLogger?.invoke("BuildView finish event: $eventResult")
                        if (eventResult is FailureResult) {
                            problems.add(
                                ProjectProblem(
                                    message = "Build failure: $eventResult",
                                    kind = MessageEvent.Kind.ERROR.name
                                )
                            )
                        }
                        if (!buildFinished.isCompleted) {
                            buildFinished.complete(Unit)
                        }
                    }

                    is MessageEvent -> {
                        if (event.kind == MessageEvent.Kind.ERROR || event.kind == MessageEvent.Kind.WARNING) {
                            problems.add(
                                ProjectProblem(
                                    message = event.message,
                                    kind = event.kind.name,
                                    group = event.group,
                                    description = event.description
                                )
                            )
                            progressLogger?.invoke("BuildView ${event.kind.name.lowercase()}: ${event.message}")
                        }
                    }
                }
            },
            listenerDisposable
        )

        val buildResult = try {
            progressLogger?.invoke("Entering ProjectTaskManager timeout block.")
            withTimeoutOrNull(timeoutMs.toLong()) {
                val result = runBuildTask(taskManager, rebuild, filesToRebuild, progressLogger)
                progressLogger?.invoke(
                    "ProjectTaskManager completed in ${elapsedMillis(startedNanos, System.nanoTime())}ms: " +
                        "hasErrors=${result.hasErrors()}, aborted=${result.isAborted()}."
                )
                if (buildStarted.get()) {
                    progressLogger?.invoke("Waiting up to ${BUILD_EVENT_DRAIN_TIMEOUT_MS}ms for trailing BuildView finish event.")
                    withTimeoutOrNull(BUILD_EVENT_DRAIN_TIMEOUT_MS) {
                        buildFinished.await()
                    }
                    progressLogger?.invoke("Finished BuildView diagnostics drain.")
                } else {
                    progressLogger?.invoke("No BuildView start event observed before ProjectTaskManager completion.")
                }
                result
            }
        } finally {
            Disposer.dispose(listenerDisposable)
            progressLogger?.invoke("BuildView listener disposed.")
        }

        if (buildResult == null) {
            progressLogger?.invoke("Build request timed out after ${elapsedMillis(startedNanos, System.nanoTime())}ms.")
        }

        if (!buildStarted.get()) {
            problems.add(
                ProjectProblem(
                    message = "The project has limited build diagnostics functionality. Build messages were not captured."
                )
            )
        }

        val hasTaskErrors = buildResult?.hasErrors() == true
        if (hasTaskErrors && problems.none { it.kind == MessageEvent.Kind.ERROR.name }) {
            problems.add(
                ProjectProblem(
                    message = "Build reported errors, but detailed error messages were not captured.",
                    kind = MessageEvent.Kind.ERROR.name
                )
            )
        }

        return BuildProjectResult(
            timedOut = buildResult == null,
            isSuccess = buildResult != null && !hasTaskErrors && problems.none { it.kind == MessageEvent.Kind.ERROR.name },
            problems = problems.toList()
        )
    }

    private suspend fun runBuildTask(
        taskManager: ProjectTaskManager,
        rebuild: Boolean,
        filesToRebuild: List<String>?,
        progressLogger: ((String) -> Unit)?
    ): ProjectTaskManager.Result {
        if (!filesToRebuild.isNullOrEmpty()) {
            progressLogger?.invoke("Resolving ${filesToRebuild.size} file(s) for ProjectTaskManager.compile.")
            val virtualFiles = filesToRebuild.mapNotNull { path ->
                val virtualFile = resolveVirtualFileWithoutRefresh(path)
                progressLogger?.invoke(
                    "VFS lookup ${if (virtualFile == null) "miss" else "hit"}: $path" +
                        (virtualFile?.let { " -> ${it.path}" } ?: "")
                )
                virtualFile
            }
            if (virtualFiles.isNotEmpty()) {
                progressLogger?.invoke("Calling ProjectTaskManager.compile for ${virtualFiles.size} file(s).")
                val promise = taskManager.compile(*virtualFiles.toTypedArray())
                progressLogger?.invoke("ProjectTaskManager.compile returned a promise; awaiting result.")
                val result = promise.await()
                progressLogger?.invoke("ProjectTaskManager.compile await returned.")
                return result
            }
            progressLogger?.invoke("No VirtualFile resolved for requested file compile; falling back to all modules.")
        }

        return if (rebuild) {
            progressLogger?.invoke("Calling ProjectTaskManager.rebuildAllModules.")
            val promise = taskManager.rebuildAllModules()
            progressLogger?.invoke("ProjectTaskManager.rebuildAllModules returned a promise; awaiting result.")
            val result = promise.await()
            progressLogger?.invoke("ProjectTaskManager.rebuildAllModules await returned.")
            result
        } else {
            progressLogger?.invoke("Calling ProjectTaskManager.buildAllModules.")
            val promise = taskManager.buildAllModules()
            progressLogger?.invoke("ProjectTaskManager.buildAllModules returned a promise; awaiting result.")
            val result = promise.await()
            progressLogger?.invoke("ProjectTaskManager.buildAllModules await returned.")
            result
        }
    }

    private fun resolveVirtualFileWithoutRefresh(path: String): VirtualFile? {
        val projectBasePath = project.basePath ?: return null
        val rawPath = Path.of(path)
        val fullPath = if (rawPath.isAbsolute) rawPath else Path.of(projectBasePath, path)
        return LocalFileSystem.getInstance().findFileByNioFile(fullPath)
    }

    private fun toProjectRelativePath(path: String): String {
        val projectBasePath = project.basePath ?: return path
        return runCatching {
            Path.of(projectBasePath).relativize(Path.of(path)).pathString
        }.getOrElse { path }
    }
}
