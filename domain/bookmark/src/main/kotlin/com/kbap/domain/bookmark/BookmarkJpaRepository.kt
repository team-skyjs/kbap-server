package com.kbap.domain.bookmark

import com.kbap.domain.bookmark.model.Bookmark
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

internal interface BookmarkJpaRepository : JpaRepository<Bookmark, Long> {
    fun findByMemberIdAndFoodId(memberId: Long, foodId: Long): Bookmark?

    @Query(
        """
        select b from Bookmark b
        where b.memberId = :memberId
          and (:cursor is null or b.id < :cursor)
        order by b.id desc
        """,
    )
    fun findPage(
        @Param("memberId") memberId: Long,
        @Param("cursor") cursor: Long?,
        pageable: Pageable,
    ): List<Bookmark>
}
