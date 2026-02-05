package org.springframework.samples.petclinic.imperative

import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class VisitRoutesTests : PetclinicRouteTestSupport() {

    @Test
    fun testInitNewVisitForm() {
        mockMvc.perform(get("/owners/{ownerId}/pets/{petId}/visits/new", TEST_OWNER_ID, TEST_PET_ID))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Visit for Leo")))
            .andExpect(content().string(containsString("""<form action="/owners/1/pets/1/visits/new" method="post">""")))
            .andExpect(content().string(containsString("""name="date"""")))
            .andExpect(content().string(containsString("""name="description"""")))
    }

    @Test
    fun testProcessNewVisitFormSuccess() {
        mockMvc.perform(
            post("/owners/{ownerId}/pets/{petId}/visits/new", TEST_OWNER_ID, TEST_PET_ID)
                .param("date", "2024-02-01")
                .param("description", "rabies shot")
        )
            .andExpect(status().isSeeOther)
            .andExpect(header().string("Location", "/owners/1"))

        assertThat(countRows("select count(*) from visits where pet_id = 1 and description = 'rabies shot'"))
            .isEqualTo(1)
    }

    @Test
    fun testProcessNewVisitFormHasErrors() {
        mockMvc.perform(
            post("/owners/{ownerId}/pets/{petId}/visits/new", TEST_OWNER_ID, TEST_PET_ID)
                .param("date", "")
                .param("description", "")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("date")))
            .andExpect(content().string(containsString("description")))
    }

    @Test
    fun testShowOwnerWithVisits() {
        mockMvc.perform(get("/owners/{ownerId}", 6))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("Jean Coleman")))
            .andExpect(content().string(containsString("rabies shot")))
            .andExpect(content().string(containsString("neutered")))
    }
}
