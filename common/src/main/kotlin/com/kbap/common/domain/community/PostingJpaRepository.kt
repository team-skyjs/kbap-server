package com.kbap.common.domain.community

import com.kbap.common.domain.community.model.Posting
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PostingJpaRepository : JpaRepository<Posting, Long> {
    @Query(
        """
        select p from Posting p
        where (:memberId is null or p.memberId = :memberId)
          and (:keyword is null or p.content like :keyword)
        order by p.id desc
        """,
    )
    fun findAdminPage(@Param("memberId") memberId: Long?, @Param("keyword") keyword: String?, pageable: Pageable): Page<Posting>

    // exists(Member) = 탈퇴 작성자 글 숨김 — Member 의 @SQLRestriction(ACTIVE)이 서브쿼리에도 적용된다.
    @Query(
        """
        select p from Posting p
        where (:cursor is null or p.id < :cursor)
          and exists (select m.id from Member m where m.id = p.memberId)
        order by p.id desc
        """,
    )
    fun findPage(@Param("cursor") cursor: Long?, pageable: Pageable): List<Posting>
}
