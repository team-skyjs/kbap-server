package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

@IntegrationTest
class AdminFoodCatalogControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var adminFoodService: AdminFoodService

    @Autowired
    private lateinit var foodContentOutboxJpaRepository: FoodContentOutboxJpaRepository

    init {
        val path = "/api/admin/foods"
        val spaOrigin = "https://kbap-admin.pages.dev"

        fun clearFoods() = TestTables.clearAll(dataSource)

        fun saveFood(koreanName: String, contentStatus: FoodContentStatus = FoodContentStatus.READY): Food =
            foodJpaRepository.save(
                Food(koreanName = koreanName, description = "구수한 $koreanName", contentStatus = contentStatus),
            )

        fun tokenOf(role: MemberRole): String = tokenIssuer.issueAccessToken(0, role)

        fun getList(query: String = "", token: String? = tokenOf(MemberRole.ADMIN)): ResultActionsDsl =
            mockMvc.get("$path$query") { token?.let { header("Authorization", "Bearer $it") } }

        fun preflight(target: String, origin: String): ResultActionsDsl =
            mockMvc.options(target) {
                header("Origin", origin)
                header("Access-Control-Request-Method", "GET")
                header("Access-Control-Request-Headers", "authorization,x-api-version")
            }

        val mapper = jacksonObjectMapper()
        val adminAuth: (org.springframework.test.web.servlet.MockHttpServletRequestDsl) -> Unit =
            { it.header("Authorization", "Bearer ${tokenOf(MemberRole.ADMIN)}") }

        fun getDetail(id: Long): ResultActionsDsl = mockMvc.get("$path/$id") { adminAuth(this) }

        fun putUpdate(id: Long, body: Map<String, Any?>): ResultActionsDsl =
            mockMvc.put("$path/$id") {
                adminAuth(this)
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(body)
            }

        fun updateBody(koreanName: String): Map<String, Any?> = mapOf(
            "koreanName" to koreanName,
            "description" to "설명",
            "spiciness" to 1,
            "contentStatus" to "READY",
        )

        fun recollectOne(id: Long): ResultActionsDsl = mockMvc.post("$path/$id/recollect") { adminAuth(this) }

        fun recollectBulk(query: String = ""): ResultActionsDsl =
            mockMvc.post("$path/recollect$query") { adminAuth(this) }

        fun deleteFood(id: Long): ResultActionsDsl = mockMvc.delete("$path/$id") { adminAuth(this) }

        beforeContainer { clearFoods() }
        afterSpec { clearFoods() }

        given("어드민 음식 목록 조회 API") {
            `when`("음식 여러 건이 있으면") {
                then("id 내림차순 목록과 전체 건수를 내려준다") {
                    saveFood("김치찌개")
                    saveFood("된장찌개")
                    val last = saveFood("비빔밥")

                    getList().andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.items.length()") { value(3) }
                        jsonPath("$.payload.items[0].id") { value(last.id) }
                        jsonPath("$.payload.items[0].koreanName") { value("비빔밥") }
                        jsonPath("$.payload.items[0].contentStatus") { value("READY") }
                        jsonPath("$.payload.totalCount") { value(3) }
                        jsonPath("$.payload.totalPages") { value(1) }
                        jsonPath("$.payload.page") { value(1) }
                        jsonPath("$.payload.hasPrev") { value(false) }
                        jsonPath("$.payload.hasNext") { value(false) }
                    }
                }
            }

            `when`("검색어 q 를 주면") {
                then("표시 이름 부분 일치 건만 전체 건수와 함께 내려준다") {
                    saveFood("김치찌개")
                    saveFood("김치볶음밥")
                    saveFood("된장찌개")

                    getList("?q=김치").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.totalCount") { value(2) }
                    }
                }
            }

            `when`("status 필터를 주면") {
                then("해당 콘텐츠 상태 건만 내려준다") {
                    saveFood("김치찌개")
                    val pending = saveFood("된장찌개", FoodContentStatus.PENDING_REVIEW)

                    getList("?status=PENDING_REVIEW").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].id") { value(pending.id) }
                        jsonPath("$.payload.totalCount") { value(1) }
                    }
                }
            }

            `when`("q 와 status 를 함께 주면") {
                then("두 조건을 모두 만족하는 건만 내려준다") {
                    saveFood("김치찌개")
                    saveFood("김치볶음밥", FoodContentStatus.PENDING_REVIEW)
                    saveFood("된장찌개", FoodContentStatus.PENDING_REVIEW)

                    getList("?q=김치&status=PENDING_REVIEW").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].koreanName") { value("김치볶음밥") }
                    }
                }
            }

            `when`("액세스 토큰 없이 호출하면") {
                then("401 로 거절한다") {
                    getList(token = null).andExpect { status { isUnauthorized() } }
                }
            }

            `when`("USER 역할 토큰으로 호출하면") {
                then("403(AUTH-008) 으로 거절한다") {
                    getList(token = tokenOf(MemberRole.USER)).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("AUTH-008") }
                    }
                }
            }
        }

        given("어드민 SPA CORS") {
            `when`("허용 오리진에서 Authorization 없이 프리플라이트를 보내면") {
                then("허용 오리진을 에코해 응답한다") {
                    preflight(path, spaOrigin).andExpect {
                        status { isOk() }
                        header { string("Access-Control-Allow-Origin", spaOrigin) }
                    }
                }
            }

            `when`("pages.dev 프리뷰 서브도메인 오리진이면") {
                then("와일드카드 패턴으로 허용한다") {
                    val previewOrigin = "https://abc123.kbap-admin.pages.dev"
                    preflight(path, previewOrigin).andExpect {
                        status { isOk() }
                        header { string("Access-Control-Allow-Origin", previewOrigin) }
                    }
                }
            }

            `when`("로컬 dev 오리진이면") {
                then("허용한다") {
                    preflight(path, "http://localhost:5173").andExpect {
                        status { isOk() }
                        header { string("Access-Control-Allow-Origin", "http://localhost:5173") }
                    }
                }
            }

            `when`("허용 오리진에서 토큰 없이 실제 요청을 보내면") {
                then("401 응답에도 CORS 헤더가 중복 없이 실린다") {
                    mockMvc.get(path) { header("Origin", spaOrigin) }.andExpect {
                        status { isUnauthorized() }
                        header { stringValues("Access-Control-Allow-Origin", spaOrigin) }
                    }
                }
            }

            `when`("허용 오리진에서 위조된 토큰으로 실제 요청을 보내면") {
                then("401 응답에도 CORS 헤더가 실린다") {
                    mockMvc.get(path) {
                        header("Origin", spaOrigin)
                        header("Authorization", "Bearer forged-token")
                    }.andExpect {
                        status { isUnauthorized() }
                        header { stringValues("Access-Control-Allow-Origin", spaOrigin) }
                    }
                }
            }

            `when`("허용 목록 밖 오리진에서 어드민 API 프리플라이트를 보내면") {
                then("거절한다") {
                    preflight(path, "https://evil.example.com").andExpect {
                        status { isForbidden() }
                    }
                }
            }

            `when`("허용 목록 밖 오리진에서 유효한 관리자 토큰으로 실제 요청을 보내면") {
                then("CORS 에서 거절한다") {
                    mockMvc.get(path) {
                        header("Origin", "https://evil.example.com")
                        header("Authorization", "Bearer ${tokenOf(MemberRole.ADMIN)}")
                    }.andExpect { status { isForbidden() } }
                }
            }

            `when`("어드민이 아닌 앱 API 경로에 임의 오리진으로 프리플라이트를 보내면") {
                then("기존 전역 CORS 가 그대로 허용한다") {
                    preflight("/api/orders", "https://anywhere.example.com").andExpect {
                        status { isOk() }
                        header { string("Access-Control-Allow-Origin", "https://anywhere.example.com") }
                    }
                }
            }
        }

        given("어드민 음식 상세 조회 API") {
            `when`("번역·성분이 채워진 음식을 조회하면") {
                then("원본 필드·번역 맵·성분·이미지·검수 이력을 내려준다") {
                    val food = foodJpaRepository.save(
                        Food(
                            koreanName = "된장찌개",
                            imageRef = "images/food/doenjang.webp",
                            description = "구수한 된장찌개",
                            spiciness = 3,
                            nameTranslations = mapOf("en" to "Soybean paste stew"),
                            descriptionTranslations = mapOf("en" to "savory stew"),
                            ingredients = listOf(FoodIngredient("SOYBEAN", 100)),
                            contentStatus = FoodContentStatus.READY,
                            contentReviewAttempts = 2,
                        ),
                    )

                    getDetail(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.id") { value(food.id) }
                        jsonPath("$.payload.koreanName") { value("된장찌개") }
                        jsonPath("$.payload.description") { value("구수한 된장찌개") }
                        jsonPath("$.payload.spiciness") { value(3) }
                        jsonPath("$.payload.contentStatus") { value("READY") }
                        jsonPath("$.payload.nameTranslations.en") { value("Soybean paste stew") }
                        jsonPath("$.payload.descriptionTranslations.en") { value("savory stew") }
                        jsonPath("$.payload.ingredients[0].code") { value("SOYBEAN") }
                        jsonPath("$.payload.ingredients[0].inclusion_percent") { value(100) }
                        jsonPath("$.payload.imageRef") { value("images/food/doenjang.webp") }
                        jsonPath("$.payload.imageUrl") { exists() }
                        jsonPath("$.payload.contentReviewAttempts") { value(2) }
                        jsonPath("$.payload.version") { exists() }
                        jsonPath("$.payload.createdAt") { exists() }
                        jsonPath("$.payload.updatedAt") { exists() }
                    }
                }
            }

            `when`("없는 id 를 조회하면") {
                then("400(FOOD-001) 로 거절한다") {
                    getDetail(999999).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }

            `when`("소프트삭제된 음식을 조회하면") {
                then("400(FOOD-001) 로 거절한다") {
                    val food = saveFood("삭제된찌개")
                    deleteFood(food.id).andExpect { status { isOk() } }

                    getDetail(food.id).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }
        }

        given("어드민 음식 수정 API") {
            `when`("전체 필드를 보내 수정하면") {
                then("반영된 상세를 내려주고 DB 에도 저장된다") {
                    val food = saveFood("수정전찌개")

                    putUpdate(
                        food.id,
                        mapOf(
                            "koreanName" to "수정후찌개",
                            "description" to "더 구수한 찌개",
                            "spiciness" to 4,
                            "contentStatus" to "PENDING_REVIEW",
                            "imageRef" to "images/food/updated.webp",
                            "nameTranslations" to mapOf("en" to "Updated stew"),
                            "descriptionTranslations" to mapOf("en" to "richer stew"),
                            "ingredients" to listOf(mapOf("code" to "SOY", "inclusion_percent" to 80)),
                        ),
                    ).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.koreanName") { value("수정후찌개") }
                        jsonPath("$.payload.description") { value("더 구수한 찌개") }
                        jsonPath("$.payload.spiciness") { value(4) }
                        jsonPath("$.payload.contentStatus") { value("PENDING_REVIEW") }
                        jsonPath("$.payload.nameTranslations.en") { value("Updated stew") }
                        jsonPath("$.payload.ingredients[0].inclusion_percent") { value(80) }
                    }

                    val reloaded = foodJpaRepository.findById(food.id).orElseThrow()
                    reloaded.displayName shouldBe "수정후찌개"
                    reloaded.spiciness shouldBe 4
                    reloaded.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                }
            }

            `when`("다른 음식과 같은 이름으로 수정하면") {
                then("409(FOOD-005) 로 거절한다") {
                    saveFood("김치찌개")
                    val food = saveFood("된장찌개")

                    putUpdate(food.id, updateBody(koreanName = "김치찌개")).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("FOOD-005") }
                    }
                }
            }

            `when`("이름을 비워 보내면") {
                then("400(COMMON-002) 검증 실패로 응답한다") {
                    val food = saveFood("된장찌개")

                    putUpdate(food.id, updateBody(koreanName = " ")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("없는 id 를 수정하면") {
                then("400(FOOD-001) 로 거절한다") {
                    putUpdate(999999, updateBody(koreanName = "유령찌개")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }

            `when`("현재와 같은 version 을 실어 수정하면") {
                then("정상 반영되고 version 이 올라간다") {
                    val food = saveFood("버전찌개")

                    putUpdate(food.id, updateBody(koreanName = "버전찌개") + mapOf("version" to food.version))
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.payload.version") { value(food.version + 1) }
                        }
                }
            }

            `when`("다른 관리자가 먼저 수정해 version 이 달라졌으면") {
                then("409(FOOD-006) 로 거절하고 아무것도 저장하지 않는다") {
                    val food = saveFood("경합찌개")
                    putUpdate(food.id, updateBody(koreanName = "먼저수정")).andExpect { status { isOk() } }

                    putUpdate(food.id, updateBody(koreanName = "나중수정") + mapOf("version" to food.version))
                        .andExpect {
                            status { isConflict() }
                            jsonPath("$.code") { value("FOOD-006") }
                        }

                    foodJpaRepository.findById(food.id).orElseThrow().displayName shouldBe "먼저수정"
                }
            }

            `when`("같은 version 두 요청이 동시에 제출되면") {
                then("한쪽만 반영되고 다른 쪽은 FOOD-006 으로 거절된다") {
                    val food = saveFood("동시수정찌개")
                    val executor = Executors.newFixedThreadPool(2)
                    val startGate = CountDownLatch(1)

                    fun command(name: String) = UpdateFoodCommand(
                        koreanName = name,
                        description = "설명",
                        spiciness = 1,
                        contentStatus = FoodContentStatus.READY,
                        imageRef = "",
                        nameTranslationsJson = "",
                        descriptionTranslationsJson = "",
                        ingredientsJson = "",
                    )

                    val results = listOf("승자찌개", "패자찌개").map { name ->
                        executor.submit<Any> {
                            startGate.await()
                            try {
                                adminFoodService.updateFood(food.id, command(name), expectedVersion = food.version)
                            } catch (e: BusinessException) {
                                e.errorCode
                            }
                        }
                    }
                    startGate.countDown()
                    val outcomes = results.map { it.get() }
                    executor.shutdown()

                    outcomes.count { it == AdminFoodUpdateResult.UPDATED } shouldBe 1
                    outcomes.count { it == ErrorCode.FOOD_VERSION_CONFLICT } shouldBe 1
                }
            }

            `when`("description 이 DB 컬럼 한도(255자)를 넘으면") {
                then("400(COMMON-002) 검증 실패로 응답한다") {
                    val food = saveFood("긴설명찌개")

                    putUpdate(
                        food.id,
                        updateBody(koreanName = "긴설명찌개") + mapOf("description" to "가".repeat(256)),
                    ).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("성분 카탈로그에 없는 코드로 수정하면") {
                then("400(COMMON-002) 로 거절하고 아무것도 저장하지 않는다") {
                    val food = saveFood("된장찌개")

                    putUpdate(
                        food.id,
                        updateBody(koreanName = "된장찌개") +
                            mapOf("ingredients" to listOf(mapOf("code" to "UNKNOWN_CODE", "inclusion_percent" to 50))),
                    ).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }

                    foodJpaRepository.findById(food.id).orElseThrow().ingredients.orEmpty() shouldBe emptyList()
                }
            }
        }

        given("어드민 음식 재수집 API") {
            `when`("단건 재수집을 요청하면") {
                then("콘텐츠 수집 대기가 생성된다") {
                    val food = saveFood("재수집찌개")

                    recollectOne(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.requested") { value(1) }
                        jsonPath("$.payload.created") { value(1) }
                        jsonPath("$.payload.skipped") { value(0) }
                    }
                }
            }

            `when`("이미 수집 대기 중인 음식에 단건 재수집을 요청하면") {
                then("생성 없이 건너뛴다") {
                    val food = saveFood("대기중찌개")
                    recollectOne(food.id).andExpect { status { isOk() } }

                    recollectOne(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.created") { value(0) }
                        jsonPath("$.payload.skipped") { value(1) }
                    }
                }
            }

            `when`("같은 음식에 단건 재수집이 동시에 들어오면") {
                then("수집 대기는 정확히 한 건만 생성된다") {
                    val food = saveFood("동시성찌개")
                    val executor = Executors.newFixedThreadPool(2)
                    val startGate = CountDownLatch(1)

                    val results = (1..2).map {
                        executor.submit<AdminFoodRecollectResult> {
                            startGate.await()
                            adminFoodService.requestRecollectForFood(food.id)
                        }
                    }
                    startGate.countDown()
                    val outcomes = results.map { it.get() }
                    executor.shutdown()

                    outcomes.sumOf { it.created } shouldBe 1
                    outcomes.sumOf { it.skipped } shouldBe 1
                    foodContentOutboxJpaRepository
                        .findByFoodIdInAndOutboxStatus(listOf(food.id), FoodContentOutboxStatus.PENDING)
                        .size shouldBe 1
                }
            }

            `when`("없는 id 에 단건 재수집을 요청하면") {
                then("400(FOOD-001) 로 거절한다") {
                    recollectOne(999999).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }

            `when`("검색어 필터로 일괄 재수집을 요청하면") {
                then("일치 건만 대기를 만들고 카운트를 내려준다") {
                    saveFood("김치찌개")
                    saveFood("김치볶음밥")
                    saveFood("된장찌개")

                    recollectBulk("?q=김치").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.requested") { value(2) }
                        jsonPath("$.payload.created") { value(2) }
                        jsonPath("$.payload.skipped") { value(0) }
                        jsonPath("$.payload.exceeded") { value(false) }
                    }
                }
            }
        }

        given("어드민 음식 소프트삭제 API") {
            `when`("음식을 삭제하면") {
                then("목록·상세에서 사라진다") {
                    val food = saveFood("삭제할찌개")

                    deleteFood(food.id).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }

                    getList().andExpect { jsonPath("$.payload.totalCount") { value(0) } }
                }
            }

            `when`("없는 id 를 삭제하면") {
                then("400(FOOD-001) 로 거절한다") {
                    deleteFood(999999).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }
        }
    }
}
