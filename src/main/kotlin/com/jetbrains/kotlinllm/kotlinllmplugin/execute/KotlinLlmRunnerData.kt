package com.jetbrains.kotlinllm.kotlinllmplugin.execute

import com.intellij.execution.configurations.RunnerSettings
import org.jdom.Element

class KotlinLlmRunnerData : RunnerSettings {
    override fun readExternal(element: Element) = Unit

    override fun writeExternal(element: Element) = Unit
}
