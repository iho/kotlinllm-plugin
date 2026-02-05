package org.springframework.samples.petclinic.imperative

import com.jetbrains.kotlinllm.mockLlm
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.web.servlet.function.RouterFunction
import org.springframework.web.servlet.function.RouterFunctions
import org.springframework.web.servlet.function.ServerResponse
import kotlin.reflect.typeOf

@SpringBootApplication(proxyBeanMethods = false)
class ImperativePetclinicApplication {

    @Bean
    fun routes(handler: PetclinicHandler): RouterFunction<ServerResponse> =
        RouterFunctions.route()
            .GET("/", handler::home)
            .GET("/owners/find", handler::findOwnersForm)
            .GET("/owners", handler::findOwners)
            .GET("/owners/new", handler::newOwnerForm)
            .POST("/owners/new", handler::createOwner)
            .GET("/owners/{ownerId}", handler::ownerDetails)
            .GET("/owners/{ownerId}/edit", handler::editOwnerForm)
            .POST("/owners/{ownerId}/edit", handler::updateOwner)
            .GET("/owners/{ownerId}/pets/new", handler::newPetForm)
            .POST("/owners/{ownerId}/pets/new", handler::createPet)
            .GET("/owners/{ownerId}/pets/{petId}/edit", handler::editPetForm)
            .POST("/owners/{ownerId}/pets/{petId}/edit", handler::updatePet)
            .GET("/owners/{ownerId}/pets/{petId}/visits/new", handler::newVisitForm)
            .POST("/owners/{ownerId}/pets/{petId}/visits/new", handler::createVisit)
            .GET("/vets", handler::vetsJson)
            .GET("/vets.json", handler::vetsJson)
            .GET("/vets.xml", handler::vetsXml)
            .GET("/vets.html", handler::vetsHtml)
            .GET("/oups", handler::crash)
            .build()
}

data class AppUser(val id: Int, val name: String)
interface UserRegistry {
    fun add(user: AppUser)
    fun findById(id: Int): AppUser?
    fun delete(user: AppUser)
    fun getAll(): List<AppUser>
}

fun main(args: Array<String>) {
    val userRegistry = mockLlm<UserRegistry>()
    userRegistry.add(AppUser(1, "John"))
    println(userRegistry.getAll())
    val toDelete = userRegistry.findById(1)
    if(toDelete != null) userRegistry.delete(toDelete)
    val anotherUser = userRegistry.findById(1)
    if(anotherUser != null) userRegistry.add(anotherUser)
    println(userRegistry.getAll())
//    runApplication<ImperativePetclinicApplication>(*args)
}
