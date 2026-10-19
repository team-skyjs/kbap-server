package com.kbap.common.domain.image

import com.kbap.common.domain.image.model.UploadedImage
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param

interface BotPhotoJpaRepository : Repository<UploadedImage, Long> {
    @Query(nativeQuery = true, value = """
        select u.object_path as path, u.member_id as memberId, u.status as status,
          case when m.is_bot = 1 and m.status = 'ACTIVE' and m.member_status = 'ACTIVE' then 'ACTIVE_BOT' else 'OTHER' end as ownerKind
        from uploaded_image u left join member m on m.id = u.member_id
        where u.object_path like :pattern escape '\\' order by u.object_path
    """)
    fun getRecords(@Param("pattern") pattern: String): List<BotPhotoRecord>

    @Query(nativeQuery = true, value = """
        select (
          exists (select 1 from food_review r left join member m on m.id = r.member_id
            where (m.id is null or m.is_bot = 0) and json_search(r.image_refs, 'one', concat('%', replace(replace(replace(:path, '\\', '\\\\'), '%', '\\%'), '_', '\\_'))) is not null)
          or exists (select 1 from community_post p where json_search(p.image_refs, 'one', concat('%', replace(replace(replace(:path, '\\', '\\\\'), '%', '\\%'), '_', '\\_'))) is not null)
          or exists (select 1 from feedback f where json_search(f.image_refs, 'one', concat('%', replace(replace(replace(:path, '\\', '\\\\'), '%', '\\%'), '_', '\\_'))) is not null)
          or exists (select 1 from member m where right(m.profile_image_url, char_length(:path)) = :path)
          or exists (select 1 from orders o where right(o.image_path, char_length(:path)) = :path)
          or exists (select 1 from order_item i where right(i.image_path, char_length(:path)) = :path)
        )
    """)
    fun hasOrdinaryReference(@Param("path") path: String): Long
    @Query(nativeQuery = true, value = """
        select candidate.path from json_table(:pathsJson, '$[*]' columns(path varchar(512) path '$')) candidate
        where (
          exists (select 1 from food_review r left join member m on m.id = r.member_id
            where (m.id is null or m.is_bot = 0) and json_search(r.image_refs, 'one', concat('%', replace(replace(replace(candidate.path, '\\', '\\\\'), '%', '\\%'), '_', '\\_'))) is not null)
          or exists (select 1 from community_post p where json_search(p.image_refs, 'one', concat('%', replace(replace(replace(candidate.path, '\\', '\\\\'), '%', '\\%'), '_', '\\_'))) is not null)
          or exists (select 1 from feedback f where json_search(f.image_refs, 'one', concat('%', replace(replace(replace(candidate.path, '\\', '\\\\'), '%', '\\%'), '_', '\\_'))) is not null)
          or exists (select 1 from member m where right(m.profile_image_url, char_length(candidate.path)) = cast(candidate.path as binary))
          or exists (select 1 from orders o where right(o.image_path, char_length(candidate.path)) = cast(candidate.path as binary))
          or exists (select 1 from order_item i where right(i.image_path, char_length(candidate.path)) = cast(candidate.path as binary))
        )
    """)
    fun getOrdinaryReferencedPaths(@Param("pathsJson") pathsJson: String): List<String>

}

interface BotPhotoRecord {
    val path: String
    val memberId: Long?
    val status: String
    val ownerKind: String
}
