package com.jetbrains.kotlinllm.kotlinllmplugin.codegen.template

import com.jetbrains.kotlinllm.kotlinllmplugin.models.MockedInterface

/**
 * Interface for registering generated classes in a routing mechanism.
 * Implementations typically update source files to route type conversions to generated classes.
 */
interface ClassRouter {
    /**
     * Registers a generated class for the given interface.
     * @param mockedInterface The interface that was implemented
     * @param className The name of the generated implementation class
     */
    suspend fun register(mockedInterface: MockedInterface, className: String = mockedInterface.name)

    suspend fun commitRegistrations()
}
