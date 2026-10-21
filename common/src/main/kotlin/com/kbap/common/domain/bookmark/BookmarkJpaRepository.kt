package com.kbap.common.domain.bookmark

import com.kbap.common.domain.bookmark.model.Bookmark
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface BookmarkJpaRepository : JpaRepository<Bookmark, Long> {
    @Query("select new com.kbap.common.domain.bookmark.BookmarkListingEntry(b.id, b.foodId) from Bookmark b where b.memberId = :memberId order by b.id")
    fun findListingEntries(@Param("memberId") memberId: Long): List<BookmarkListingEntry>

    fun findByMemberIdAndFoodId(memberId: Long, foodId: Long): Bookmark?

    fun findByMemberIdAndFoodIdIn(memberId: Long, foodIds: Collection<Long>): List<Bookmark>

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
