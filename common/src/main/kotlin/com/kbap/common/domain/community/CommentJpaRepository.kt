package com.kbap.common.domain.community

import com.kbap.common.domain.community.model.Comment
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CommentJpaRepository : JpaRepository<Comment, Long> {
    @Query(
        """
        select c from Comment c
        where c.postId = :postId
          and c.parentId is null
          and (:cursor is null or c.id > :cursor)
        order by c.id asc
        """,
    )
    fun findTopLevelPage(@Param("postId") postId: Long, @Param("cursor") cursor: Long?, pageable: Pageable): List<Comment>

    fun findByParentIdInOrderByIdAsc(parentIds: Collection<Long>): List<Comment>

    @Query(
        """
        select c.postId as postId, count(c.id) as commentCount
        from Comment c
        where c.postId in :postIds
        group by c.postId
        """,
    )
    fun countByPostIds(@Param("postIds") postIds: Collection<Long>): List<PostCommentCount>

    @Modifying
    @Query("update Comment c set c.status = com.kbap.common.domain.EntityStatus.DELETED where c.parentId = :parentId")
    fun softDeleteReplies(@Param("parentId") parentId: Long)
}

interface PostCommentCount {
    val postId: Long
    val commentCount: Long
}
