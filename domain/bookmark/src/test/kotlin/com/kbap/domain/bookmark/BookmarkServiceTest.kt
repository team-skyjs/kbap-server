package com.kbap.domain.bookmark

import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import com.kbap.core.testsupport.MySqlContainerConfig
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

@SpringBootTest(classes = [BookmarkServiceTestApp::class])
@Import(MySqlContainerConfig::class)
class BookmarkServiceTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: BookmarkService

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val memberId = 100L

        fun clearTables() {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DELETE FROM bookmark")
                    statement.execute("DELETE FROM food")
                }
            }
        }

        fun seedFood(id: Long, koreanName: String, contentStatus: String = "READY") {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "INSERT INTO food (id, korean_name, image_ref, description, spiciness, " +
                            "name_translations, description_translations, content_status, status, created_at, updated_at) " +
                            "VALUES ($id, '$koreanName', NULL, '설명', 0, '{}', '{}', '$contentStatus', 'ACTIVE', " +
                            "NOW(6), NOW(6))",
                    )
                }
            }
        }

        fun countRows(foodId: Long, onlyActive: Boolean): Int {
            val statusClause = if (onlyActive) " and status = 'ACTIVE'" else ""
            return dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT COUNT(*) FROM bookmark WHERE member_id = $memberId AND food_id = $foodId$statusClause",
                    ).use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
            }
        }

        fun bookmarkedFoodIds(cursor: Long? = null) =
            service.findBookmarks(memberId, "ko", cursor).items.map { it.foodId }

        fun activeBookmarkIdOf(foodId: Long): Long =
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "SELECT id FROM bookmark WHERE member_id = $memberId AND food_id = $foodId " +
                            "AND status = 'ACTIVE' ORDER BY id DESC LIMIT 1",
                    ).use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }

        beforeContainer { clearTables() }

        given("음식 북마크 여부 일괄 조회 — findBookmarkedFoodIds") {
            `when`("회원이 요청 음식 집합 중 일부만 북마크했으면") {
                then("북마크한 foodId 만 반환한다") {
                    seedFood(1L, "김치찌개")
                    seedFood(2L, "된장찌개")
                    seedFood(3L, "비빔밥")
                    service.bookmark(memberId, 1L)
                    service.bookmark(memberId, 3L)

                    service.findBookmarkedFoodIds(memberId, listOf(1L, 2L, 3L)) shouldContainExactlyInAnyOrder listOf(1L, 3L)
                }
            }

            `when`("memberId 가 null 이면(비회원)") {
                then("쿼리 없이 빈 집합을 반환한다") {
                    seedFood(1L, "김치찌개")
                    service.bookmark(memberId, 1L)

                    service.findBookmarkedFoodIds(null, listOf(1L)) shouldBe emptySet()
                }
            }

            `when`("요청 foodIds 가 비어 있으면") {
                then("빈 집합을 반환한다") {
                    service.findBookmarkedFoodIds(memberId, emptyList()) shouldBe emptySet()
                }
            }

            `when`("북마크를 취소(소프트삭제)한 뒤 조회하면") {
                then("취소한 foodId 는 제외된다") {
                    seedFood(1L, "김치찌개")
                    seedFood(2L, "된장찌개")
                    service.bookmark(memberId, 1L)
                    service.bookmark(memberId, 2L)
                    service.unbookmark(memberId, 1L)

                    service.findBookmarkedFoodIds(memberId, listOf(1L, 2L)) shouldContainExactlyInAnyOrder listOf(2L)
                }
            }
        }

        given("음식 북마크 등록") {
            `when`("READY 음식을 처음 북마크하면") {
                then("활성 북마크가 1건 생성되고 목록에 담긴다") {
                    seedFood(1L, "김치찌개")

                    service.bookmark(memberId, 1L)

                    countRows(1L, onlyActive = true) shouldBe 1
                    bookmarkedFoodIds() shouldContainExactly listOf(1L)
                }
            }

            `when`("같은 음식을 중복 북마크하면") {
                then("예외 없이 성공하고 행은 1개로 유지된다(멱등)") {
                    seedFood(1L, "김치찌개")

                    service.bookmark(memberId, 1L)
                    service.bookmark(memberId, 1L)

                    countRows(1L, onlyActive = false) shouldBe 1
                    countRows(1L, onlyActive = true) shouldBe 1
                    bookmarkedFoodIds() shouldContainExactly listOf(1L)
                }
            }

            `when`("미존재 음식을 북마크하면") {
                then("FOOD_NOT_FOUND 예외를 던진다") {
                    val exception = shouldThrow<BusinessException> {
                        service.bookmark(memberId, 99999L)
                    }
                    exception.errorCode shouldBe ErrorCode.FOOD_NOT_FOUND
                }
            }

            `when`("미완성(INCOMPLETE) 음식을 북마크하면") {
                then("FOOD_NOT_FOUND 예외를 던진다") {
                    seedFood(2L, "미완성찌개", contentStatus = "INCOMPLETE")

                    val exception = shouldThrow<BusinessException> {
                        service.bookmark(memberId, 2L)
                    }
                    exception.errorCode shouldBe ErrorCode.FOOD_NOT_FOUND
                }
            }
        }

        given("음식 북마크 취소") {
            `when`("북마크를 취소하면") {
                then("목록에서 사라지지만 행은 소프트삭제로 남는다") {
                    seedFood(1L, "김치찌개")
                    service.bookmark(memberId, 1L)

                    service.unbookmark(memberId, 1L)

                    countRows(1L, onlyActive = false) shouldBe 1
                    countRows(1L, onlyActive = true) shouldBe 0
                    bookmarkedFoodIds() shouldBe emptyList<Long>()
                }
            }

            `when`("존재하지 않는 북마크를 취소하면") {
                then("예외 없이 멱등하게 통과한다") {
                    seedFood(1L, "김치찌개")

                    shouldNotThrowAny {
                        service.unbookmark(memberId, 1L)
                    }
                }
            }
        }

        given("음식 북마크 재등록") {
            `when`("취소한 음식을 다시 북마크하면") {
                then("새 행이 INSERT 되어 DELETED 1 + ACTIVE 1 로 남고 목록에 다시 담긴다") {
                    seedFood(1L, "김치찌개")
                    service.bookmark(memberId, 1L)
                    service.unbookmark(memberId, 1L)

                    service.bookmark(memberId, 1L)

                    countRows(1L, onlyActive = false) shouldBe 2
                    countRows(1L, onlyActive = true) shouldBe 1
                    bookmarkedFoodIds() shouldContainExactly listOf(1L)
                }
            }

            `when`("음식 셋을 북마크한 뒤 첫 음식을 취소했다가 다시 북마크하면") {
                then("재등록한 음식이 최근 순으로 목록 맨 앞에 온다") {
                    seedFood(1L, "김치찌개")
                    seedFood(2L, "된장찌개")
                    seedFood(3L, "비빔밥")
                    service.bookmark(memberId, 1L)
                    service.bookmark(memberId, 2L)
                    service.bookmark(memberId, 3L)
                    service.unbookmark(memberId, 1L)

                    service.bookmark(memberId, 1L)

                    val ids = bookmarkedFoodIds()
                    ids.firstOrNull() shouldBe 1L
                    ids shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L)
                }
            }
        }

        given("음식 북마크 목록 커서 페이지네이션") {
            `when`("PAGE_SIZE 를 초과해 북마크하면") {
                then("첫 페이지는 PAGE_SIZE 개와 다음 커서를 주고, 다음 페이지가 나머지를 이어 준다") {
                    val total = BookmarkService.PAGE_SIZE + 1
                    val foodIds = (1L..total.toLong()).toList()
                    foodIds.forEach { id ->
                        seedFood(id, "메뉴$id")
                        service.bookmark(memberId, id)
                    }

                    val firstPage = service.findBookmarks(memberId, "ko", null)
                    firstPage.items.size shouldBe BookmarkService.PAGE_SIZE
                    firstPage.hasNext shouldBe true

                    val secondPage = service.findBookmarks(memberId, "ko", firstPage.nextCursor)
                    secondPage.items.size shouldBe 1
                    secondPage.hasNext shouldBe false

                    val collected = firstPage.items.map { it.foodId } + secondPage.items.map { it.foodId }
                    collected shouldContainExactlyInAnyOrder foodIds
                }
            }

            `when`("커서로 쓴 북마크가 취소(소프트삭제)되어도") {
                then("id 값 비교라 커서보다 작은 북마크가 정상 이어진다") {
                    (1L..5L).forEach { id ->
                        seedFood(id, "메뉴$id")
                        service.bookmark(memberId, id)
                    }
                    val cursor = activeBookmarkIdOf(3L)
                    service.unbookmark(memberId, 3L)

                    val page = service.findBookmarks(memberId, "ko", cursor)

                    page.items.map { it.foodId } shouldContainExactly listOf(2L, 1L)
                }
            }
        }
    }
}
