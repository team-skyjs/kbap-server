package com.kbap.common.domain.image

import com.kbap.common.domain.image.model.UploadedImage
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface UploadedImageJpaRepository : JpaRepository<UploadedImage, Long> {
    fun countByInstallationIdAndCreatedAtAfter(installationId: String, createdAt: LocalDateTime): Long

    fun findByPath(path: String): UploadedImage?

    fun findByPathIn(paths: Collection<String>): List<UploadedImage>

    @Query(
        nativeQuery = true,
        value = "select u.id from uploaded_image u where $ORPHAN and u.id > :afterId order by u.id limit :size",
    )
    fun findOrphanIds(
        @Param("before") before: LocalDateTime,
        @Param("afterId") afterId: Long,
        @Param("size") size: Int,
    ): List<Long>

    @Query(
        nativeQuery = true,
        value = "select count(*) from uploaded_image u where $ORPHAN and locate(:segment, u.object_path) > 0",
    )
    fun countOrphansIn(@Param("before") before: LocalDateTime, @Param("segment") segment: String): Long

    @Modifying
    @Query(
        nativeQuery = true,
        value = "update uploaded_image u set u.status = 'DELETED', u.updated_at = now(6) where u.id = :id and $ORPHAN",
    )
    fun deleteIfOrphan(@Param("id") id: Long, @Param("before") before: LocalDateTime): Int

    companion object {
        val CLEANUP_SEGMENTS = listOf("images/review/", "images/community/", "images/feedback/")

        private const val CLEANUP_PURPOSE =
            "(locate('images/review/', u.object_path) > 0 " +
                "or locate('images/community/', u.object_path) > 0 " +
                "or locate('images/feedback/', u.object_path) > 0)"

        private const val UNREFERENCED =
            "not exists (select 1 from food_review r " +
                "where json_search(r.image_refs, 'one', concat('%', u.object_path)) is not null) " +
                "and not exists (select 1 from community_post p " +
                "where json_search(p.image_refs, 'one', concat('%', u.object_path)) is not null) " +
                "and not exists (select 1 from feedback fb " +
                "where json_search(fb.image_refs, 'one', concat('%', u.object_path)) is not null) " +
                "and not exists (select 1 from member m " +
                "where right(m.profile_image_url, char_length(u.object_path)) collate utf8mb4_0900_ai_ci = u.object_path) " +
                "and not exists (select 1 from orders o " +
                "where right(o.image_path, char_length(u.object_path)) = u.object_path)"

        const val ORPHAN =
            "u.status = 'ACTIVE' and u.created_at < :before and $CLEANUP_PURPOSE and $UNREFERENCED"
    }
}
