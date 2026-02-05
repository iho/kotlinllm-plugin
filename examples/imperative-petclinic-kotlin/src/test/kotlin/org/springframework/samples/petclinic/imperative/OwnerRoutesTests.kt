package org.springframework.samples.petclinic.imperative

import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class OwnerRoutesTests : PetclinicRouteTestSupport() {

    @Test
    fun testInitCreationForm() {
        mockMvc.perform(get("/owners/new"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("""<form action="/owners/new" method="post">""")))
            .andExpect(content().string(containsString("""name="firstName"""")))
            .andExpect(content().string(containsString("""name="telephone"""")))
    }

    @Test
    fun testProcessCreationFormSuccess() {
        mockMvc.perform(
            post("/owners/new")
                .param("firstName", "Joe")
                .param("lastName", "Bloggs")
                .param("address", "123 Caramel Street")
                .param("city", "London")
                .param("telephone", "0131676163")
        )
            .andExpect(status().isSeeOther)
            .andExpect(header().string("Location", "/owners/11"))

        assertThat(countRows("select count(*) from owners where first_name = 'Joe' and last_name = 'Bloggs'"))
            .isEqualTo(1)
    }

    @Test
    fun testProcessCreationFormHasErrors() {
        mockMvc.perform(
            post("/owners/new")
                .param("firstName", "Joe")
                .param("lastName", "Bloggs")
                .param("city", "London")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("is required")))
            .andExpect(content().string(containsString("""<form action="/owners/new" method="post">""")))
    }

    @Test
    fun testInitFindForm() {
        mockMvc.perform(get("/owners/find"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("<h1>Find Owners</h1>")))
            .andExpect(content().string(containsString("""<form action="/owners" method="get">""")))
    }

    @Test
    fun testProcessFindFormSuccess() {
        mockMvc.perform(get("/owners"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("<h1>Owners</h1>")))
            .andExpect(content().string(containsString("George Franklin")))
            .andExpect(content().string(containsString("Carlos Estaban")))
    }

    @Test
    fun testProcessFindFormByLastName() {
        mockMvc.perform(get("/owners").param("lastName", "Franklin"))
            .andExpect(status().isSeeOther)
            .andExpect(redirectedUrl("/owners/1"))
    }

    @Test
    fun testProcessFindFormNoOwnersFound() {
        mockMvc.perform(get("/owners").param("lastName", "Unknown Surname"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("No owners found for")))
            .andExpect(content().string(containsString("Unknown Surname")))
            .andExpect(content().string(containsString("""<form action="/owners" method="get">""")))
    }

    @Test
    fun testInitUpdateOwnerForm() {
        mockMvc.perform(get("/owners/{ownerId}/edit", TEST_OWNER_ID))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("""<form action="/owners/1/edit" method="post">""")))
            .andExpect(content().string(containsString("""name="firstName" type="text" value="George"""")))
            .andExpect(content().string(containsString("""name="lastName" type="text" value="Franklin"""")))
            .andExpect(content().string(containsString("""name="address" type="text" value="110 W. Liberty St."""")))
            .andExpect(content().string(containsString("""name="city" type="text" value="Madison"""")))
            .andExpect(content().string(containsString("""name="telephone" type="text" value="6085551023"""")))
    }

    @Test
    fun testProcessUpdateOwnerFormSuccess() {
        mockMvc.perform(
            post("/owners/{ownerId}/edit", TEST_OWNER_ID)
                .param("firstName", "Joe")
                .param("lastName", "Bloggs")
                .param("address", "123 Caramel Street")
                .param("city", "London")
                .param("telephone", "0161629158")
        )
            .andExpect(status().isSeeOther)
            .andExpect(header().string("Location", "/owners/1"))

        assertThat(
            jdbc.queryForObject(
                "select city from owners where id = ?",
                String::class.java,
                TEST_OWNER_ID
            )
        ).isEqualTo("London")
    }

    @Test
    fun testProcessUpdateOwnerFormHasErrors() {
        mockMvc.perform(
            post("/owners/{ownerId}/edit", TEST_OWNER_ID)
                .param("firstName", "Joe")
                .param("lastName", "Bloggs")
                .param("city", "London")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("is required")))
            .andExpect(content().string(containsString("""<form action="/owners/1/edit" method="post">""")))
    }

    @Test
    fun testShowOwner() {
        mockMvc.perform(get("/owners/{ownerId}", TEST_OWNER_ID))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("George Franklin")))
            .andExpect(content().string(containsString("110 W. Liberty St.")))
            .andExpect(content().string(containsString("Madison")))
            .andExpect(content().string(containsString("6085551023")))
            .andExpect(content().string(containsString("Leo")))
    }
}
