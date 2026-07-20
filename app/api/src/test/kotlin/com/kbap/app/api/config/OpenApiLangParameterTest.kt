package com.kbap.app.api.config

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class OpenApiLangParameterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    private val mapper = jacksonObjectMapper()

    init {
        // @ParameterObject 가 요청 DTO 를 쿼리 파라미터로 펼치지 못하면 문서가 실제 계약과 어긋난다.
        val targets = listOf(
            "/api/v1/home" to "get",
            "/api/v1/foods" to "get",
            "/api/v1/foods/search" to "get",
            "/api/v1/foods/{foodId}" to "get",
            "/api/v1/bookmarks" to "get",
        )

        fun docs(): JsonNode =
            mapper.readTree(
                mockMvc.get("/v3/api-docs").andReturn().response.getContentAsString(Charsets.UTF_8),
            )

        fun langParam(docs: JsonNode, path: String, method: String): JsonNode? =
            docs.path("paths").path(path).path(method).path("parameters")
                .firstOrNull { it.path("name").asText() == "lang" }

        given("생성된 OpenAPI 문서") {
            `when`("lang 을 받는 5개 엔드포인트를 확인하면") {
                then("모두 lang 을 required 쿼리 파라미터로 노출한다") {
                    val docs = docs()

                    targets.forEach { (path, method) ->
                        val lang = langParam(docs, path, method)

                        withClue(path) {
                            lang shouldNotBe null
                            lang!!.path("in").asText() shouldBe "query"
                            lang.path("required").asBoolean() shouldBe true
                        }
                    }
                }
            }

            `when`("에러 코드 문구를 확인하면") {
                then("폐기된 COMMON-001 을 언급하지 않는다") {
                    docs().toString().contains("COMMON-001") shouldBe false
                }
            }
        }
    }
}

private fun <T> withClue(clue: Any, block: () -> T): T =
    io.kotest.assertions.withClue(clue, block)
