package org.springframework.samples.petclinic.imperative

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

class VetRoutesTests : PetclinicRouteTestSupport() {

    @Test
    fun testShowVetListHtml() {
        mockMvc.perform(get("/vets.html"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andExpect(content().string(containsString("James Carter")))
            .andExpect(content().string(containsString("Helen Leary")))
            .andExpect(content().string(containsString("radiology")))
    }

    @Test
    fun testShowVetListJson() {
        mockMvc.perform(get("/vets.json"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().string(containsString("James")))
            .andExpect(content().string(containsString("Carter")))
            .andExpect(content().string(containsString("radiology")))
    }

    @Test
    fun testShowVetListXml() {
        mockMvc.perform(get("/vets.xml"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
            .andExpect(content().string(containsString("<firstName>James</firstName>")))
            .andExpect(content().string(containsString("<lastName>Carter</lastName>")))
            .andExpect(content().string(containsString("<name>radiology</name>")))
    }
}
