package com.jetbrains.kotlinllm.kotlinllmplugin.modes

import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualType
import com.jetbrains.kotlinllm.kotlinllmplugin.models.ActualValue
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedInterface
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedMethod
import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedVariable

/**
 * Interface for implementing different LLM-based code generation modes.
 * Each mode defines how to set up tracked methods and handle method calls.
 */
interface KotlinLlmMode {
    /**
     * Sets up the mode by generating necessary classes and returning methods to track.
     * @return Set of methods that should be intercepted at runtime
     */
    suspend fun setup(): Set<TrackedMethod>

    /**
     * Called when a tracked method is intercepted at runtime.
     * @param trackedMethod The method that was intercepted
     * @param actualValues The actual argument values from the method call
     * @param compilationError Optional compilation error from previous attempt
     * @return true if generation succeeded (syntactically valid), false otherwise
     */
    suspend fun onMethodCalled(
        trackedMethod: TrackedMethod,
        actualValues: List<ActualValue>,
        compilationError: String? = null
    ): Boolean

    /**
     * Called after a generated implementation compiles and is accepted for hot reload.
     * Modes should commit any pending in-memory generation state here, not after PSI syntax update.
     */
    suspend fun onMethodCompilationSucceeded(trackedMethod: TrackedMethod) = Unit
}

/**
 * Represents a method that is being tracked for runtime interception and code generation.
 * @property mockedMethod The original method definition
 * @property returnType The actual return type after type parameter resolution
 * @property implementationClassName The name of the generated implementation class
 * @property implementationMethodName The name of the method in the implementation class
 * @property implementationArgumentCount The number of arguments accepted by the generated implementation method
 * @property implementationArgumentTypeNames The JVM argument type names accepted by the generated implementation method
 * @property mockedInterface The interface containing this method (optional, for context)
 * @property mockedProperty The original property definition when the tracked method is a generated property getter
 */
data class TrackedMethod(
    val mockedMethod: MockedMethod,
    val returnType: ActualType,
    val implementationClassName: String,
    val implementationPackageName: String,
    val implementationMethodName: String,
    val implementationArgumentCount: Int = mockedMethod.parameters.size,
    val implementationArgumentTypeNames: List<String>? = null,
    val mockedInterface: MockedInterface? = null,
    val mockedProperty: MockedVariable? = null,
)
