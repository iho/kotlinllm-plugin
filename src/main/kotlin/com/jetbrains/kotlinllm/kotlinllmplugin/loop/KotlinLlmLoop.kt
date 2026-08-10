package com.jetbrains.kotlinllm.kotlinllmplugin.loop

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.CompilerModuleExtension
import com.intellij.psi.PsiDocumentManager
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.findGeneratedSourceFile
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.optimizeGeneratedImports
import com.jetbrains.kotlinllm.kotlinllmplugin.jdi.JdiLauncher
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualValue
import com.jetbrains.kotlinllm.kotlinllmplugin.modes.KotlinLlmMode
import com.jetbrains.kotlinllm.kotlinllmplugin.modes.TrackedMethod
import com.jetbrains.kotlinllm.kotlinllmplugin.services.BuildProjectService
import com.jetbrains.kotlinllm.kotlinllmplugin.services.elapsedMillis
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmStatsService
import com.jetbrains.kotlinllm.kotlinllmplugin.services.resolveConfiguredBuildsFolderPath
import com.jetbrains.kotlinllm.kotlinllmplugin.services.toProjectRelativeKotlinLlmPath
import com.jetbrains.kotlinllm.kotlinllmplugin.snapshot.snapshotCaptureService
import com.sun.jdi.Method
import com.sun.jdi.ReferenceType
import com.sun.jdi.StringReference
import com.sun.jdi.ThreadReference
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.request.EventRequest
import com.sun.jdi.request.BreakpointRequest
import java.nio.file.Files
import java.nio.file.Path as NioPath
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes

/**
 * Main event loop that manages method tracking and hot-reloading for Kotlin LLM integration.
 * Intercepts method calls, triggers LLM code generation, and redefines classes at runtime.
 */
