package org.springframework.samples.petclinic.imperative

import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Locale

class PetRoutesTests : PetclinicRouteTestSupport() {

    @Test
    fun testInitCreationFormForPet() {
        mockMvc.perform(get("/owners/{ownerId}/pets/new", TEST_OWNER_ID))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("""<form action="/owners/1/pets/new" method="post">""")))
            .andExpect(content().string(containsString("""name="name"""")))
            .andExpect(content().string(containsString("""name="birthDate"""")))
            .andExpect(content().string(containsString("<option value=\"2\"")))
    }

    @Test
    fun testProcessCreationFormForPetSuccess() {
        mockMvc.perform(
            post("/owners/{ownerId}/pets/new", TEST_OWNER_ID)
                .locale(Locale.US)
                .param("name", "Fido")
                .param("birthDate", "2020-01-01")
                .param("type", "2")
        )
            .andExpect(status().isSeeOther)
            .andExpect(header().string("Location", "/owners/1"))

        assertThat(countRows("select count(*) from pets where owner_id = 1 and name = 'Fido'"))
            .isEqualTo(1)
    }

    @Test
    fun testProcessCreationFormForPetHasErrors() {
        mockMvc.perform(
            post("/owners/{ownerId}/pets/new", TEST_OWNER_ID)
                .locale(Locale.US)
                .param("name", "")
                .param("birthDate", "")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("name: is required")))
            .andExpect(content().string(containsString("birthDate: is required")))
            .andExpect(content().string(containsString("type: is required")))
    }

    @Test
    fun testInitUpdatePetForm() {
        mockMvc.perform(get("/owners/{ownerId}/pets/{petId}/edit", TEST_OWNER_ID, TEST_PET_ID))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("""<form action="/owners/1/pets/1/edit" method="post">""")))
            .andExpect(content().string(containsString("""name="name" type="text" value="Leo"""")))
            .andExpect(content().string(containsString("""name="birthDate" type="date" value="2010-09-07"""")))
    }

    @Test
    fun testProcessUpdatePetFormSuccess() {
        mockMvc.perform(
            post("/owners/{ownerId}/pets/{petId}/edit", TEST_OWNER_ID, TEST_PET_ID)
                .locale(Locale.US)
                .param("name", "Leo Updated")
                .param("birthDate", "2020-01-02")
                .param("type", "2")
        )
            .andExpect(status().isSeeOther)
            .andExpect(header().string("Location", "/owners/1"))

        assertThat(
            jdbc.queryForObject(
                "select name from pets where id = ?",
                String::class.java,
                TEST_PET_ID
            )
        ).isEqualTo("Leo Updated")
    }

    @Test
    fun testProcessUpdatePetFormHasErrors() {
        mockMvc.perform(
            post("/owners/{ownerId}/pets/{petId}/edit", TEST_OWNER_ID, TEST_PET_ID)
                .locale(Locale.US)
                .param("name", "")
                .param("birthDate", "")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("name: is required")))
            .andExpect(content().string(containsString("birthDate: is required")))
            .andExpect(content().string(containsString("type: is required")))
    }
}
