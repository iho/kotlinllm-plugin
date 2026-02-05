package com.jetbrains.kotlinllm.kotlinllmplugin.execute

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.ConfigurationInfoProvider
import com.intellij.execution.configurations.JavaCommandLine
import com.intellij.execution.configurations.JavaCommandLineState
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.impl.DefaultJavaProgramRunner
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.RunConfigurationWithSuppressedDefaultRunAction
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.util.Key
import com.jetbrains.kotlinllm.kotlinllmplugin.actions.createKotlinLlmBootstrapFiles
import com.jetbrains.kotlinllm.kotlinllmplugin.actions.promptForKotlinLlmInitializationLater
import com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template.optimizeGeneratedImports
import com.jetbrains.kotlinllm.kotlinllmplugin.jdi.JdiLauncher
import com.jetbrains.kotlinllm.kotlinllmplugin.loop.KotlinLlmLoop
import com.jetbrains.kotlinllm.kotlinllmplugin.modes.AsLlmMode
import com.jetbrains.kotlinllm.kotlinllmplugin.modes.MockLlmMode
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmCoroutineScope
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmRunInProgress
import com.jetbrains.kotlinllm.kotlinllmplugin.services.kotlinLlmStatsService
import com.jetbrains.kotlinllm.kotlinllmplugin.services.resolveConfiguredBuildsFolderPath
import com.jetbrains.kotlinllm.kotlinllmplugin.services.resolveConfiguredKotlinLlmFolder
import com.jetbrains.kotlinllm.kotlinllmplugin.services.saveConfiguredKotlinLlmFolder
import com.jetbrains.kotlinllm.kotlinllmplugin.services.toProjectRelativeKotlinLlmPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

const val KOTLIN_LLM_RUNNER_ID = "KotlinLlmRunner"

class KotlinLlmRunner : DefaultJavaProgramRunner() {
    override fun getRunnerId(): String = KOTLIN_LLM_RUNNER_ID

    override fun createConfigurationData(settingsProvider: ConfigurationInfoProvider): RunnerSettings = KotlinLlmRunnerData()

    override fun canRun(
        executorId: String,
        profile: RunProfile
    ): Boolean {
        return try {
            executorId == KOTLIN_LLM_EXECUTOR_ID &&
                    profile !is RunConfigurationWithSuppressedDefaultRunAction &&
                    profile is RunConfigurationBase<*> &&
                    profile.createsJavaCommandLineState()
        } catch (_: Exception) {
            false
        }
    }