class KotlinLlmLoop(
    private val project: Project,
    private val vm: VirtualMachine,
    private val processHandler: ProcessHandler,
    private val consoleView: ConsoleView,
    private val jdiLauncher: JdiLauncher,
    private val statsSessionId: String? = null,
) {
    private sealed class CompilationResult {
        data class Success(val classesToRedefine: Map<ReferenceType, ByteArray>) : CompilationResult()
        data class Failure(val message: String) : CompilationResult()
    }

    private data class MethodBreakpointKey(
        val className: String,
        val methodName: String,
        val methodSignature: String,
    )

    private data class BreakpointSkipKey(
        val threadId: Long,
        val className: String,
        val methodName: String,
        val methodSignature: String,
    )

    private companion object {
        val LOG: Logger = Logger.getInstance(KotlinLlmLoop::class.java)
        const val COMPILATION_ERROR_FEEDBACK = "Current code has a compilation error."
        const val TRACKED_METHOD_PROPERTY = "kotlinllm.trackedMethod"
        const val METHOD_INTERCEPTION_ARGUMENT_PREVIEW_LIMIT = 80
    }

    private val trackedMethods: MutableSet<TrackedMethod> = mutableSetOf()
    private val modes: MutableList<KotlinLlmMode> = mutableListOf()
    private val buildProjectService = BuildProjectService(project)
    private val registeredMethodBreakpoints: MutableMap<MethodBreakpointKey, BreakpointRequest> = mutableMapOf()
    private val restartedInvocationBreakpointsToSkip: MutableSet<BreakpointSkipKey> = mutableSetOf()
    private val runStopped = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        processHandler.addProcessListener(object : ProcessListener {
            override fun startNotified(event: ProcessEvent) = Unit

            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = Unit

            override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                runStopped.set(true)
            }

            override fun processTerminated(event: ProcessEvent) {
                runStopped.set(true)
            }
        })
    }

    fun registerPreparedMode(mode: KotlinLlmMode, setUpMethods: Set<TrackedMethod>) {
        modes.add(mode)
        trackedMethods.addAll(setUpMethods)
    }

    suspend fun start() {
        registerMethodBreakpointsForLoadedClasses()
        jdiLauncher.launch(
            vm = vm,
            processHandler = processHandler,
            consoleView = consoleView,
            onClassPrepare = { event ->
                registerMethodBreakpointsForReferenceType(event.referenceType())
            },
            onBreakpoint = { event ->
                handleMethodBreakpoint(event)
            },
        )
    }

    private fun registerMethodBreakpointsForLoadedClasses() {
        trackedMethods
            .distinctBy { trackedClassFqName(it) }
            .forEach { trackedMethod ->
                val className = trackedClassFqName(trackedMethod)
                vm.classesByName(className).forEach { referenceType ->
                    registerMethodBreakpointsForReferenceType(referenceType)
                }
            }
    }

    private fun registerMethodBreakpointsForReferenceType(referenceType: ReferenceType) {
        val className = referenceType.name()
        trackedMethods
            .filter { trackedClassFqName(it) == className }
            .forEach { trackedMethod ->
                registerMethodBreakpoint(referenceType, trackedMethod)
            }
    }

    private fun registerMethodBreakpoint(referenceType: ReferenceType, trackedMethod: TrackedMethod) {
        val matchingMethods = referenceType
            .methodsByName(trackedMethod.implementationMethodName)
            .filter { method -> method.matchesTrackedMethod(trackedMethod) }
        matchingMethods.forEach { method ->
            val key = MethodBreakpointKey(referenceType.name(), method.name(), method.signature())
            if (registeredMethodBreakpoints.containsKey(key)) return@forEach

            val location = runCatching { method.location() }.getOrNull()
                ?: runCatching { method.allLineLocations().firstOrNull() }.getOrNull()
                ?: return@forEach
            val request = vm.eventRequestManager().createBreakpointRequest(location)
            request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
            request.putProperty(TRACKED_METHOD_PROPERTY, trackedMethod)
            request.enable()
            registeredMethodBreakpoints[key] = request
        }
    }

    private fun refreshMethodBreakpointsForReferenceTypes(referenceTypes: Collection<ReferenceType>) {
        referenceTypes
            .distinctBy { it.name() }
            .forEach { referenceType ->
                clearMethodBreakpointsForReferenceType(referenceType)
                registerMethodBreakpointsForReferenceType(referenceType)
            }
    }

    private fun clearMethodBreakpointsForReferenceType(referenceType: ReferenceType) {
        val eventRequestManager = vm.eventRequestManager()
        val className = referenceType.name()
        val keysToRemove = registeredMethodBreakpoints.keys
            .filter { it.className == className }
        keysToRemove.forEach { key ->
            registeredMethodBreakpoints.remove(key)?.let { request ->
                runCatching { eventRequestManager.deleteEventRequest(request) }
            }
        }
    }

    private fun trackedClassFqName(trackedMethod: TrackedMethod): String {
        val packageName = trackedMethod.implementationPackageName
        return if (packageName == "<root>" || packageName.isBlank()) {
            trackedMethod.implementationClassName
        } else {
            "$packageName.${trackedMethod.implementationClassName}"
        }
    }

    private fun findTrackedMethod(method: Method): TrackedMethod? {
        val declaringTypeName = method.declaringType().name()
        return trackedMethods.firstOrNull {
            declaringTypeName == trackedClassFqName(it) &&
                    method.name() == it.implementationMethodName &&
                    method.matchesTrackedMethod(it)
        }
    }

    private fun Method.matchesTrackedMethod(trackedMethod: TrackedMethod): Boolean {
        if (name() != trackedMethod.implementationMethodName) return false
        val argumentTypeNames = runCatching { argumentTypeNames() }.getOrNull() ?: return false
        val expectedArgumentTypeNames = trackedMethod.implementationArgumentTypeNames
        if (expectedArgumentTypeNames != null) {
            return argumentTypeNames == expectedArgumentTypeNames
        }
        return argumentTypeNames.size == trackedMethod.implementationArgumentCount
    }

    private suspend fun handleMethodBreakpoint(event: BreakpointEvent) {
        if (shouldAbortProcessing()) return
        val method = event.location().method()
        val trackedMethod = event.request().getProperty(TRACKED_METHOD_PROPERTY) as? TrackedMethod
            ?: findTrackedMethod(method)
            ?: return
        val skipKey = BreakpointSkipKey(
            threadId = event.thread().uniqueID(),
            className = trackedClassFqName(trackedMethod),
            methodName = method.name(),
            methodSignature = method.signature(),
        )
        if (restartedInvocationBreakpointsToSkip.remove(skipKey)) {
            return
        }

        logMethodHit(method.name(), trackedMethod)
        val fromValues = extractArgumentValues(method, event.thread())
        project.kotlinLlmStatsService.recordMethodIntercepted(statsSessionId, trackedMethod, fromValues.size)
        logMethodInterception(method.name(), fromValues)
        // Bridge: record this real invocation so the snapshot orchestration can seed its
        // state from live observed calls (instead of an empty {}).
        runCatching { project.snapshotCaptureService.recordInvocation(trackedMethod, fromValues) }
        val hotReloaded = processTrackedMethod(trackedMethod, fromValues)
        if (hotReloaded) {
            val restarted = restartInterruptedInvocation(event.thread())
            if (restarted) {
                restartedInvocationBreakpointsToSkip.add(skipKey)
            }
        }
    }

    private suspend fun processTrackedMethod(trackedMethod: TrackedMethod, fromValues: List<ActualValue>): Boolean {
        if (shouldAbortProcessing()) return false
        var compilationError: String? = null
        val maxRetries = 5
        var attempt = 0

        while (true) {
            if (shouldAbortProcessing()) return false
            var allModesSucceeded = true
            modes.forEach { mode ->
                if (shouldAbortProcessing()) return false
                val success = mode.onMethodCalled(trackedMethod, fromValues, compilationError)
                if (!success) {
                    allModesSucceeded = false
                }
            }

            if (!allModesSucceeded) {
                project.kotlinLlmStatsService.recordCodeGenerationFailure(
                    statsSessionId,
                    "One or more modes failed to generate an update for ${trackedMethod.implementationClassName}.${trackedMethod.implementationMethodName}"
                )
                edtWriteAction {
                    consoleView.print("KotlinLLM: Code generation failed. Omitting rebuild.\n", ConsoleViewContentType.ERROR_OUTPUT)
                }
                return false
            }

            if (shouldAbortProcessing()) return false
            when (val compilationResult = rebuildAndCheckCompilation(trackedMethod)) {
                is CompilationResult.Success -> {
                    if (shouldAbortProcessing()) return false
                    if (redefineClasses(trackedMethod, compilationResult.classesToRedefine)) {
                        modes.forEach { mode ->
                            mode.onMethodCompilationSucceeded(trackedMethod)
                        }
                        return true
                    }
                    return false
                }
                is CompilationResult.Failure -> {
                    if (attempt < maxRetries) {
                        attempt++
                        compilationError = compilationResult.message.ifBlank { COMPILATION_ERROR_FEEDBACK }

                        edtWriteAction {
                            consoleView.print("KotlinLLM: Compilation failed. Retrying with error feedback... (Attempt $attempt/$maxRetries)\n", ConsoleViewContentType.ERROR_OUTPUT)
                            consoleView.print("${compilationResult.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                        }
                    } else {
                        edtWriteAction {
                            consoleView.print("KotlinLLM: Compilation failed after $maxRetries attempts. Giving up.\n", ConsoleViewContentType.ERROR_OUTPUT)
                            consoleView.print("Final errors:\n${compilationResult.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
                        }
                        return false
                    }
                }
            }
        }
    }

    private suspend fun restartInterruptedInvocation(thread: ThreadReference): Boolean {
        val callerFrame = runCatching {
            if (thread.frameCount() < 2) return false
            thread.frame(1)
        }.getOrNull() ?: return false

        val callerDescription = runCatching {
            val location = callerFrame.location()
            "${location.declaringType().name()}.${location.method().name()}"
        }.getOrDefault("active generated method")

        val restarted = runCatching {
            thread.popFrames(callerFrame)
        }.isSuccess

        if (restarted) {
            project.kotlinLlmStatsService.recordInvocationRestarted(statsSessionId, callerDescription)
            edtWriteAction {
                consoleView.print(
                    "KotlinLLM: Restarted $callerDescription after reload.\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT
                )
            }
        }
        return restarted
    }

    private fun extractArgumentValues(method: Method, thread: ThreadReference): List<ActualValue> {
        val args = method.arguments()

        return args.map { argument ->
            val value = thread.frame(0).getValue(argument)
            val argumentValue = when (value) {
                is StringReference -> value.value()
                else -> value.toString()
            }

            ActualValue(
                argument.name(),
                argument.typeName(),
                argumentValue
            )
        }
    }

    private suspend fun logMethodInterception(methodName: String, fromValues: List<ActualValue>) {
        val argumentsString = fromValues.joinToString(", ") { "${it.name}=${it.value}" }
        val preview = argumentsString.take(METHOD_INTERCEPTION_ARGUMENT_PREVIEW_LIMIT)
        val suffix = if (argumentsString.length > METHOD_INTERCEPTION_ARGUMENT_PREVIEW_LIMIT) "..." else ""
        edtWriteAction {
            consoleView.print(
                "KotlinLLM: Method intercepted - $methodName($preview$suffix)\n",
                ConsoleViewContentType.SYSTEM_OUTPUT
            )
        }
    }

    private suspend fun logMethodHit(methodName: String, trackedMethod: TrackedMethod) {
        edtWriteAction {
            consoleView.print(
                "KotlinLLM: Breakpoint hit - ${trackedMethod.implementationClassName}.$methodName. Capturing arguments...\n",
                ConsoleViewContentType.SYSTEM_OUTPUT
            )
        }
    }

    private suspend fun rebuildAndCheckCompilation(trackedMethod: TrackedMethod): CompilationResult {
        if (shouldAbortProcessing()) return CompilationResult.Failure("Run stopped.")
        val startedNanos = System.nanoTime()
        logBuildProgress("Optimizing generated imports.")
        optimizeGeneratedImports(project)
        logBuildProgress("Committing and saving documents.")
        flushPendingPsiWrites()
        logBuildProgress("Resolving generated source file for ${trackedMethod.implementationClassName}.")
        val sourceFileToRebuild = sourceFileForTrackedMethod(trackedMethod)
        edtWriteAction {
            if (sourceFileToRebuild != null) {
                consoleView.print("KotlinLLM: Compiling updated source file...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            } else {
                consoleView.print("KotlinLLM: Rebuilding project...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
        }
        logBuildProgress("Source selection: ${sourceFileToRebuild ?: "<all modules>"}")

        val buildResult = buildProjectService.buildProject(
            rebuild = false,
            filesToRebuild = sourceFileToRebuild?.let(::listOf),
            progressLogger = ::logBuildProgress
        )
        logBuildProgress("Build service returned: timedOut=${buildResult.timedOut}, success=${buildResult.isSuccess}, problems=${buildResult.problems.size}.")
        if (buildResult.timedOut) {
            val message = "Build timed out."
            project.kotlinLlmStatsService.recordCompilationCompleted(
                statsSessionId,
                success = false,
                durationMs = elapsedMillis(startedNanos, System.nanoTime()),
                message = message,
            )
            return CompilationResult.Failure(message)
        }
        if (shouldAbortProcessing()) {
            return CompilationResult.Failure("Run stopped.")
        }
        if (!buildResult.isSuccess) {
            val message = buildResult.problems.joinToString("\n") { problem ->
                buildString {
                    append(problem.kind)
                    append(": ")
                    append(problem.message)
                    problem.file?.let { file ->
                        append(" [")
                        append(file)
                        problem.line?.let { line ->
                            append(":")
                            append(line)
                            problem.column?.let { column ->
                                append(":")
                                append(column)
                            }
                        }
                        append("]")
                    }
                }
            }.ifBlank {
                "Build failed. Check IntelliJ Build tool window for details."
            }
            project.kotlinLlmStatsService.recordCompilationCompleted(
                statsSessionId,
                success = false,
                durationMs = elapsedMillis(startedNanos, System.nanoTime()),
                message = message,
            )
            return CompilationResult.Failure(message)
        }

        logBuildProgress("Collecting compiled class files for hot reload.")
        val classesToRedefine = collectModifiedClassesForRedefinition(trackedMethod)
        logBuildProgress("Class collection finished: ${classesToRedefine.size} loaded class(es) matched.")
        if (classesToRedefine.isEmpty()) {
            val message = "No modified class files were found for hot reload. " +
                "Checked compiler output roots and fallback output directories."
            project.kotlinLlmStatsService.recordCompilationCompleted(
                statsSessionId,
                success = false,
                durationMs = elapsedMillis(startedNanos, System.nanoTime()),
                message = message,
            )
            return CompilationResult.Failure(message)
        }

        project.kotlinLlmStatsService.recordCompilationCompleted(
            statsSessionId,
            success = true,
            durationMs = elapsedMillis(startedNanos, System.nanoTime()),
            message = "classesToRedefine=${classesToRedefine.size}",
        )
        return CompilationResult.Success(classesToRedefine)
    }

    private fun logBuildProgress(message: String) {
        LOG.info("KotlinLLM build: $message")
        // Surface build/reload progress to the visible console so a hang at any step
        // (VFS refresh, compile, class collection, vm.suspend, redefineClasses,
        // breakpoint refresh) is diagnosable instead of looking frozen at "Compiling...".
        runCatching {
            if (!project.isDisposed) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater({
                    if (!project.isDisposed) {
                        consoleView.print(
                            "KotlinLLM: $message\n",
                            ConsoleViewContentType.SYSTEM_OUTPUT
                        )
                    }
                }, com.intellij.openapi.application.ModalityState.any())
            }
        }
    }

    private suspend fun flushPendingPsiWrites() {
        edtWriteAction {
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            FileDocumentManager.getInstance().saveAllDocuments()
        }
    }

    private fun sourceFileForTrackedMethod(trackedMethod: TrackedMethod): String? {
        val fileName = "${trackedMethod.implementationClassName}.kt"
        return runReadAction {
            findGeneratedSourceFile(project, fileName, trackedMethod.implementationPackageName)
                ?.path
                ?.let { toProjectRelativeKotlinLlmPath(project, Path(it)) }
        }
    }

    private suspend fun redefineClasses(
        trackedMethod: TrackedMethod,
        classesToRedefine: Map<ReferenceType, ByteArray>
    ): Boolean {
        if (shouldAbortProcessing()) return false
        val trackedClassName = trackedClassFqName(trackedMethod)
        val primaryClassesToRedefine = classesToRedefine
            .filterKeys { referenceType -> referenceType.name() == trackedClassName }
            .ifEmpty { classesToRedefine }
        val classNames = primaryClassesToRedefine.keys.joinToString { it.name() }
        val startedNanos = System.nanoTime()
        return runCatching {
            logBuildProgress("Clearing method breakpoints before class redefinition.")
            primaryClassesToRedefine.keys
                .distinctBy { it.name() }
                .forEach { clearMethodBreakpointsForReferenceType(it) }
            logBuildProgress("Suspending VM before class redefinition.")
            vm.suspend()
            try {
                logBuildProgress(
                    "Calling JDI redefineClasses for ${primaryClassesToRedefine.size} primary class(es): $classNames" +
                        if (primaryClassesToRedefine.size != classesToRedefine.size) {
                            " (${classesToRedefine.size - primaryClassesToRedefine.size} helper class(es) omitted)."
                        } else {
                            "."
                        }
                )
                vm.redefineClasses(primaryClassesToRedefine)
                logBuildProgress("JDI redefineClasses returned.")
            } finally {
                logBuildProgress("Resuming VM after class redefinition attempt.")
                vm.resume()
            }
            logBuildProgress("Refreshing method breakpoints.")
            refreshMethodBreakpointsForReferenceTypes(primaryClassesToRedefine.keys)
            logBuildProgress("Method breakpoints refreshed.")
            project.kotlinLlmStatsService.recordHotReloadCompleted(
                statsSessionId,
                success = true,
                classCount = primaryClassesToRedefine.size,
                durationMs = elapsedMillis(startedNanos, System.nanoTime()),
                message = classNames,
            )

            edtWriteAction {
                consoleView.print(
                    "KotlinLLM: Reloaded ${primaryClassesToRedefine.size} class(es) successfully: $classNames\n",
                    ConsoleViewContentType.SYSTEM_OUTPUT
                )
            }
            true
        }.getOrElse { error ->
            if (shouldAbortProcessing()) return false
            logBuildProgress("Class redefinition failed before completion; restoring method breakpoints.")
            refreshMethodBreakpointsForReferenceTypes(primaryClassesToRedefine.keys)
            project.kotlinLlmStatsService.recordHotReloadCompleted(
                statsSessionId,
                success = false,
                classCount = primaryClassesToRedefine.size,
                durationMs = elapsedMillis(startedNanos, System.nanoTime()),
                message = "${error::class.simpleName}: ${error.message ?: "no details"}",
            )
            edtWriteAction {
                consoleView.print(
                    "KotlinLLM: Failed to redefine class(es) [$classNames]: ${error::class.simpleName}: ${error.message ?: "no details"}\n",
                    ConsoleViewContentType.ERROR_OUTPUT
                )
            }
            false
        }
    }

    private fun collectModifiedClassesForRedefinition(
        trackedMethod: TrackedMethod
    ): Map<ReferenceType, ByteArray> {
        val trackedClassName = trackedClassFqName(trackedMethod)
        val outputRoots = collectOutputRoots()
        val packagePath = packagePathFor(trackedMethod.implementationPackageName)
        val expectedClassFile = "${trackedMethod.implementationClassName}.class"
        val expectedInnerClassPrefix = "${trackedMethod.implementationClassName}$"

        logBuildProgress("Output roots: ${outputRoots.joinToString { it.toString() }}")

        val classFilesToRedefine = mutableMapOf<String, NioPath>()

        fun rememberNewestClassFile(qualifiedName: String, classFile: NioPath) {
            if (!isGeneratedClassForTrackedMethod(qualifiedName, trackedClassName)) return
            val existing = classFilesToRedefine[qualifiedName]
            if (existing == null || Files.getLastModifiedTime(classFile) > Files.getLastModifiedTime(existing)) {
                classFilesToRedefine[qualifiedName] = classFile
                logBuildProgress("Matched class file: $qualifiedName -> $classFile")
            }
        }

        outputRoots.filter { it.exists() && it.isDirectory() }.forEach { root ->
            logBuildProgress("Checking output root: $root")
            candidatePackageDirs(root, packagePath).forEach { (effectiveRoot, packageDir) ->
                logBuildProgress("Checking package output directory: $packageDir")
                runCatching {
                    Files.list(packageDir).use { stream ->
                        stream.filter { path ->
                            val fileName = path.fileName?.toString() ?: return@filter false
                            fileName == expectedClassFile ||
                                (fileName.startsWith(expectedInnerClassPrefix) && fileName.endsWith(".class"))
                        }.forEach { classFile ->
                            val qualifiedName = qualifiedNameFromClassFile(effectiveRoot, classFile) ?: return@forEach
                            rememberNewestClassFile(qualifiedName, classFile)
                        }
                    }
                }.onFailure { error ->
                    logBuildProgress("Failed to inspect $packageDir: ${error::class.simpleName}: ${error.message ?: "no details"}")
                }
            }
        }

        val redefineMap = mutableMapOf<ReferenceType, ByteArray>()
        classFilesToRedefine.forEach { (qualifiedName, classFilePath) ->
            logBuildProgress("Looking up loaded VM classes for $qualifiedName.")
            val loadedTypes = vm.classesByName(qualifiedName)
            logBuildProgress("VM classes for $qualifiedName: ${loadedTypes.size}.")
            if (loadedTypes.isEmpty()) return@forEach
            logBuildProgress("Reading class bytes: $classFilePath")
            val bytes = runCatching { classFilePath.readBytes() }.getOrNull() ?: return@forEach
            loadedTypes.forEach { loadedType ->
                redefineMap[loadedType] = bytes
            }
        }

        return redefineMap
    }

    private fun isGeneratedClassForTrackedMethod(
        qualifiedName: String,
        trackedClassName: String
    ): Boolean {
        return qualifiedName == trackedClassName || qualifiedName.startsWith("$trackedClassName$")
    }

    private fun packagePathFor(packageName: String): String {
        val normalizedPackage = packageName.takeUnless { it == "<root>" || it.isBlank() } ?: ""
        return normalizedPackage.replace('.', '/')
    }

    private fun candidatePackageDirs(root: NioPath, packagePath: String): List<Pair<NioPath, NioPath>> {
        val candidates = mutableListOf<Pair<NioPath, NioPath>>()

        fun packageDirFor(effectiveRoot: NioPath): NioPath {
            return if (packagePath.isBlank()) effectiveRoot else effectiveRoot.resolve(packagePath)
        }

        val directPackageDir = packageDirFor(root)
        if (directPackageDir.exists() && directPackageDir.isDirectory()) {
            candidates.add(root to directPackageDir)
        }

        runCatching {
            Files.list(root).use { stream ->
                stream
                    .filter { child -> child.isDirectory() }
                    .map { child -> child to packageDirFor(child) }
                    .filter { (_, packageDir) -> packageDir.exists() && packageDir.isDirectory() }
                    .forEach(candidates::add)
            }
        }

        return candidates.distinctBy { it.second }
    }

    private fun collectOutputRoots(): Set<NioPath> {
        val outputRoots = mutableSetOf<NioPath>()
        ModuleManager.getInstance(project).modules.forEach { module ->
            val compilerExtension = CompilerModuleExtension.getInstance(module)
            compilerExtension?.compilerOutputPath?.let { outputRoots.add(Path(it.path)) }
        }

        val basePath = project.basePath
        if (basePath != null) {
            listOf(
                Path(basePath, "build", "classes", "kotlin", "main"),
                Path(basePath, "build", "classes", "java", "main"),
                Path(basePath, "out", "production"),
                Path(basePath, "out", "production", "classes"),
            ).forEach(outputRoots::add)
        }

        resolveConfiguredBuildsFolderPath(project)?.let { buildsFolder ->
            listOf(
                buildsFolder,
                buildsFolder.resolve("classes").resolve("kotlin").resolve("main"),
                buildsFolder.resolve("classes").resolve("java").resolve("main"),
                buildsFolder.resolve("classes").resolve("kotlin").resolve("test"),
                buildsFolder.resolve("classes").resolve("java").resolve("test"),
            ).forEach(outputRoots::add)
        }

        return outputRoots
    }

    private fun qualifiedNameFromClassFile(root: NioPath, classFile: NioPath): String? {
        return runCatching {
            val relative = root.relativize(classFile).toString().replace('\\', '/')
            if (!relative.endsWith(".class")) return null
            relative.removeSuffix(".class").replace('/', '.')
        }.getOrNull()
    }

    private fun shouldAbortProcessing(): Boolean {
        return runStopped.get() || processHandler.isProcessTerminated || processHandler.isProcessTerminating
    }

}
