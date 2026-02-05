package com.jetbrains.kotlinllm.kotlinllmplugin.execute

import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings

class KotlinLlmRunConfigurationExtension : RunConfigurationExtension() {
    override fun <T : RunConfigurationBase<*>> updateJavaParameters(
        configuration: T,
        params: JavaParameters,
        runnerSettings: RunnerSettings?
    ) {
        if (runnerSettings !is KotlinLlmRunnerData) {
            return
        }
    }

    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean {
        return true
    }

    override fun isEnabledFor(configuration: RunConfigurationBase<*>, runnerSettings: RunnerSettings?): Boolean {
        return runnerSettings is KotlinLlmRunnerData
    }

    override fun getSerializationId(): String = "kotlinllm"
}
