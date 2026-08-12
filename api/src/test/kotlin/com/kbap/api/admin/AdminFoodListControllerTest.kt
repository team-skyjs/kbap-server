package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import jakarta.servlet.http.Cookie
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class AdminFoodListControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun adminCookie(): Cookie =
            Cookie(AdminPageAuthInterceptor.COOKIE_NAME, tokenIssuer.issueAccessToken(1, MemberRole.ADMIN))

        fun clearFoods() {
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM food")
                }
            }
        }

        fun saveFood(koreanName: String): Food =
            foodJpaRepository.save(Food(koreanName = koreanName, description = "구수한 $koreanName"))

        fun listPageOf(result: MvcResult): AdminFoodListPageView =
            result.modelAndView!!.model["foodPage"] as AdminFoodListPageView

        fun getList(query: String = ""): MvcResult =
            mockMvc.get("/admin/foods/list$query") { cookie(adminCookie()) }
                .andExpect {
                    status { isOk() }
                    view { name("admin/food-list") }
                }.andReturn()

        beforeContainer { clearFoods() }
        afterSpec { clearFoods() }

        given("음식 목록 offset 페이징") {
            `when`("음식 201건에서 첫 페이지를 조회하면") {
                then("id 내림차순 200건과 페이지 정보를 내려준다") {
                    foodJpaRepository.saveAll((1..201).map { Food(koreanName = "목록음식$it", description = "구수한 목록음식$it") })

                    val page = listPageOf(getList())

                    page.items.size shouldBe 200
                    page.page shouldBe 1
                    page.totalPages shouldBe 2
                    page.totalCount shouldBe 201
                    page.hasNext shouldBe true

                    val page2 = listPageOf(getList("?page=2"))
                    page2.items.size shouldBe 1
                    page2.hasPrev shouldBe true
                }
            }

            `when`("음식이 없으면") {
                then("빈 목록을 내려준다") {
                    listPageOf(getList()).items shouldBe emptyList()
                }
            }

        }

        given("음식 카드 그리드") {
            `when`("목록을 렌더링하면") {
                then("고정 높이 뷰포트 안의 카드 그리드로 렌더링한다") {
                    val saved = saveFood("그리드비빔밥")

                    val html = getList().response.contentAsString

                    html shouldContain "food-grid-viewport"
                    html shouldContain "food-card"
                    html shouldContain "그리드비빔밥"
                    html shouldContain "detail=${saved.id}"
                    html shouldNotContain "food-card-list"
                    html shouldNotContain "food-row"
                }
            }

            `when`("imageRef 가 있는 음식이 있으면") {
                then("카드 썸네일로 해석된 공개 URL 을 지연 로딩한다") {
                    foodJpaRepository.save(
                        Food(koreanName = "그리드이미지음식", description = "설명", imageRef = "food/img/9.webp"),
                    )

                    val html = getList().response.contentAsString

                    html shouldContain "https://cdn.test/food/img/9.webp"
                    html shouldContain "loading=\"lazy\""
                }
            }

            `when`("imageRef 가 없는 음식이 있으면") {
                then("카드에 이미지 플레이스홀더를 렌더링한다") {
                    saveFood("그리드이미지없음")

                    getList().response.contentAsString shouldContain "image-placeholder"
                }
            }

            `when`("여러 콘텐츠 상태의 음식이 있으면") {
                then("상태별로 구분되는 배지 클래스를 카드에 렌더링한다") {
                    foodJpaRepository.saveAll(
                        FoodContentStatus.entries.map {
                            Food(koreanName = "배지음식$it", description = "설명", contentStatus = it)
                        },
                    )

                    val html = getList().response.contentAsString

                    listOf("badge-ok", "badge-progress", "badge-info", "badge-warn")
                        .forEach { html shouldContain it }
                }
            }

            `when`("표시할 음식이 없으면") {
                then("뷰포트를 유지한 채 빈 목록 안내를 렌더링한다") {
                    val html = getList().response.contentAsString

                    html shouldContain "food-grid-viewport"
                    html shouldContain "표시할 음식이 없습니다"
                }
            }
        }

        given("콘텐츠 상태 필터") {
            fun saveFood(koreanName: String, contentStatus: FoodContentStatus): Food =
                foodJpaRepository.save(
                    Food(koreanName = koreanName, description = "설명", contentStatus = contentStatus),
                )

            `when`("status 파라미터로 조회하면") {
                then("해당 상태의 음식만 건수와 함께 내려준다") {
                    saveFood("필터검수대기", FoodContentStatus.PENDING_REVIEW)
                    saveFood("필터완료", FoodContentStatus.READY)

                    val result = getList("?status=PENDING_REVIEW")

                    val page = listPageOf(result)
                    page.totalCount shouldBe 1
                    page.status shouldBe FoodContentStatus.PENDING_REVIEW
                    result.response.contentAsString shouldContain "필터검수대기"
                    result.response.contentAsString shouldNotContain "필터완료"
                }
            }

            `when`("q 와 status 를 함께 지정하면") {
                then("두 조건을 모두 만족하는 음식만 내려준다") {
                    saveFood("교집합김치찌개", FoodContentStatus.PENDING_REVIEW)
                    saveFood("교집합김치볶음밥", FoodContentStatus.READY)
                    saveFood("교집합된장찌개", FoodContentStatus.PENDING_REVIEW)

                    val result = getList("?q=김치&status=PENDING_REVIEW")

                    listPageOf(result).totalCount shouldBe 1
                    result.response.contentAsString shouldContain "교집합김치찌개"
                }
            }

            `when`("알 수 없는 status 값으로 조회하면") {
                then("오류 없이 상태 조건 없는 전체 목록을 내려준다") {
                    saveFood("미지의상태음식", FoodContentStatus.READY)

                    val result = getList("?status=NOT_A_STATUS")

                    listPageOf(result).status shouldBe null
                    listPageOf(result).totalCount shouldBe 1
                }
            }

            `when`("목록을 렌더링하면") {
                then("전체와 전 상태를 담은 필터 선택지를 내려준다") {
                    val html = getList("?status=READY").response.contentAsString

                    html shouldContain "name=\"status\""
                    FoodContentStatus.entries.forEach { html shouldContain "value=\"$it\"" }
                    html shouldContain "selected"
                }
            }

            `when`("상태 필터가 적용된 상태에서 페이지가 여러 개면") {
                then("페이지 이동·상세보기 링크가 상태를 유지한다") {
                    foodJpaRepository.saveAll(
                        (1..201).map {
                            Food(
                                koreanName = "상태유지음식$it",
                                description = "설명",
                                contentStatus = FoodContentStatus.PENDING_REVIEW,
                            )
                        },
                    )

                    val html = getList("?status=PENDING_REVIEW").response.contentAsString

                    html shouldContain "page=2&amp;q=&amp;status=PENDING_REVIEW"
                    html shouldContain "status=PENDING_REVIEW&amp;detail="
                }
            }

            `when`("해당 상태의 음식이 하나도 없으면") {
                then("오류 없이 빈 결과 안내를 렌더링한다") {
                    saveFood("다른상태음식", FoodContentStatus.READY)

                    val html = getList("?status=FAILED").response.contentAsString

                    html shouldContain "food-grid-viewport"
                    html shouldContain "결과가 없습니다"
                }
            }
        }

        given("음식 상세 패널") {
            `when`("detail 파라미터로 조회하면") {
                then("전 컬럼 데이터를 모델로 내려준다") {
                    val saved = foodJpaRepository.save(
                        Food(
                            koreanName = "모달비빔밥",
                            description = "모달 설명",
                            spiciness = 2,
                            contentStatus = FoodContentStatus.READY,
                            nameTranslations = mapOf("en" to "Bibimbap"),
                        ),
                    )

                    val result = getList("?detail=${saved.id}")

                    val detail = result.modelAndView!!.model["foodDetail"] as AdminFoodDetailView
                    detail.shouldNotBeNull()
                    detail.id shouldBe saved.id
                    detail.koreanName shouldBe "모달비빔밥"
                    detail.spiciness shouldBe 2
                    detail.contentStatus shouldBe FoodContentStatus.READY
                    detail.nameTranslationsJson shouldContain "Bibimbap"

                    result.response.contentAsString shouldContain "food-modal"
                }
            }

            `when`("detail 로 상세를 열면") {
                then("목록 위에 겹치는 모달로 렌더링하고 구 사이드 패널은 쓰지 않는다") {
                    val saved = saveFood("모달전환음식")

                    val html = getList("?detail=${saved.id}").response.contentAsString

                    html shouldContain "<dialog"
                    html shouldContain "food-modal"
                    html shouldContain "showModal()"
                    html shouldNotContain "food-panel"
                }
            }

            `when`("검색·상태 필터 상태에서 상세를 열면") {
                then("모달 닫기 링크와 폼 hidden 입력이 목록 조건을 유지한다") {
                    val saved = foodJpaRepository.save(
                        Food(
                            koreanName = "모달유지김치찌개",
                            description = "설명",
                            contentStatus = FoodContentStatus.PENDING_REVIEW,
                        ),
                    )

                    val html = getList("?q=김치&status=PENDING_REVIEW&detail=${saved.id}").response.contentAsString

                    html shouldContain "name=\"status\" value=\"PENDING_REVIEW\""
                    html shouldContain "name=\"q\" value=\"김치\""
                }
            }

            `when`("그리드를 렌더링하면") {
                then("목록 스크롤 위치를 세션에 저장·복원한다") {
                    saveFood("스크롤복원음식")

                    val html = getList().response.contentAsString

                    html shouldContain "sessionStorage"
                    html shouldContain "food-grid-viewport"
                }
            }

            `when`("imageRef 가 있는 음식을 detail 로 조회하면") {
                then("해석된 공개 URL 의 이미지를 상세에 렌더링한다") {
                    val saved = foodJpaRepository.save(
                        Food(koreanName = "이미지모달음식", description = "설명", imageRef = "food/img/2.png"),
                    )

                    val html = getList("?detail=${saved.id}").response.contentAsString
                    html shouldContain "https://cdn.test/food/img/2.png"
                }
            }

            `when`("imageRef 가 없는 음식을 detail 로 조회하면") {
                then("이미지 대신 플레이스홀더를 렌더링한다") {
                    val saved = saveFood("이미지없는모달음식")

                    val html = getList("?detail=${saved.id}").response.contentAsString
                    html shouldContain "image-placeholder"
                }
            }

            `when`("존재하지 않는 detail id 면") {
                then("상세 없이 목록만 보여준다") {
                    getList("?detail=999999").modelAndView!!.model["foodDetail"] shouldBe null
                }
            }
        }

        given("음식 상세 패널 편집 토글") {
            fun detailFieldTag(html: String, id: String): String =
                Regex("""<(?:input|select|textarea)[^>]*id="$id"[^>]*>""").find(html)!!.value

            val detailFieldIds = listOf(
                "koreanName", "contentStatus", "spiciness", "imageRef", "description",
                "nameTranslationsJson", "descriptionTranslationsJson", "ingredientsJson",
            )

            `when`("detail 만으로 상세를 펼치면") {
                then("전 입력이 비활성이고 저장 버튼 없이 편집 링크만 보인다") {
                    val saved = saveFood("토글읽기음식")

                    val html = getList("?page=1&detail=${saved.id}").response.contentAsString

                    detailFieldIds.forEach { id -> detailFieldTag(html, id) shouldContain "disabled" }
                    html shouldNotContain ">저장</button>"
                    html shouldContain ">편집</a>"
                    html shouldContain "edit=true"
                }
            }

            `when`("edit=true 로 상세를 펼치면") {
                then("전 입력이 활성화되고 저장 버튼과 취소 링크가 보인다") {
                    val saved = saveFood("토글편집음식")

                    val html = getList("?page=1&detail=${saved.id}&edit=true").response.contentAsString

                    detailFieldIds.forEach { id -> detailFieldTag(html, id) shouldNotContain "disabled" }
                    html shouldContain ">저장</button>"
                    html shouldContain ">취소</a>"
                }
            }
        }

        given("상세 모달 버튼 규격") {
            `when`("상세 모달의 버튼을 렌더링하면") {
                then("공통 규격에 역할별 색 변형을 적용한다") {
                    val saved = saveFood("버튼규격음식")

                    val readOnly = getList("?detail=${saved.id}").response.contentAsString
                    readOnly shouldContain "btn btn-neutral"
                    readOnly shouldContain "btn btn-danger"

                    val editing = getList("?detail=${saved.id}&edit=true").response.contentAsString
                    editing shouldContain "btn btn-primary"
                    editing shouldContain "btn btn-neutral"
                }
            }
        }

        given("음식 컬럼 수정") {
            `when`("모달 폼에서 전 컬럼을 수정 제출하면") {
                then("반영하고 목록으로 리다이렉트한다") {
                    val saved = saveFood("수정전이름")

                    mockMvc.post("/admin/foods/${saved.id}") {
                        cookie(adminCookie())
                        param("page", "1")
                        param("koreanName", "수정후이름")
                        param("description", "수정된 설명")
                        param("spiciness", "3")
                        param("contentStatus", "PENDING_REVIEW")
                        param("imageRef", "food/1.png")
                        param("nameTranslationsJson", """{"en":"Edited"}""")
                        param("descriptionTranslationsJson", """{"en":"Edited desc"}""")
                        param("ingredientsJson", """[{"code":"PORK","inclusion_percent":80}]""")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/list?page=1&updated=${saved.id}")
                    }

                    val updated = foodJpaRepository.findById(saved.id).get()
                    updated.koreanName shouldBe "수정후이름"
                    updated.description shouldBe "수정된 설명"
                    updated.spiciness shouldBe 3
                    updated.contentStatus shouldBe FoodContentStatus.PENDING_REVIEW
                    updated.imageRef shouldBe "food/1.png"
                    updated.nameTranslations shouldBe mapOf("en" to "Edited")
                    updated.ingredients!!.single().code shouldBe "PORK"
                }
            }

            `when`("빈 ingredientsJson 과 빈 imageRef 로 제출하면") {
                then("각각 미조사(null)로 반영된다") {
                    val saved = saveFood("널수정이름")

                    mockMvc.post("/admin/foods/${saved.id}") {
                        cookie(adminCookie())
                        param("page", "1")
                        param("koreanName", "널수정이름")
                        param("description", "설명")
                        param("spiciness", "0")
                        param("contentStatus", "FAILED")
                        param("imageRef", "")
                        param("nameTranslationsJson", "{}")
                        param("descriptionTranslationsJson", "{}")
                        param("ingredientsJson", "")
                    }.andExpect { status { is3xxRedirection() } }

                    val updated = foodJpaRepository.findById(saved.id).get()
                    updated.imageRef shouldBe null
                    updated.ingredients shouldBe null
                }
            }

            `when`("잘못된 JSON 으로 제출하면") {
                then("오류 파라미터와 함께 상세를 다시 열고 데이터를 바꾸지 않는다") {
                    val saved = saveFood("JSON오류이름")

                    mockMvc.post("/admin/foods/${saved.id}") {
                        cookie(adminCookie())
                        param("page", "1")
                        param("koreanName", "JSON오류이름")
                        param("description", "설명")
                        param("spiciness", "0")
                        param("contentStatus", "FAILED")
                        param("imageRef", "")
                        param("nameTranslationsJson", "{잘못된}")
                        param("descriptionTranslationsJson", "{}")
                        param("ingredientsJson", "")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/list?page=1&detail=${saved.id}&edit=true&error=invalid-json")
                    }

                    foodJpaRepository.findById(saved.id).get().koreanName shouldBe "JSON오류이름"
                }
            }

            `when`("존재하지 않는 음식을 수정 제출하면") {
                then("not-found 오류 파라미터와 anchor 로 리다이렉트한다") {
                    mockMvc.post("/admin/foods/999999") {
                        cookie(adminCookie())
                        param("page", "1")
                        param("koreanName", "없는음식")
                        param("description", "설명")
                        param("spiciness", "0")
                        param("contentStatus", "FAILED")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/list?page=1&error=not-found")
                    }
                }
            }

            `when`("공백 이름으로 수정 제출하면") {
                then("invalid-name 오류 파라미터와 anchor 로 모달을 다시 연다") {
                    val saved = saveFood("이름검증대상")

                    mockMvc.post("/admin/foods/${saved.id}") {
                        cookie(adminCookie())
                        param("page", "1")
                        param("koreanName", "   ")
                        param("description", "설명")
                        param("spiciness", "0")
                        param("contentStatus", "FAILED")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/list?page=1&detail=${saved.id}&edit=true&error=invalid-name")
                    }

                    foodJpaRepository.findById(saved.id).get().koreanName shouldBe "이름검증대상"
                }
            }

            `when`("다른 음식과 중복되는 이름으로 수정하면") {
                then("중복 오류 파라미터로 리다이렉트한다") {
                    saveFood("중복대상이름")
                    val saved = saveFood("중복시도이름")

                    mockMvc.post("/admin/foods/${saved.id}") {
                        cookie(adminCookie())
                        param("page", "1")
                        param("koreanName", "중복대상이름")
                        param("description", "설명")
                        param("spiciness", "0")
                        param("contentStatus", "FAILED")
                        param("imageRef", "")
                        param("nameTranslationsJson", "{}")
                        param("descriptionTranslationsJson", "{}")
                        param("ingredientsJson", "")
                    }.andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/list?page=1&detail=${saved.id}&edit=true&error=duplicate-name")
                    }
                }
            }
        }

        given("음식명 검색") {
            `when`("q 파라미터로 검색하면") {
                then("음식명이 부분 일치하는 음식만 렌더링한다") {
                    saveFood("검색렌더김치찌개")
                    saveFood("검색렌더순두부찌개")

                    val result = getList("?q=김치")

                    result.response.contentAsString shouldContain "검색렌더김치찌개"
                    result.response.contentAsString shouldNotContain "검색렌더순두부찌개"
                    listPageOf(result).totalCount shouldBe 1
                }
            }

            `when`("q 없이 조회하면") {
                then("기존과 동일하게 전체 음식을 렌더링한다") {
                    saveFood("검색렌더김치찌개")
                    saveFood("검색렌더순두부찌개")

                    listPageOf(getList()).totalCount shouldBe 2
                }
            }
        }

        given("음식명 검색 상태 유지") {
            val encodedQ = URLEncoder.encode("김치", StandardCharsets.UTF_8)

            fun updateParams(koreanName: String, nameTranslationsJson: String = "{}"): Map<String, String> = mapOf(
                "page" to "1",
                "koreanName" to koreanName,
                "description" to "설명",
                "spiciness" to "0",
                "contentStatus" to "FAILED",
                "imageRef" to "",
                "nameTranslationsJson" to nameTranslationsJson,
                "descriptionTranslationsJson" to "{}",
                "ingredientsJson" to "",
            )

            fun postUpdate(id: Long, q: String, koreanName: String, nameTranslationsJson: String = "{}") =
                mockMvc.post("/admin/foods/$id") {
                    cookie(adminCookie())
                    param("q", q)
                    updateParams(koreanName, nameTranslationsJson).forEach { (k, v) -> param(k, v) }
                }

            `when`("검색 상태에서 결과가 여러 페이지면") {
                then("페이지 이동·상세보기 링크가 검색어를 유지한다") {
                    foodJpaRepository.saveAll((1..201).map { Food(koreanName = "유지김치$it", description = "구수한 유지김치$it") })

                    val html = getList("?q=김치").response.contentAsString

                    html shouldContain "page=2&amp;q=$encodedQ"
                    html shouldContain "q=$encodedQ&amp;status=&amp;detail="
                }
            }

            `when`("검색 상태에서 상세를 편집 모드로 열면") {
                then("편집 관련 링크와 수정 폼 hidden 입력이 검색어를 유지한다") {
                    val saved = saveFood("유지편집김치찌개")

                    val html = getList("?q=김치&detail=${saved.id}&edit=true").response.contentAsString

                    html shouldContain "q=$encodedQ&amp;status=&amp;detail=${saved.id}"
                    html shouldContain "name=\"q\" value=\"김치\""
                }
            }

            `when`("검색어와 함께 수정을 제출하면") {
                then("성공 redirect 가 인코딩된 검색어와 앵커를 유지한다") {
                    val saved = saveFood("유지수정김치찌개")

                    postUpdate(saved.id, "김치", "유지수정김치찌개").andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/list?page=1&q=$encodedQ&updated=${saved.id}")
                    }
                }
            }

            `when`("검색어와 함께 잘못된 JSON 으로 제출하면") {
                then("오류 redirect 도 검색어를 유지한다") {
                    val saved = saveFood("유지오류김치찌개")

                    postUpdate(saved.id, "김치", "유지오류김치찌개", nameTranslationsJson = "{잘못된}").andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/list?page=1&q=$encodedQ&detail=${saved.id}&edit=true&error=invalid-json")
                    }
                }
            }

            `when`("공백뿐인 검색어로 제출하면") {
                then("redirect 에 q 파라미터를 붙이지 않는다") {
                    val saved = saveFood("유지블랭크김치찌개")

                    postUpdate(saved.id, "   ", "유지블랭크김치찌개").andExpect {
                        status { is3xxRedirection() }
                        redirectedUrl("/admin/foods/list?page=1&updated=${saved.id}")
                    }
                }
            }
        }

        given("음식명 검색 빈 결과") {
            `when`("일치하는 음식이 없는 검색어로 조회하면") {
                then("빈 결과 안내와 전체 목록 복귀 링크를 렌더링한다") {
                    saveFood("빈결과된장찌개")

                    val html = getList("?q=아무도없는음식명").response.contentAsString

                    html shouldContain "검색 결과가 없습니다"
                    html shouldContain "href=\"/admin/foods/list\">전체 목록"
                }
            }

            `when`("검색 없이 음식이 하나도 없으면") {
                then("기존 빈 목록 안내를 유지하고 전체 목록 링크는 없다") {
                    val html = getList().response.contentAsString

                    html shouldContain "표시할 음식이 없습니다"
                    html shouldNotContain ">전체 목록"
                }
            }
        }
    }
}
