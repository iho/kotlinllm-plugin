package com.jetbrains.kotlinllm.kotlinllmplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

@Service(Service.Level.PROJECT)
internal class KotlinLlmJdiService : Disposable {
    val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runInProgress: AtomicBoolean = AtomicBoolean(false)
    val codegenWriteMutex: Mutex = Mutex()

    override fun dispose() {
        coroutineScope.cancel()
    }
}

internal val Project.kotlinLlmCoroutineScope: CoroutineScope
    get() = service<KotlinLlmJdiService>().coroutineScope

internal val Project.kotlinLlmRunInProgress: AtomicBoolean
    get() = service<KotlinLlmJdiService>().runInProgress

internal val Project.kotlinLlmCodegenWriteMutex: Mutex
    get() = service<KotlinLlmJdiService>().codegenWriteMutex
