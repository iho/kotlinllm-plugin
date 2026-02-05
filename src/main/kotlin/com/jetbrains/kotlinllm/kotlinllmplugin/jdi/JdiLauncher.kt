package com.jetbrains.kotlinllm.kotlinllmplugin.jdi

import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.edtWriteAction
import com.intellij.util.io.awaitExit
import com.jetbrains.kotlinllm.kotlinllmplugin.execute.KotlinLlmProcessHandler
import com.sun.jdi.Bootstrap
import com.sun.jdi.VirtualMachine
import com.sun.jdi.event.BreakpointEvent
import com.sun.jdi.event.ClassPrepareEvent
import com.sun.jdi.event.VMDeathEvent
import com.sun.jdi.event.VMDisconnectEvent
import com.sun.jdi.request.EventRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.file.Path

class JdiLauncher(
    private val state: JavaCommandLineState
) {

    fun launchVirtualMachine(): VirtualMachine {
        val parameters = state.javaParameters
        val classpath = parameters.classPath.pathsString
        val mainClass = parameters.mainClass ?: throw IllegalStateException("No main class specified")
        val userDirRaw = parameters.workingDirectory ?: throw IllegalStateException("No working directory specified")
        val userDir = runCatching { Path.of(userDirRaw).normalize().toString() }.getOrDefault(userDirRaw)
        val isGradleLauncher = mainClass == "org.gradle.launcher.GradleMain"
        val vmArgs = parameters.vmParametersList.parametersString.trim()
        val programArgs = parameters.programParametersList.parametersString.trim()

        val virtualMachineManager = Bootstrap.virtualMachineManager()
        val launchingConnector = virtualMachineManager.defaultConnector()

        val env = launchingConnector.defaultArguments().apply {
            val options = buildString {
                if (vmArgs.isNotBlank()) {
                    append(vmArgs)
                    append(' ')
                }
                append("-XX:+AllowEnhancedClassRedefinition ")
                append("-cp ")
                append(classpath)
                append(' ')
                append("-Duser.dir=$userDir")
            }
            this["options"]?.setValue(options)
            this["main"]?.setValue(
                if (programArgs.isBlank() || isGradleLauncher) mainClass else "$mainClass $programArgs"
            )
        }

        return launchingConnector.launch(env)
    }

    suspend fun launch(
        vm: VirtualMachine,
        processHandler: ProcessHandler,
        consoleView: ConsoleView,
        onClassPrepare: suspend (ClassPrepareEvent) -> Unit = {},
        onBreakpoint: suspend (BreakpointEvent) -> Unit = {},
    ) = coroutineScope {
        val eventQueue = vm.eventQueue()
        val eventRequestManager = vm.eventRequestManager()
        val osProcess = vm.process()

        val request = eventRequestManager.createClassPrepareRequest()
        request.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD)
        request.enable()

        val osProcessWatcher = launch {
            val exitCode = runCatching { osProcess?.awaitExit() }.getOrNull() ?: return@launch
            markTerminated(processHandler, exitCode)
            runCatching { vm.dispose() }
        }

        try {
            var shouldStop = false
            while (!shouldStop && currentCoroutineContext().isActive && !processHandler.isProcessTerminated) {
                if (osProcess != null && !osProcess.isAlive) {
                    markTerminated(processHandler, runCatching { osProcess.exitValue() }.getOrDefault(0))
                    break
                }
                val eventSet = eventQueue.remove(250) ?: continue
                try {
                    for (event in eventSet) {
                        when (event) {
                            is VMDisconnectEvent, is VMDeathEvent -> {
                                val exitCode = vm.process()?.awaitExit() ?: 0
                                markTerminated(processHandler, exitCode)
                                shouldStop = true
                                break
                            }

                            is ClassPrepareEvent -> {
                                onClassPrepare(event)
                            }

                            is BreakpointEvent -> {
                                val method = event.location().method()
                                if (method.isConstructor) continue
                                if (method.isStaticInitializer) continue

                                onBreakpoint(event)
                            }

                        }
                    }
                } finally {
                    runCatching { eventSet.resume() }
                }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) {
                return@coroutineScope
            }
            edtWriteAction {
                consoleView.print("Error in event loop: ${error.message}\n", ConsoleViewContentType.ERROR_OUTPUT)
            }
            markTerminated(processHandler, 1)
        } finally {
            osProcessWatcher.cancel()
        }
    }

    private fun markTerminated(processHandler: ProcessHandler, exitCode: Int) {
        if (processHandler is KotlinLlmProcessHandler) {
            processHandler.notifyTerminated(exitCode)
        }
    }
}
