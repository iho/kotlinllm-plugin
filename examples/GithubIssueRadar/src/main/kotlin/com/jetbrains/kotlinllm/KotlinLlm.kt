/*
 * Copyright 2014-2026 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license.
 */

package com.jetbrains.kotlinllm

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.reflect.KType
import kotlin.reflect.typeOf

public interface AsLlmParser<F, T> {
    public fun parse(from: F, hint: String = ""): T
}

public fun interface AsLlmProvider {
    public fun resolve(fromType: KType, toType: KType): AsLlmParser<*, *>?
}

public fun interface MockLlmProvider {
    public fun resolve(referenceType: KType): Any?
}

public object AsLlmManager {
    private val bootstrapped = AtomicBoolean(false)

    @Volatile
    private var provider: AsLlmProvider = AsLlmProvider { _, _ -> null }

    public fun install(provider: AsLlmProvider) {
        this.provider = provider
    }

    public fun resolve(fromType: KType, toType: KType): AsLlmParser<*, *>? {
        ensureBootstrapLoaded()
        return provider.resolve(fromType, toType)
    }

    private fun ensureBootstrapLoaded() {
        if (bootstrapped.compareAndSet(false, true)) {
            runCatching { Class.forName("com.jetbrains.kotlinllm.generated.core.KotlinLlmBootstrap") }
        }
    }
}

public object MockLlmManager {
    private val bootstrapped = AtomicBoolean(false)

    @Volatile
    private var provider: MockLlmProvider = MockLlmProvider { _ -> null }

    public fun install(provider: MockLlmProvider) {
        this.provider = provider
    }

    public fun resolve(referenceType: KType): Any? {
        ensureBootstrapLoaded()
        return provider.resolve(referenceType)
    }

    private fun ensureBootstrapLoaded() {
        if (bootstrapped.compareAndSet(false, true)) {
            runCatching { Class.forName("com.jetbrains.kotlinllm.generated.core.KotlinLlmBootstrap") }
        }
    }
}

@Suppress("UNCHECKED_CAST")
public inline fun <reified F, reified T> asLlm(from: F, hint: String = ""): T {
    val parser = AsLlmManager.resolve(typeOf<F>(), typeOf<T>()) as? AsLlmParser<F, T>
        ?: error("No asLlm parser for ${typeOf<F>()} -> ${typeOf<T>()}")
    return parser.parse(from, hint)
}

@Suppress("UNCHECKED_CAST")
public inline fun <reified T> mockLlm(): T {
    return (MockLlmManager.resolve(typeOf<T>())) as T
}