    override fun execute(environment: ExecutionEnvironment) {
        val project = environment.project
        val state = environment.state as? JavaCommandLineState ?: throw IllegalStateException(
            "Cannot run configuration '${environment.runProfile.name}' with KotlinLLM: " +
                    "configuration does not produce a JavaCommandLineState. " +
                    "This configuration type may not be compatible with Kotlin Inline Prompts."
        )

        val executor = environment.executor

        val consoleView = TextConsoleBuilderFactory.getInstance()
            .createBuilder(project)
            .console

        if (!project.kotlinLlmRunInProgress.compareAndSet(false, true)) {
            consoleView.print(
                "KotlinLLM: A run session is already active for this project. Stop it before starting a new one.\n",
                ConsoleViewContentType.ERROR_OUTPUT
            )
            environment.callback?.processNotStarted(null)
            return
        }

        project.kotlinLlmCoroutineScope.launch {
            val runJob = currentCoroutineContext()[Job]
            val statsService = project.kotlinLlmStatsService
            val statsSessionId = statsService.startSession(environment.runProfile.name)
            var statsStatus = "completed"
            var statsError: String? = null
            var callbackStarted = false
            try {
                val configuredKotlinLlmFolder = resolveConfiguredKotlinLlmFolder(project)
                val configuredBuildsFolder = resolveConfiguredBuildsFolderPath(project)
                if (configuredKotlinLlmFolder == null || configuredBuildsFolder == null) {
                    val selectedFolder = promptForKotlinLlmInitializationLater(project, triggeredByRun = true)
                    if (selectedFolder == null) {
                        statsStatus = "cancelled"
                        statsError = "Project is missing required KotlinLLM settings"
                        edtWriteAction {
                            consoleView.print(
                                """
                                KotlinLLM: Run cancelled because this project is missing required KotlinLLM settings.
                                KotlinLLM needs a generated Kotlin source folder and a builds folder before it can run:
                                  - .kotlinllm stores the selected source root
                                  - .kotlinllm stores the builds folder used for compiled class discovery
                                  - com.jetbrains.kotlinllm.generated.core contains bootstrap/provider files
                                  - those files let KotlinLLM route asLlm/mockLlm calls and hot-reload generated code

                                Run Tools > KotlinLLM Settings, or start a KotlinLLM run again and choose both folders.

                                """.trimIndent(),
                                ConsoleViewContentType.ERROR_OUTPUT
                            )
                        }
                        environment.callback?.processNotStarted(null)
                        return@launch
                    }
                    if (configuredKotlinLlmFolder == null) {
                        saveConfiguredKotlinLlmFolder(project, selectedFolder)
                        createKotlinLlmBootstrapFiles(project)
                        edtWriteAction {
                            consoleView.print(
                                "KotlinLLM: Initialized generated files under ${toProjectRelativeKotlinLlmPath(project, java.nio.file.Path.of(selectedFolder.path))}/com/jetbrains/kotlinllm/generated/core.\n",
                                ConsoleViewContentType.SYSTEM_OUTPUT
                            )
                        }
                    }
                }
                val agentStatusSink: (String) -> Unit = { message ->
                    ApplicationManager.getApplication().invokeLater(
                        {
                            if (!project.isDisposed) {
                                consoleView.print(
                                    "KotlinLLM Agent: $message\n",
                                    ConsoleViewContentType.SYSTEM_OUTPUT
                                )
                            }
                        },
                        ModalityState.any()
                    )
                }
                val asLlmMode = AsLlmMode(project, agentStatusSink)
                val mockLlmMode = MockLlmMode(project, agentStatusSink)
                val asLlmTrackedMethods = asLlmMode.setup()
                statsService.recordModeSetup(statsSessionId, "asLlm", asLlmTrackedMethods.size)
                val mockLlmTrackedMethods = mockLlmMode.setup()
                statsService.recordModeSetup(statsSessionId, "mockLlm", mockLlmTrackedMethods.size)
                optimizeGeneratedImports(project)

                val beforeLaunchOk = runBeforeLaunchTasks(project, environment, consoleView)
                if (!beforeLaunchOk) {
                    statsStatus = "failed"
                    statsError = "A before-launch task failed"
                    edtWriteAction {
                        consoleView.print(
                            "KotlinLLM: A before-launch task failed. Aborting run.\n",
                            ConsoleViewContentType.ERROR_OUTPUT
                        )
                    }
                    environment.callback?.processNotStarted(null)
                    return@launch
                }
                val jdiLauncher = JdiLauncher(state)
                val vm = jdiLauncher.launchVirtualMachine()
                val processHandler = KotlinLlmProcessHandler(vm)

                val executionResult = DefaultExecutionResult(consoleView, processHandler)

                consoleView.attachToProcess(processHandler)
                edtWriteAction {
                    val descriptor = RunContentDescriptor(
                        executionResult.executionConsole,
                        processHandler,
                        executionResult.executionConsole.component,
                        environment.runProfile.name
                    )
                    RunContentManager.getInstance(project).showRunContent(executor, descriptor)
                    callbackStarted = true
                    environment.callback?.processStarted(descriptor)
                }

                processHandler.startNotify()
                processHandler.addProcessListener(object : ProcessListener {
                    override fun startNotified(event: ProcessEvent) = Unit

                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = Unit

                    override fun processWillTerminate(event: ProcessEvent, willBeDestroyed: Boolean) {
                        statsStatus = "stopped"
                        runJob?.cancel(CancellationException("KotlinLLM run stopped by user"))
                    }

                    override fun processTerminated(event: ProcessEvent) {
                        statsStatus = "stopped"
                        runJob?.cancel(CancellationException("KotlinLLM run terminated"))
                    }
                })

                val kotlinLlmLoop = KotlinLlmLoop(project, vm, processHandler, consoleView, jdiLauncher, statsSessionId)
                kotlinLlmLoop.registerPreparedMode(asLlmMode, asLlmTrackedMethods)
                kotlinLlmLoop.registerPreparedMode(mockLlmMode, mockLlmTrackedMethods)

                edtWriteAction {
                    consoleView.print("KotlinLLM: Ready. Monitoring for asLlm and mockLlm calls...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
                }

                kotlinLlmLoop.start()
            } catch (error: Throwable) {
                if (!callbackStarted) {
                    environment.callback?.processNotStarted(error)
                }
                if (error is CancellationException) {
                    if (statsStatus == "completed") {
                        statsStatus = "stopped"
                    }
                    statsError = error.message
                    return@launch
                }
                statsStatus = "failed"
                statsError = "${error::class.simpleName}: ${error.message ?: "no details"}"
                edtWriteAction {
                    consoleView.print(
                        "KotlinLLM: Run failed: ${error::class.simpleName}: ${error.message ?: "no details"}\n",
                        ConsoleViewContentType.ERROR_OUTPUT
                    )
                }
            } finally {
                statsService.finishSession(statsSessionId, statsStatus, statsError)
                project.kotlinLlmRunInProgress.set(false)
            }
        }
    }

    private fun RunConfiguration.createsJavaCommandLineState(): Boolean {
        val executor = getKotlinLlmExecutorInstance() ?: return false
        val environment = ExecutionEnvironmentBuilder(project, executor)
            .runProfile(this)
            .runner(this@KotlinLlmRunner)
            .runnerSettings(KotlinLlmRunnerData())
            .build()
        return environment.state is JavaCommandLine
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun runBeforeLaunchTasks(
        project: com.intellij.openapi.project.Project,
        environment: ExecutionEnvironment,
        consoleView: com.intellij.execution.ui.ConsoleView
    ): Boolean {
        val runConfiguration = environment.runProfile as? RunConfigurationBase<*> ?: return true
        val enabledTasks = runConfiguration.beforeRunTasks.filter { it.isEnabled }
        if (enabledTasks.isEmpty()) return true

        for (task in enabledTasks) {
            val provider = BeforeRunTaskProvider.getProvider(project, task.providerId)
                as? BeforeRunTaskProvider<BeforeRunTask<*>>
                ?: continue

            val canExecute = runCatching { provider.canExecuteTask(runConfiguration, task) }.getOrDefault(false)
            if (!canExecute) {
                edtWriteAction {
                    consoleView.print(
                        "KotlinLLM: Omitting non-executable before-launch task '${provider.name}'.\n",
                        ConsoleViewContentType.SYSTEM_OUTPUT
                    )
                }
                continue
            }

            edtWriteAction {
                consoleView.print("KotlinLLM: Running before-launch task '${provider.name}'...\n", ConsoleViewContentType.SYSTEM_OUTPUT)
            }
            val ok = runCatching {
                provider.executeTask(DataContext.EMPTY_CONTEXT, runConfiguration, environment, task)
            }.getOrDefault(false)
            if (!ok) {
                return false
            }
        }
        return true
    }
}
