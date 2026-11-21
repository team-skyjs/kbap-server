package com.kbap.common.domain.bookmark

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.bookmark.model.Bookmark
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class BookmarkJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var repository: BookmarkJpaRepository

    init {
        given("여러 회원의 북마크") {
            `when`("회원 목록으로 조회하면") {
                repository.deleteAll()
                repository.saveAll(listOf(Bookmark(1L, 10L), Bookmark(1L, 11L), Bookmark(2L, 20L), Bookmark(3L, 30L)))
                val deleted = repository.save(Bookmark(2L, 21L))
                deleted.delete()
                repository.save(deleted)

                then("그 회원들의 살아 있는 북마크만 나온다") {
                    repository.findByMemberIdIn(listOf(1L, 2L)).map { it.memberId to it.foodId }.toSet() shouldBe
                        setOf(1L to 10L, 1L to 11L, 2L to 20L)
                }
            }
        }
    }
}
