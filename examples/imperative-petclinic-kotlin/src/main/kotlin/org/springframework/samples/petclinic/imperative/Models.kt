package org.springframework.samples.petclinic.imperative

import java.time.LocalDate

data class RequestParameters(
    val values: Map<String, List<String>>
)

data class RouteValue(
    val name: String,
    val value: String
)

data class OwnerSearch(
    val lastName: String = ""
)

data class OwnerInput(
    val firstName: String = "",
    val lastName: String = "",
    val address: String = "",
    val city: String = "",
    val telephone: String = ""
)

data class PetFormRequest(
    val parameters: RequestParameters,
    val petTypes: List<PetType>,
    val locale: String
)

data class PetInput(
    val name: String = "",
    val birthDate: LocalDate? = null,
    val typeId: Int? = null
)

data class VisitFormRequest(
    val parameters: RequestParameters,
    val locale: String
)

data class VisitInput(
    val date: LocalDate? = null,
    val description: String = ""
)

data class FieldIssue(
    val field: String,
    val message: String
)

data class Owner(
    val id: Int,
    val firstName: String,
    val lastName: String,
    val address: String,
    val city: String,
    val telephone: String,
    val pets: List<Pet> = emptyList()
)

data class Pet(
    val id: Int,
    val name: String,
    val birthDate: LocalDate?,
    val typeId: Int,
    val typeName: String,
    val ownerId: Int,
    val visits: List<Visit> = emptyList()
)

data class PetType(
    val id: Int,
    val name: String
)

data class Visit(
    val id: Int,
    val petId: Int,
    val date: LocalDate?,
    val description: String
)

data class Vet(
    val id: Int,
    val firstName: String,
    val lastName: String,
    val specialties: List<Specialty> = emptyList()
)

data class Specialty(
    val id: Int,
    val name: String
)

data class VetList(
    val vetList: List<Vet>
)

data class VetJson(
    val content: String
)

data class VetXml(
    val content: String
)
