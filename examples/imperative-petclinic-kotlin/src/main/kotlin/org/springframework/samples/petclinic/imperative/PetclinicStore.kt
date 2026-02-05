package org.springframework.samples.petclinic.imperative

import com.jetbrains.kotlinllm.asLlm
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Component

@Component
class PetclinicStore(private val jdbc: JdbcTemplate) {

    fun petTypes(): List<PetType> =
        asLlm<List<Map<String, Any?>>, List<PetType>>(
            jdbc.queryForList("select id, name from types order by name"),
            "Represent pet type database rows as data classes."
        )

    fun findOwners(lastName: String): List<Owner> =
        ownersByRows(
            jdbc.queryForList(
                "select id, first_name, last_name, address, city, telephone from owners where last_name like ? order by last_name, first_name",
                "$lastName%"
            )
        )

    fun owner(ownerId: Int): Owner {
        val owner = ownersByRows(
            jdbc.queryForList(
                "select id, first_name, last_name, address, city, telephone from owners where id = ?",
                ownerId
            )
        ).first()
        val pets = petsForOwner(ownerId)
        return owner.copy(pets = pets)
    }

    fun createOwner(input: OwnerInput): Owner {
        val key = GeneratedKeyHolder()
        jdbc.update({ connection ->
            val statement = connection.prepareStatement(
                "insert into owners (first_name, last_name, address, city, telephone) values (?, ?, ?, ?, ?)",
                arrayOf("id")
            )
            statement.setString(1, input.firstName)
            statement.setString(2, input.lastName)
            statement.setString(3, input.address)
            statement.setString(4, input.city)
            statement.setString(5, input.telephone)
            statement
        }, key)
        return owner(asLlm<Number, Int>(key.key!!, "Represent generated owner database key as an Int."))
    }

    fun updateOwner(ownerId: Int, input: OwnerInput) {
        jdbc.update(
            "update owners set first_name = ?, last_name = ?, address = ?, city = ?, telephone = ? where id = ?",
            input.firstName,
            input.lastName,
            input.address,
            input.city,
            input.telephone,
            ownerId
        )
    }

    fun pet(petId: Int): Pet =
        petsByRows(
            jdbc.queryForList(
                """
                select pets.id, pets.name, pets.birth_date, pets.type_id, pets.owner_id, types.name as type_name
                from pets
                join types on pets.type_id = types.id
                where pets.id = ?
                """.trimIndent(),
                petId
            )
        ).first().copy(visits = visitsForPet(petId))

    fun createPet(ownerId: Int, input: PetInput) {
        jdbc.update(
            "insert into pets (name, birth_date, type_id, owner_id) values (?, ?, ?, ?)",
            input.name,
            input.birthDate,
            input.typeId,
            ownerId
        )
    }

    fun updatePet(petId: Int, input: PetInput) {
        jdbc.update(
            "update pets set name = ?, birth_date = ?, type_id = ? where id = ?",
            input.name,
            input.birthDate,
            input.typeId,
            petId
        )
    }

    fun createVisit(petId: Int, input: VisitInput) {
        jdbc.update(
            "insert into visits (pet_id, visit_date, description) values (?, ?, ?)",
            petId,
            input.date,
            input.description
        )
    }

    fun vets(): List<Vet> =
        asLlm<String, List<Vet>>(
            jdbc.queryForList(
                """
                select vets.id as vet_id, vets.first_name, vets.last_name,
                       specialties.id as specialty_id, specialties.name as specialty_name
                from vets
                left join vet_specialties on vets.id = vet_specialties.vet_id
                left join specialties on vet_specialties.specialty_id = specialties.id
                order by vets.last_name, vets.first_name, specialties.name
                """.trimIndent()
            ).toString(),
            "Represent joined veterinarian database rows as veterinarian data classes with specialties.",
        )

    private fun ownersByRows(rows: List<Map<String, Any?>>): List<Owner> =
        asLlm<List<Map<String, Any?>>, List<Owner>>(
            rows,
            "Represent owner database rows as owner data classes."
        )

    private fun petsForOwner(ownerId: Int): List<Pet> =
        petsByRows(
            jdbc.queryForList(
                """
                select pets.id, pets.name, pets.birth_date, pets.type_id, pets.owner_id, types.name as type_name
                from pets
                join types on pets.type_id = types.id
                where pets.owner_id = ?
                order by pets.name
                """.trimIndent(),
                ownerId
            )
        ).map { pet -> pet.copy(visits = visitsForPet(pet.id)) }

    private fun petsByRows(rows: List<Map<String, Any?>>): List<Pet> =
        asLlm<List<Map<String, Any?>>, List<Pet>>(
            rows,
            "Represent pet database rows as pet data classes."
        )

    private fun visitsForPet(petId: Int): List<Visit> =
        asLlm<List<Map<String, Any?>>, List<Visit>>(
            jdbc.queryForList(
                "select id, pet_id, visit_date, description from visits where pet_id = ? order by visit_date",
                petId
            ),
            "Represent visit database rows as visit data classes."
        )
}
