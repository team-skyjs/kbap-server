package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentFailureKind
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.matchers.shouldBe
import org.springframework.test.web.servlet.get

@IntegrationTest
class AdminFoodCatalogControllerTest : AdminFoodCatalogTestSupport() {
    init {
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

            `when`("검색어에 LIKE 와일드카드가 있으면") {
                then("문자 그대로 일치하는 건만 내려준다") {
                    saveFood("김치찌개")
                    saveFood("100%생과일주스")
                    saveFood("소금_설탕구이")

                    mockMvc.get(path) {
                        header("Authorization", "Bearer ${tokenOf(MemberRole.ADMIN)}")
                        param("q", "%")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.totalCount") { value(1) }
                        jsonPath("$.payload.items[0].koreanName") { value("100%생과일주스") }
                    }

                    mockMvc.get(path) {
                        header("Authorization", "Bearer ${tokenOf(MemberRole.ADMIN)}")
                        param("q", "_")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.totalCount") { value(1) }
                        jsonPath("$.payload.items[0].koreanName") { value("소금_설탕구이") }
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

            `when`("failureKind 필터를 주면") {
                then("해당 실패 유형 건만 내려준다") {
                    foodJpaRepository.save(
                        Food(
                            koreanName = "반려찌개",
                            description = "설명",
                            contentStatus = FoodContentStatus.FAILED,
                            contentFailureKind = FoodContentFailureKind.ADMIN_REJECTED,
                        ),
                    )
                    foodJpaRepository.save(
                        Food(
                            koreanName = "비음식찌개",
                            description = "설명",
                            contentStatus = FoodContentStatus.FAILED,
                            contentFailureKind = FoodContentFailureKind.NOT_FOOD,
                        ),
                    )

                    getList("?failureKind=ADMIN_REJECTED").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].koreanName") { value("반려찌개") }
                        jsonPath("$.payload.totalCount") { value(1) }
                    }

                    getList("?status=FAILED&failureKind=NOT_FOOD").andExpect {
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].koreanName") { value("비음식찌개") }
                    }
                }
            }

            `when`("deleted=true 를 주면") {
                then("삭제된 음식만 필터 조합과 함께 내려준다") {
                    saveFood("활성찌개")
                    val kimchi = saveFood("김치찌개")
                    val doenjang = saveFood("된장찌개")
                    deleteFood(kimchi.id).andExpect { status { isOk() } }
                    deleteFood(doenjang.id).andExpect { status { isOk() } }

                    getList("?deleted=true").andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items.length()") { value(2) }
                        jsonPath("$.payload.totalCount") { value(2) }
                    }

                    getList("?deleted=true&q=김치").andExpect {
                        jsonPath("$.payload.items.length()") { value(1) }
                        jsonPath("$.payload.items[0].id") { value(kimchi.id) }
                    }

                    getList().andExpect { jsonPath("$.payload.totalCount") { value(1) } }
                }
            }

            `when`("영어 이름 번역이 있으면") {
                then("items 의 englishName 에 en 값이, 없으면 null 이 실린다") {
                    foodJpaRepository.save(
                        Food(
                            koreanName = "된장찌개",
                            description = "설명",
                            nameTranslations = mapOf("en" to "Soybean paste stew"),
                        ),
                    )
                    saveFood("김치찌개")

                    getList().andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].englishName") { value(null) }
                        jsonPath("$.payload.items[1].englishName") { value("Soybean paste stew") }
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
                        jsonPath("$.payload.deleted") { value(false) }
                        jsonPath("$.payload.version") { exists() }
                        jsonPath("$.payload.createdAt") { exists() }
                        jsonPath("$.payload.updatedAt") { exists() }
                    }
                }
            }

            `when`("성분 미조사(null) 음식을 조회하면") {
                then("ingredients 를 null 로 내려 빈 배열(조사 완료)과 구분한다") {
                    val food = foodJpaRepository.save(
                        Food(koreanName = "미조사찌개", description = "설명", ingredients = null),
                    )

                    val body = getDetail(food.id).andExpect { status { isOk() } }
                        .andReturn().response.contentAsString
                    mapper.readTree(body).path("payload").path("ingredients").isNull shouldBe true
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
    }
}
