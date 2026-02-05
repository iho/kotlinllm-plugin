package org.springframework.samples.petclinic.imperative

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.web.servlet.MockMvc

internal const val TEST_OWNER_ID = 1
internal const val TEST_PET_ID = 1

@SpringBootTest
@AutoConfigureMockMvc
@Sql(scripts = ["/schema.sql", "/data.sql"])
abstract class PetclinicRouteTestSupport {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var jdbc: JdbcTemplate

    protected fun countRows(sql: String): Int =
        jdbc.queryForObject(sql, Int::class.java) ?: 0
}
