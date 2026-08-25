package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.kbap.api.admin.AdminTestTokens.adminHeaders
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodQueryControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var contentOutboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val objectMapper = jacksonObjectMapper()

    init {
        val targets = LanguageCode.entries.filter { it != LanguageCode.KO }.map { it.code }
        fun allTargets(v: String) = targets.associateWith { "$v-$it" }

        fun seed(
            name: String,
            status: FoodContentStatus,
            imageRef: String? = "images/food/$name.webp",
            ingredients: List<FoodIngredient>? = listOf(FoodIngredient("SOY", 100)),
            failureKind: FoodContentFailureKind? = null,
            enTranslation: String = "$name-en",
        ): Food = foodRepository.save(
            Food(
                koreanName = name,
                displayName = name,
                imageRef = imageRef,
                description = "$name 설명",
                spiciness = 1,
                nameTranslations = allTargets(name) + ("en" to enTranslation),
                descriptionTranslations = allTargets("d"),
                ingredients = ingredients,
                contentStatus = status,
            ).apply { contentFailureKind = failureKind },
        )

        fun get(path: String, token: String = AdminTestTokens.adminAccessToken(tokenIssuer)): MvcResult =
            mockMvc.get("/api/admin$path") { adminHeaders(token) }.andReturn()

        fun json(result: MvcResult): Map<String, Any?> = objectMapper.readValue(result.response.contentAsString)

        @Suppress("UNCHECKED_CAST")
        fun payload(result: MvcResult) = json(result)["payload"] as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        fun items(result: MvcResult) = payload(result)["items"] as List<Map<String, Any?>>

        fun names(result: MvcResult) = items(result).map { it["displayName"] }

        beforeContainer {
            vectorOutboxRepository.deleteAll()
            contentOutboxRepository.deleteAll()
            foodRepository.deleteAll()
        }

        given("GET /api/admin/foods") {
            `when`("검색·필터·정렬·페이지를 조합하면") {
                then("조건에 맞는 항목과 메타가 온다") {
                    val chicken = seed("삼계탕", FoodContentStatus.READY, ingredients = listOf(FoodIngredient("CHICKEN", 100)), enTranslation = "Ginseng Chicken Soup")
                    seed("김치찌개", FoodContentStatus.READY)
                    seed("김치전", FoodContentStatus.PENDING_REVIEW)
                    seed("실패음식", FoodContentStatus.FAILED, failureKind = FoodContentFailureKind.NOT_FOOD)
                    seed("판정실패", FoodContentStatus.FAILED, failureKind = FoodContentFailureKind.JUDGE_REJECTED)
                    val deleted = seed("삭제음식", FoodContentStatus.READY).apply { delete() }.let { foodRepository.save(it) }

                    names(get("/foods?q=${chicken.id}")) shouldContainExactly listOf("삼계탕")
                    names(get("/foods?q=김치")) shouldContainExactlyInAnyOrder listOf("김치찌개", "김치전")
                    names(get("/foods?ingredient=CHICKEN")) shouldContainExactly listOf("삼계탕")
                    names(get("/foods?translation=Ginseng")) shouldContainExactly listOf("삼계탕")
                    names(get("/foods?status=PENDING_REVIEW")) shouldContainExactly listOf("김치전")
                    names(get("/foods?failureKind=NOT_FOOD")) shouldContainExactly listOf("실패음식")

                    names(get("/foods")).size shouldBe 5
                    val withDeleted = get("/foods?includeDeleted=true")
                    names(withDeleted).size shouldBe 6
                    items(withDeleted).single { it["id"] == deleted.id.toInt() }["deleted"] shouldBe true

                    names(get("/foods?sort=displayName,asc")).first() shouldBe "김치전"
                    val paged = payload(get("/foods?size=2&page=2"))
                    (paged["items"] as List<*>).size shouldBe 2
                    paged["totalCount"] shouldBe 5
                    paged["totalPages"] shouldBe 3

                    get("/foods?size=201").response.status shouldBe 400
                    get("/foods?sort=price,asc").response.status shouldBe 400
                }
            }

            `when`("항목을 보면") {
                then("이미지 유무·리뷰 수·벡터 동기화 상태가 있다") {
                    val food = seed("항목음식", FoodContentStatus.READY)
                    vectorOutboxRepository.save(FoodVectorOutbox.upsert(food.id).apply { complete() })

                    val item = items(get("/foods")).single()

                    item["hasImage"] shouldBe true
                    item["reviewCount"] shouldBe 0
                    item["vectorSyncStatus"] shouldBe "COMPLETE"
                    @Suppress("UNCHECKED_CAST")
                    (item["contentStatus"] as Map<String, Any?>)["label"] shouldBe "준비 완료"
                }
            }

            `when`("회원 토큰이면") {
                then("403") {
                    get("/foods", AdminTestTokens.userAccessToken(tokenIssuer, 1L)).response.status shouldBe 403
                }
            }
        }

        given("GET /api/admin/foods/{id}") {
            `when`("이력이 있는 음식을 조회하면") {
                then("구조화 번역·재료·허용 전이·이력 4종·집계가 한 응답에 온다") {
                    val food = seed("상세음식", FoodContentStatus.PENDING_REVIEW)
                    contentOutboxRepository.save(FoodContentOutbox.pending(food.id, "상세음식"))
                    vectorOutboxRepository.save(FoodVectorOutbox.upsert(food.id))

                    val p = payload(get("/foods/${food.id}"))

                    @Suppress("UNCHECKED_CAST")
                    ((p["ingredients"] as List<Map<String, Any?>>).single()["inclusionPercent"]) shouldBe 100
                    @Suppress("UNCHECKED_CAST")
                    (p["nameTranslations"] as Map<String, Any?>).size shouldBeGreaterThan 8
                    p["allowedTransitions"] shouldBe listOf("APPROVE", "REJECT")
                    @Suppress("UNCHECKED_CAST")
                    val history = p["history"] as Map<String, Any?>
                    (history["contentOutboxes"] as List<*>).size shouldBe 1
                    (history["vectorOutboxes"] as List<*>).size shouldBe 1
                    history["imageItems"] shouldBe emptyList<Any>()
                    @Suppress("UNCHECKED_CAST")
                    (history["reviewSummary"] as Map<String, Any?>)["count"] shouldBe 0
                    history["scanMatchCount"] shouldBe 0
                    history["bookmarkCount"] shouldBe 0
                }
            }

            `when`("삭제된 음식을 조회하면") {
                then("deleted:true 로 온다") {
                    val food = seed("삭제상세", FoodContentStatus.READY).apply { delete() }.let { foodRepository.save(it) }

                    payload(get("/foods/${food.id}"))["deleted"] shouldBe true
                }
            }

            `when`("없는 음식이면") {
                then("400 FOOD-001") {
                    val result = get("/foods/999999")
                    result.response.status shouldBe 400
                    json(result)["code"] shouldBe "FOOD-001"
                }
            }
        }

        given("GET /api/admin/ingredients") {
            `when`("조회하면") {
                then("카탈로그 81건이 코드순으로 온다") {
                    val list = items(get("/ingredients"))
                    list.size shouldBe 81
                    list.first()["code"] shouldBe list.map { it["code"] as String }.sorted().first()
                }
            }
        }
    }
}
