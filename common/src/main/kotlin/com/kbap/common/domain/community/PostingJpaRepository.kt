package com.kbap.common.domain.community

import com.kbap.common.domain.community.model.Posting
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PostingJpaRepository : JpaRepository<Posting, Long> {
    @Query("select p from Posting p where :cursor is null or p.id < :cursor order by p.id desc")
    fun findFeedPage(@Param("cursor") cursor: Long?, pageable: Pageable): List<Posting>

    // 게스트 게이트 판정 전용 — LIMIT 프로젝션으로 깊은 커서에도 스캔을 페이지 크기+1 행에 고정한다.
    @Query("select p.id from Posting p where p.id >= :cursor order by p.id")
    fun findIdsFrom(@Param("cursor") cursor: Long, pageable: Pageable): List<Long>
}
