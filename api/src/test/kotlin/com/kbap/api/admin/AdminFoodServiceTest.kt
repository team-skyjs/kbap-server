package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CyclicBarrier
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class AdminFoodServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: AdminFoodService

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun clearFoods() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DELETE FROM food") }
            }
        }

        fun saveFood(koreanName: String): Long =
            foodJpaRepository.save(Food(koreanName = koreanName, description = "구수한 $koreanName")).id

        fun foodsByNames(names: Set<String>): Map<String, Food> =
            foodJpaRepository.findByKoreanNameIn(names).associateBy { it.koreanName }

        beforeContainer { clearFoods() }

        given("음식 상세 이미지 URL 해석(getFoodDetailOrNull)") {
            `when`("imageRef 가 상대 키면") {
                then("공개 베이스 URL 과 결합한 imageUrl 을 내려준다") {
                    val food = foodJpaRepository.save(
                        Food(koreanName = "이미지키음식", description = "설명", imageRef = "food/img/1.png"),
                    )

                    service.getFoodDetailOrNull(food.id)!!.imageUrl shouldBe "https://cdn.test/food/img/1.png"
                }
            }

            `when`("imageRef 가 없으면") {
                then("imageUrl 도 null 이다") {
                    val id = saveFood("이미지없는음식")

                    service.getFoodDetailOrNull(id)!!.imageUrl shouldBe null
                }
            }

            `when`("imageRef 가 이미 절대 URL 이면") {
                then("원문 그대로 내려준다") {
                    val food = foodJpaRepository.save(
                        Food(koreanName = "절대경로음식", description = "설명", imageRef = "https://other.cdn/x.png"),
                    )

                    service.getFoodDetailOrNull(food.id)!!.imageUrl shouldBe "https://other.cdn/x.png"
                }
            }
        }

        given("음식 상세 JSON 포맷(getFoodDetailOrNull)") {
            `when`("번역·기피 성분이 있는 음식의 상세를 조회하면") {
                then("JSON 필드를 줄바꿈 있는 pretty 포맷으로 내려준다") {
                    val food = foodJpaRepository.save(
                        Food(
                            koreanName = "포맷음식",
                            description = "설명",
                            nameTranslations = mapOf("en" to "Pretty", "ja" to "プリティ"),
                        ),
                    )

                    val detail = service.getFoodDetailOrNull(food.id)!!

                    detail.nameTranslationsJson shouldContain "\n"
                    detail.nameTranslationsJson shouldContain "\"en\""
                }
            }
        }

        given("관리자 음식 수정 상태 자동 보정(updateFood)") {
            val fullTranslationsJson =
                """{"zh-Hans":"번","en":"번","ja":"번","zh-Hant":"번","vi":"번","id":"번","th":"번","ru":"번","es":"번"}"""

            fun completeCommand(
                koreanName: String,
                contentStatus: FoodContentStatus,
                spiciness: Int = 2,
                imageRef: String = "",
                nameTranslationsJson: String = fullTranslationsJson,
            ) = UpdateFoodCommand(
                koreanName = koreanName,
                description = "완비된 설명",
                spiciness = spiciness,
                contentStatus = contentStatus,
                imageRef = imageRef,
                nameTranslationsJson = nameTranslationsJson,
                descriptionTranslationsJson = fullTranslationsJson,
                avoidanceSubstancesJson = """[{"code":"PORK","inclusion_percent":80}]""",
            )

            fun savedStatus(id: Long): FoodContentStatus =
                foodJpaRepository.findById(id).get().contentStatus

            fun saveIncompleteFood(koreanName: String): Long =
                foodJpaRepository.save(Food.incomplete(koreanName)).id

            `when`("검수 이전 상태를 고른 채 텍스트를 완비하고 이미지 없이 저장하면") {
                then("PENDING_IMAGE 로 자동 보정된다") {
                    val id = saveIncompleteFood("보정이미지대기음식")

                    val result = service.updateFood(id, completeCommand("보정이미지대기음식", FoodContentStatus.INCOMPLETE))

                    result shouldBe AdminFoodUpdateResult.UPDATED
                    savedStatus(id) shouldBe FoodContentStatus.PENDING_IMAGE
                }
            }

            `when`("검수 이전 상태를 고른 채 텍스트 완비에 이미지까지 있게 저장하면") {
                then("PENDING_REVIEW 로 자동 보정된다") {
                    val id = saveIncompleteFood("보정검수대기음식")

                    val result = service.updateFood(
                        id,
                        completeCommand("보정검수대기음식", FoodContentStatus.INCOMPLETE, imageRef = "food/img.png"),
                    )

                    result shouldBe AdminFoodUpdateResult.UPDATED
                    savedStatus(id) shouldBe FoodContentStatus.PENDING_REVIEW
                }
            }

            `when`("검수 이전 상태를 고른 채 텍스트가 미완이면") {
                then("INCOMPLETE 로 보정된다") {
                    val id = saveIncompleteFood("보정미완음식")

                    val result = service.updateFood(
                        id,
                        completeCommand("보정미완음식", FoodContentStatus.PENDING_IMAGE, spiciness = Food.SPICINESS_UNASSESSED),
                    )

                    result shouldBe AdminFoodUpdateResult.UPDATED
                    savedStatus(id) shouldBe FoodContentStatus.INCOMPLETE
                }
            }

            `when`("READY 를 직접 지정해 저장하면") {
                then("텍스트가 미완이어도 READY 가 유지된다") {
                    val id = saveIncompleteFood("수동레디음식")

                    val result = service.updateFood(
                        id,
                        completeCommand("수동레디음식", FoodContentStatus.READY, nameTranslationsJson = "{}"),
                    )

                    result shouldBe AdminFoodUpdateResult.UPDATED
                    savedStatus(id) shouldBe FoodContentStatus.READY
                }
            }

            `when`("PENDING_REVIEW 를 직접 지정해 저장하면") {
                then("텍스트가 미완이어도 PENDING_REVIEW 가 유지된다") {
                    val id = saveIncompleteFood("수동검수음식")

                    val result = service.updateFood(
                        id,
                        completeCommand("수동검수음식", FoodContentStatus.PENDING_REVIEW, nameTranslationsJson = "{}"),
                    )

                    result shouldBe AdminFoodUpdateResult.UPDATED
                    savedStatus(id) shouldBe FoodContentStatus.PENDING_REVIEW
                }
            }

            `when`("중복 이름으로 검증에 실패하면") {
                then("상태 보정 없이 기존 상태가 유지된다") {
                    saveFood("보정중복대상음식")
                    val id = saveIncompleteFood("보정중복시도음식")

                    val result = service.updateFood(id, completeCommand("보정중복대상음식", FoodContentStatus.INCOMPLETE))

                    result shouldBe AdminFoodUpdateResult.DUPLICATE_NAME
                    savedStatus(id) shouldBe FoodContentStatus.INCOMPLETE
                }
            }
        }

        given("관리자 음식 시드(seedIncomplete)") {
            `when`("전부 새 이름이면") {
                then("모두 INCOMPLETE 로 생성되고 created 로 센다") {
                    val result = service.seedIncomplete(setOf("시드마라샹궈", "시드탕후루", "시드쌀국수"))

                    result shouldBe SeedIncompleteResult(requested = 3, created = 3, skipped = 0)
                    foodJpaRepository.count() shouldBe 3
                    foodsByNames(setOf("시드마라샹궈", "시드탕후루", "시드쌀국수"))
                        .values.forEach { it.isReady() shouldBe false }
                }
            }

            `when`("정규화되지 않은 표기가 섞여 있으면") {
                then("정규화(NFC·한글만)된 이름으로 저장·중복 판정한다") {
                    val result = service.seedIncomplete(setOf("김치 찌개", "김치찌개", "Kimchi 김치찌개!", "abc123"))

                    result shouldBe SeedIncompleteResult(requested = 1, created = 1, skipped = 0)
                    foodsByNames(setOf("김치찌개")).keys shouldBe setOf("김치찌개")
                    foodJpaRepository.count() shouldBe 1
                }
            }

            `when`("기존 이름과 새 이름이 섞여 있으면") {
                then("새 이름만 생성하고 기존은 skipped 로 센다") {
                    val existingId = saveFood("시드비빔밥")

                    val result = service.seedIncomplete(setOf("시드비빔밥", "시드김치찌개", "시드잡채"))

                    result shouldBe SeedIncompleteResult(requested = 3, created = 2, skipped = 1)
                    foodJpaRepository.count() shouldBe 3
                    foodsByNames(setOf("시드비빔밥")).getValue("시드비빔밥").id shouldBe existingId
                }
            }

            `when`("전부 기존 이름이면") {
                then("생성 없이 skipped 로만 세고 성공한다") {
                    saveFood("시드국밥")
                    saveFood("시드냉면")

                    val result = service.seedIncomplete(setOf("시드국밥", "시드냉면"))

                    result shouldBe SeedIncompleteResult(requested = 2, created = 0, skipped = 2)
                    foodJpaRepository.count() shouldBe 2
                }
            }

            `when`("빈 집합이거나 정규화 후 남는 이름이 없으면") {
                then("쿼리 없이 (0,0,0) 을 돌려준다") {
                    service.seedIncomplete(emptySet()) shouldBe SeedIncompleteResult(requested = 0, created = 0, skipped = 0)
                    service.seedIncomplete(setOf("abc", "123", "  ")) shouldBe SeedIncompleteResult(requested = 0, created = 0, skipped = 0)
                }
            }

            `when`("같은 목록으로 두 번 적재하면") {
                then("두 번째는 created=0 으로 성공하고 행 수가 늘지 않는다") {
                    val names = setOf("멱등마라탕", "멱등탕수육")

                    service.seedIncomplete(names) shouldBe SeedIncompleteResult(requested = 2, created = 2, skipped = 0)
                    service.seedIncomplete(names) shouldBe SeedIncompleteResult(requested = 2, created = 0, skipped = 2)
                    foodJpaRepository.count() shouldBe 2
                }
            }

            `when`("소프트 삭제된 동명 음식만 있으면") {
                then("되살리지도 새로 만들지도 않고 skipped 로 집계한다") {
                    val ghostId = saveFood("유령시드라면")
                    val ghost = foodJpaRepository.findById(ghostId).get()
                    ghost.delete()
                    foodJpaRepository.save(ghost)

                    val result = service.seedIncomplete(setOf("유령시드라면", "생존시드라면"))

                    result shouldBe SeedIncompleteResult(requested = 2, created = 1, skipped = 1)
                    foodsByNames(setOf("생존시드라면")).keys shouldBe setOf("생존시드라면")
                }
            }

            `when`("동일 목록을 두 스레드가 동시에 적재하면") {
                then("각 이름은 정확히 한 행만 저장되고 created 합계도 실제 생성 수와 일치한다") {
                    val names = setOf("경합마라탕", "경합쌀국수", "경합분짜")
                    val barrier = CyclicBarrier(2)

                    val results = (1..2).map {
                        CompletableFuture.supplyAsync {
                            barrier.await()
                            service.seedIncomplete(names)
                        }
                    }.map { it.join() }

                    results.forEach { it.requested shouldBe 3 }
                    results.sumOf { it.created } shouldBe 3
                    foodJpaRepository.count() shouldBe 3
                    foodsByNames(names).keys shouldBe names
                }
            }
        }

        given("관리자 음식 삭제(deleteFood)") {
            fun rawStatus(id: Long): String? =
                dataSource.connection.use { connection ->
                    connection.prepareStatement("SELECT status FROM food WHERE id = ?").use { statement ->
                        statement.setLong(1, id)
                        statement.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                    }
                }

            `when`("존재하는 음식을 삭제하면") {
                then("DELETED 를 반환하고 row 는 DELETED 상태로 보존되며 상세 조회에서 사라진다") {
                    val id = saveFood("삭제불고기")

                    val result = service.deleteFood(id)

                    result shouldBe AdminFoodDeleteResult.DELETED
                    rawStatus(id) shouldBe "DELETED"
                    service.getFoodDetailOrNull(id) shouldBe null
                }
            }

            `when`("존재하지 않는 id 를 삭제하면") {
                then("NOT_FOUND 를 반환한다") {
                    service.deleteFood(999_999) shouldBe AdminFoodDeleteResult.NOT_FOUND
                }
            }

            `when`("이미 삭제된 음식을 다시 삭제하면") {
                then("NOT_FOUND 를 반환하고 상태는 DELETED 그대로다") {
                    val id = saveFood("삭제재시도갈비탕")
                    service.deleteFood(id)

                    service.deleteFood(id) shouldBe AdminFoodDeleteResult.NOT_FOUND
                    rawStatus(id) shouldBe "DELETED"
                }
            }

            `when`("삭제된 음식이 섞여 있는 목록을 조회하면") {
                then("삭제된 음식은 items 와 totalCount 모두에서 제외된다") {
                    val survivorId = saveFood("삭제목록남는음식")
                    val deletedId = saveFood("삭제목록사라지는음식")
                    service.deleteFood(deletedId)

                    val page = service.getFoodPage(1)

                    page.totalCount shouldBe 1
                    page.items.map { it.id } shouldBe listOf(survivorId)
                }
            }
        }
    }
}
