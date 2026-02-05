package com.jetbrains.kotlinllm.kotlinllmplugin.execute

import com.intellij.execution.Executor
import com.intellij.execution.ExecutorRegistry
import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.wm.ToolWindowId
import javax.swing.Icon

const val KOTLIN_LLM_EXECUTOR_ID = "KotlinLlmExecutor"

/**
 * Custom executor that enables running any Kotlin configuration with KotlinLLM.
 * Supports Spring configurations, Plugin configurations, Application configurations, and more.
 * This executor appears in the main toolbar next to Run and Debug buttons.
 */
class KotlinLlmExecutor : Executor() {

    override fun getToolWindowId(): String = ToolWindowId.RUN

    override fun getToolWindowIcon(): Icon = icon

    override fun getIcon(): Icon = kotlinLlmIcon

    override fun getDisabledIcon(): Icon = AllIcons.Actions.Lightning

    override fun getDescription(): String = "Run with KotlinLLM - enables asLlm and mockLlm runtime code generation"

    override fun getActionName(): String = "Run with KotlinLLM"

    override fun getId(): String = KOTLIN_LLM_EXECUTOR_ID

    override fun getStartActionText(): String = "Run with KotlinLLM"

    override fun getStartActionText(configurationName: String): String = "Run '$configurationName' with KotlinLLM"

    override fun getContextActionId(): String = "RunClassWithKotlinLlm"

    override fun getHelpId(): String? = null

    private val kotlinLlmIcon: Icon
        get() = try {
            // Try to load custom icon, fall back to default lightning icon
            IconLoader.findIcon("/icons/kotlinllm.svg", KotlinLlmExecutor::class.java) ?: AllIcons.Actions.Lightning
        } catch (e: Exception) {
            AllIcons.Actions.Lightning
        }
}

fun getKotlinLlmExecutorInstance(): KotlinLlmExecutor? {
    return ExecutorRegistry.getInstance().getExecutorById(KOTLIN_LLM_EXECUTOR_ID) as? KotlinLlmExecutor
}
