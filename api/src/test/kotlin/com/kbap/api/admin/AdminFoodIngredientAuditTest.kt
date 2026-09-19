package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import io.kotest.matchers.shouldBe
import org.springframework.test.web.servlet.get

@IntegrationTest
class AdminFoodIngredientAuditTest : AdminFoodCatalogTestSupport() {
    private fun seed(name: String, rawIngredients: String?): Long {
        val id = saveFood(name).id
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE food SET ingredients = CAST(? AS JSON) WHERE id = ?").use { ps ->
                ps.setString(1, rawIngredients)
                ps.setLong(2, id)
                ps.executeUpdate()
            }
        }
        return id
    }

    private fun item(code: String, percent: Int) = """{"code": "$code", "inclusion_percent": $percent}"""

    init {
        given("재료 원본 점검") {
            `when`("원본에 오류 유형이 섞여 있으면") {
                then("유형별로 음식 id 를 모으고 정상 음식은 어디에도 넣지 않는다") {
                    val ok = seed("정상", "[${item("SOY", 80)}]")
                    val unassessed = seed("미조사", null)
                    val empty = seed("빈배열", "[]")
                    val notArray = seed("비배열", item("SOY", 80))
                    val malformed = seed("타입오류", """[{"code": "SOY", "inclusion_percent": "80"}]""")
                    val badFormat = seed("형식오류", "[${item("soy sauce", 80)}]")
                    val zero = seed("영퍼센트", "[${item("SOY", 0)}]")
                    val outOfRange = seed("범위밖", "[${item("SOY", 101)}]")
                    val unknown = seed("미등록", "[${item("KIMCHI_PASTE", 50)}]")
                    val duplicate = seed("중복", "[${item("SOY", 80)}, ${item("SOY", 20)}]")
                    val tooMany = seed("초과", (1..22).joinToString(",", "[", "]") { item("SOY", it) })
                    val deleted = seed("삭제됨", "[${item("SOY", 0)}]")
                    dataSource.connection.use { c ->
                        c.createStatement().use { it.execute("UPDATE food SET status = 'DELETED' WHERE id = $deleted") }
                    }

                    val json = mockMvc.get("$path/ingredient-audit") { adminAuth(this) }
                        .andExpect { status { isOk() } }
                        .andReturn().response.getContentAsString(Charsets.UTF_8)
                    val payload = mapper.readTree(json).path("payload")
                    fun ids(issue: String) = payload.path("issues").path(issue).map { it.asLong() }

                    payload.path("scanned").asInt() shouldBe 12
                    payload.path("nullCount").asInt() shouldBe 1
                    payload.path("emptyCount").asInt() shouldBe 1
                    payload.path("maxIngredientCount").asInt() shouldBe 22
                    ids("NOT_ARRAY") shouldBe listOf(notArray)
                    ids("MALFORMED_ITEM") shouldBe listOf(malformed)
                    ids("INVALID_CODE_FORMAT") shouldBe listOf(badFormat)
                    ids("ZERO_PERCENT") shouldBe listOf(zero, deleted)
                    ids("OUT_OF_RANGE") shouldBe listOf(outOfRange)
                    ids("UNKNOWN_CODE") shouldBe listOf(unknown)
                    ids("DUPLICATE_CODE") shouldBe listOf(duplicate, tooMany)
                    ids("TOO_MANY") shouldBe listOf(tooMany)
                    listOf(ok, unassessed, empty).none { id ->
                        payload.path("issues").any { list -> list.any { it.asLong() == id } }
                    } shouldBe true
                }
            }
        }
    }
}
