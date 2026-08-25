package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminAppVersionHistoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    private val objectMapper = jacksonObjectMapper()

    init {
        fun token() = AdminTestTokens.adminAccessToken(tokenIssuer)
        fun json(r: MvcResult): Map<String, Any?> = objectMapper.readValue(r.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(r: MvcResult) = json(r)["payload"] as Map<String, Any?>

        beforeContainer {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM admin_audit_log") } }
        }

        given("PUT /api/admin/app-version → GET /api/admin/app-version/history") {
            `when`("최신 버전만 바꾸면") {
                then("이력에 변경자·바뀐 필드의 before/after 만 남는다") {
                    val current = payload(mockMvc.get("/api/admin/app-version") { adminHeaders(token()) }.andReturn())
                    val body = mapOf(
                        "minSupportedVersion" to current["minSupportedVersion"],
                        "latestVersion" to "9.9.9",
                        "iosStoreUrl" to current["iosStoreUrl"],
                        "aosStoreUrl" to current["aosStoreUrl"],
                    )
                    mockMvc.put("/api/admin/app-version") {
                        adminHeaders(token())
                        contentType = MediaType.APPLICATION_JSON
                        content = objectMapper.writeValueAsString(body)
                    }.andReturn().response.status shouldBe 200

                    val history = mockMvc.get("/api/admin/app-version/history") { adminHeaders(token()) }.andReturn()
                    history.response.status shouldBe 200
                    @Suppress("UNCHECKED_CAST")
                    val items = payload(history)["items"] as List<Map<String, Any?>>
                    items.size shouldBe 1
                    val entry = items.single()
                    entry["action"] shouldBe "APP_VERSION_UPDATE"
                    entry["adminAccountId"] shouldBe 1
                    @Suppress("UNCHECKED_CAST")
                    val before = entry["before"] as Map<String, Any?>
                    @Suppress("UNCHECKED_CAST")
                    val after = entry["after"] as Map<String, Any?>
                    before.keys shouldBe setOf("latestVersion")
                    before["latestVersion"] shouldBe current["latestVersion"]
                    after["latestVersion"] shouldBe "9.9.9"
                }
            }
        }
    }
}
