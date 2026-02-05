package org.springframework.samples.petclinic.imperative

import com.jetbrains.kotlinllm.asLlm
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.function.ServerRequest
import org.springframework.web.servlet.function.ServerResponse
import java.time.LocalDate

@Component
class PetclinicHandler(private val store: PetclinicStore) {

    fun home(request: ServerRequest): ServerResponse =
        html(
            "Petclinic",
            """
            <h1>Petclinic</h1>
            <nav class="actions">
              <a href="/owners/find">Find owners</a>
              <a href="/owners/new">New owner</a>
              <a href="/vets.html">Veterinarians</a>
              <a href="/h2-console">H2 console</a>
            </nav>
            """.trimIndent()
        )

    fun findOwnersForm(request: ServerRequest): ServerResponse =
        html("Find Owners", ownerSearchForm())

    fun findOwners(request: ServerRequest): ServerResponse {
        val query = asLlm<RequestParameters, OwnerSearch>(
            request.parameters(),
            "Parse owner search request parameters. lastName is optional."
        )
        val owners = store.findOwners(query.lastName)
        if (owners.size == 1) {
            return redirect("/owners/${owners.first().id}")
        }

        val body = when {
            owners.isEmpty() -> ownerSearchForm("No owners found for '${escape(query.lastName)}'.")
            else -> ownersTable(owners)
        }
        return html("Owners", body)
    }

    fun newOwnerForm(request: ServerRequest): ServerResponse =
        html("New Owner", ownerForm("/owners/new", OwnerInput()))

    fun createOwner(request: ServerRequest): ServerResponse {
        val parameters = request.parameters()
        val input = asLlm<RequestParameters, OwnerInput>(
            parameters,
            "Parse submitted owner form fields."
        )
        val validation = asLlm<RequestParameters, List<FieldIssue>>(
            parameters,
            "Validate submitted owner form fields. firstName, lastName, address, city, and telephone are required. telephone must contain at most 10 digits."
        )
        if (validation.isNotEmpty()) {
            return html("New Owner", ownerForm("/owners/new", input, validation))
        }

        val owner = store.createOwner(input)
        return redirect("/owners/${owner.id}")
    }

    fun ownerDetails(request: ServerRequest): ServerResponse {
        val ownerId = request.pathInt("ownerId")
        val owner = store.owner(ownerId)
        return html("Owner", ownerSummary(owner))
    }

    fun editOwnerForm(request: ServerRequest): ServerResponse {
        val ownerId = request.pathInt("ownerId")
        val owner = store.owner(ownerId)
        return html(
            "Edit Owner",
            ownerForm(
                "/owners/$ownerId/edit",
                asLlm<Owner, OwnerInput>(owner, "Represent owner data as editable owner form input.")
            )
        )
    }

    fun updateOwner(request: ServerRequest): ServerResponse {
        val ownerId = request.pathInt("ownerId")
        val parameters = request.parameters()
        val input = asLlm<RequestParameters, OwnerInput>(
            parameters,
            "Parse submitted owner form fields."
        )
        val validation = asLlm<RequestParameters, List<FieldIssue>>(
            parameters,
            "Validate submitted owner form fields. firstName, lastName, address, city, and telephone are required. telephone must contain at most 10 digits."
        )
        if (validation.isNotEmpty()) {
            return html("Edit Owner", ownerForm("/owners/$ownerId/edit", input, validation))
        }

        store.updateOwner(ownerId, input)
        return redirect("/owners/$ownerId")
    }

    fun newPetForm(request: ServerRequest): ServerResponse {
        val ownerId = request.pathInt("ownerId")
        return html("New Pet", petForm("/owners/$ownerId/pets/new", PetInput(), store.petTypes()))
    }

    fun createPet(request: ServerRequest): ServerResponse {
        val ownerId = request.pathInt("ownerId")
        val formRequest = PetFormRequest(request.parameters(), store.petTypes(), request.locale())
        val input = asLlm<PetFormRequest, PetInput>(
            formRequest,
            "Parse submitted pet form fields. Resolve type from available pet types and parse birthDate."
        )
        val validation = asLlm<PetFormRequest, List<FieldIssue>>(
            formRequest,
            "Validate submitted pet form fields. name, birthDate, and type are required."
        )
        if (validation.isNotEmpty()) {
            return html("New Pet", petForm("/owners/$ownerId/pets/new", input, store.petTypes(), validation))
        }

        store.createPet(ownerId, input)
        return redirect("/owners/$ownerId")
    }

    fun editPetForm(request: ServerRequest): ServerResponse {
        val ownerId = request.pathInt("ownerId")
        val petId = request.pathInt("petId")
        val pet = store.pet(petId)
        return html(
            "Edit Pet",
            petForm(
                "/owners/$ownerId/pets/$petId/edit",
                asLlm<Pet, PetInput>(pet, "Represent pet data as editable pet form input."),
                store.petTypes()
            )
        )
    }

    fun updatePet(request: ServerRequest): ServerResponse {
        val ownerId = request.pathInt("ownerId")
        val petId = request.pathInt("petId")
        val formRequest = PetFormRequest(request.parameters(), store.petTypes(), request.locale())
        val input = asLlm<PetFormRequest, PetInput>(
            formRequest,
            "Parse submitted pet form fields. Resolve type from available pet types and parse birthDate."
        )
        val validation = asLlm<PetFormRequest, List<FieldIssue>>(
            formRequest,
            "Validate submitted pet form fields. name, birthDate, and type are required."
        )
        if (validation.isNotEmpty()) {
            return html("Edit Pet", petForm("/owners/$ownerId/pets/$petId/edit", input, store.petTypes(), validation))
        }

        store.updatePet(petId, input)
        return redirect("/owners/$ownerId")
    }

    fun newVisitForm(request: ServerRequest): ServerResponse {
        val ownerId = request.pathInt("ownerId")
        val petId = request.pathInt("petId")
        val pet = store.pet(petId)
        return html("New Visit", visitForm("/owners/$ownerId/pets/$petId/visits/new", pet, VisitInput(date = LocalDate.now())))
    }

    fun createVisit(request: ServerRequest): ServerResponse {
        val ownerId = request.pathInt("ownerId")
        val petId = request.pathInt("petId")
        val formRequest = VisitFormRequest(request.parameters(), request.locale())
        val input = asLlm<VisitFormRequest, VisitInput>(
            formRequest,
            "Parse submitted visit form fields. Parse date and description."
        )
        val validation = asLlm<VisitFormRequest, List<FieldIssue>>(
            formRequest,
            "Validate submitted visit form fields. date and description are required."
        )
        if (validation.isNotEmpty()) {
            return html("New Visit", visitForm("/owners/$ownerId/pets/$petId/visits/new", store.pet(petId), input, validation))
        }

        store.createVisit(petId, input)
        return redirect("/owners/$ownerId")
    }

    fun vetsHtml(request: ServerRequest): ServerResponse =
        html("Veterinarians", vetsTable(store.vets()))

    fun vetsJson(request: ServerRequest): ServerResponse =
        ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .body(asLlm<List<Vet>, VetJson>(
                store.vets(),
                "Render veterinarian rows as JSON response content.",
            ).content)

    fun vetsXml(request: ServerRequest): ServerResponse =
        ServerResponse.ok()
            .contentType(MediaType.APPLICATION_XML)
            .body(asLlm<List<Vet>, VetXml>(store.vets(), "Render veterinarian rows as XML response content.").content)

    fun crash(request: ServerRequest): ServerResponse {
        throw RuntimeException("Expected: controller used to showcase what happens when an exception is thrown")
    }
}
