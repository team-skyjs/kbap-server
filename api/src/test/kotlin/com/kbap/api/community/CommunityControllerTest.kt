package com.kbap.api.community

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class CommunityControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        val path = "/api/community/posts"

        fun seedMember(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, nickname, country_code, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, ?, 'KR', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "community-test-$memberId")
                    ps.setString(3, "커뮤니티$memberId")
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedFood(id: Long, koreanName: String, contentStatus: String = "READY"): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (id, korean_name, description, spiciness, name_translations,
                                      description_translations, ingredients, content_status, status,
                                      created_at, updated_at)
                    VALUES (?, ?, '설명', 0, '{}', '{}', '[]', ?, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, id)
                    ps.setString(2, koreanName)
                    ps.setString(3, contentStatus)
                    ps.executeUpdate()
                }
            }

        fun seedVerifiedImage(memberId: Long, imagePath: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes,
                                                status, created_at, updated_at)
                    VALUES (?, ?, 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6))
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, imagePath)
                    ps.executeUpdate()
                }
            }

        fun communityImagePath(memberId: Long, name: String) = "images/community/2026/08/${memberId}_$name.jpg"

        fun createBody(
            content: String? = "오늘 김치찌개 최고",
            imagePaths: List<String>? = null,
            foodIds: List<Long>? = null,
        ): String = mapper.writeValueAsString(
            buildMap {
                content?.let { put("content", it) }
                imagePaths?.let { put("imagePaths", it) }
                foodIds?.let { put("foodIds", it) }
            },
        )

        fun create(token: String?, body: String): ResultActionsDsl =
            mockMvc.post(path) {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }

        fun update(token: String, postId: Long, body: String): ResultActionsDsl =
            mockMvc.put("$path/$postId") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }

        fun remove(token: String, postId: Long): ResultActionsDsl =
            mockMvc.delete("$path/$postId") {
                header("Authorization", "Bearer $token")
            }

        fun statusOf(postId: Long): String =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT status FROM community_post WHERE id = ?").use { ps ->
                    ps.setLong(1, postId)
                    ps.executeQuery().use { rs ->
                        rs.next().shouldBeTrue()
                        rs.getString(1)
                    }
                }
            }

        fun postingIdOf(result: ResultActionsDsl): Long =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))
                .path("payload").path("postId").asLong()

        fun editedAtOf(postId: Long): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT edited_at FROM community_post WHERE id = ?").use { ps ->
                    ps.setLong(1, postId)
                    ps.executeQuery().use { rs ->
                        rs.next().shouldBeTrue()
                        rs.getString(1)
                    }
                }
            }

        given("게시글 작성 API — POST /api/community/posts") {
            seedFood(9100L, "커뮤니티김치찌개")
            seedFood(9101L, "커뮤니티된장찌개")
            seedFood(9102L, "커뮤니티불고기")
            seedFood(9103L, "커뮤니티준비중", contentStatus = "FAILED")

            `when`("본문만으로 작성하면") {
                then("200 과 게시글을 반환한다") {
                    val token = accessToken(9100L)

                    create(token, createBody(content = "본문만 있는 글")).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.content") { value("본문만 있는 글") }
                        jsonPath("$.payload.imageUrls.length()") { value(0) }
                        jsonPath("$.payload.foodIds.length()") { value(0) }
                        jsonPath("$.payload.editedAt") { value(null) }
                    }
                }
            }

            `when`("사진 4장을 첨부해 작성하면") {
                then("순서가 보존된 이미지 URL 을 반환한다") {
                    val token = accessToken(9101L)
                    val paths = (1..4).map { communityImagePath(9101L, "photo$it") }
                    paths.forEach { seedVerifiedImage(9101L, it) }

                    create(token, createBody(imagePaths = paths)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.imageUrls.length()") { value(4) }
                        jsonPath("$.payload.imageUrls[0]") { value("https://cdn.test/${paths[0]}") }
                        jsonPath("$.payload.imageUrls[3]") { value("https://cdn.test/${paths[3]}") }
                    }
                }
            }

            `when`("음식 태그 3개를 첨부해 작성하면") {
                then("태그가 저장된다") {
                    val token = accessToken(9102L)

                    create(token, createBody(foodIds = listOf(9100L, 9101L, 9102L))).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.foodIds.length()") { value(3) }
                        jsonPath("$.payload.foodIds[0]") { value(9100) }
                    }
                }
            }

            `when`("본문이 비어 있으면") {
                then("400 으로 거절한다") {
                    val token = accessToken(9103L)

                    create(token, createBody(content = null)).andExpect { status { isBadRequest() } }
                    create(token, createBody(content = "  ")).andExpect { status { isBadRequest() } }
                }
            }

            `when`("본문이 2000자를 넘으면") {
                then("400 으로 거절한다") {
                    val token = accessToken(9104L)

                    create(token, createBody(content = "가".repeat(2001))).andExpect { status { isBadRequest() } }
                }
            }

            `when`("사진이 5장이면") {
                then("400 으로 거절한다") {
                    val token = accessToken(9105L)
                    val paths = (1..5).map { communityImagePath(9105L, "over$it") }
                    paths.forEach { seedVerifiedImage(9105L, it) }

                    create(token, createBody(imagePaths = paths)).andExpect { status { isBadRequest() } }
                }
            }

            `when`("음식 태그가 4개이면") {
                then("400 으로 거절한다") {
                    val token = accessToken(9106L)

                    create(token, createBody(foodIds = listOf(9100L, 9101L, 9102L, 9100L)))
                        .andExpect { status { isBadRequest() } }
                }
            }

            `when`("같은 음식을 중복 태그하면") {
                then("COMMUNITY-004 로 거절한다") {
                    val token = accessToken(9107L)

                    create(token, createBody(foodIds = listOf(9100L, 9100L))).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-004") }
                    }
                }
            }

            `when`("게스트가 작성을 시도하면") {
                then("401 로 거절한다") {
                    create(null, createBody()).andExpect { status { isUnauthorized() } }
                }
            }

            `when`("타인이 업로드한 이미지 경로를 쓰면") {
                then("COMMUNITY-003 으로 거절한다") {
                    val token = accessToken(9108L)
                    seedMember(9109L)
                    val othersPath = communityImagePath(9109L, "stolen")
                    seedVerifiedImage(9109L, othersPath)

                    create(token, createBody(imagePaths = listOf(othersPath))).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-003") }
                    }
                }
            }

            `when`("업로드 기록이 없는 이미지 경로를 쓰면") {
                then("COMMUNITY-003 으로 거절한다") {
                    val token = accessToken(9110L)

                    create(token, createBody(imagePaths = listOf(communityImagePath(9110L, "ghost")))).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-003") }
                    }
                }
            }

            `when`("커뮤니티 용도가 아닌 이미지 경로를 쓰면") {
                then("COMMUNITY-003 으로 거절한다") {
                    val token = accessToken(9111L)
                    val reviewPath = "images/review/2026/08/9111_other.jpg"
                    seedVerifiedImage(9111L, reviewPath)

                    create(token, createBody(imagePaths = listOf(reviewPath))).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-003") }
                    }
                }
            }

            `when`("등록되지 않은 음식을 태그하면") {
                then("COMMUNITY-004 로 거절한다") {
                    val token = accessToken(9112L)

                    create(token, createBody(foodIds = listOf(999999L))).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-004") }
                    }
                }
            }

            `when`("READY 가 아닌 음식을 태그하면") {
                then("COMMUNITY-004 로 거절한다") {
                    val token = accessToken(9113L)

                    create(token, createBody(foodIds = listOf(9103L))).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-004") }
                    }
                }
            }

            `when`("작성 직후 조회하면") {
                then("editedAt 은 비어 있다") {
                    val token = accessToken(9114L)
                    val postId = postingIdOf(
                        create(token, createBody(content = "수정 전")).andExpect { status { isOk() } },
                    )

                    editedAtOf(postId).shouldBeNull()
                }
            }
        }

        given("게시글 수정 API — PUT /api/community/posts/{postId}") {
            seedFood(9200L, "커뮤니티수정김치")
            seedFood(9201L, "커뮤니티수정된장")

            fun createPosting(token: String, body: String = createBody()): Long =
                postingIdOf(create(token, body).andExpect { status { isOk() } })

            `when`("본문·사진·태그를 바꾸면") {
                then("반영되고 editedAt 이 채워진다") {
                    val token = accessToken(9200L)
                    val postId = createPosting(token)
                    val newPath = communityImagePath(9200L, "edited")
                    seedVerifiedImage(9200L, newPath)

                    update(
                        token,
                        postId,
                        createBody(content = "수정했다", imagePaths = listOf(newPath), foodIds = listOf(9200L)),
                    ).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.content") { value("수정했다") }
                        jsonPath("$.payload.imageUrls.length()") { value(1) }
                        jsonPath("$.payload.foodIds[0]") { value(9200) }
                        jsonPath("$.payload.editedAt") { exists() }
                    }
                    editedAtOf(postId).shouldNotBeNull()
                }
            }

            `when`("사진과 태그를 생략해 수정하면") {
                then("사진과 태그가 제거된다") {
                    val token = accessToken(9201L)
                    val path = communityImagePath(9201L, "removed")
                    seedVerifiedImage(9201L, path)
                    val postId = createPosting(
                        token,
                        createBody(imagePaths = listOf(path), foodIds = listOf(9200L)),
                    )

                    update(token, postId, createBody(content = "사진 뺐다")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.imageUrls.length()") { value(0) }
                        jsonPath("$.payload.foodIds.length()") { value(0) }
                    }
                }
            }

            `when`("타인의 글을 수정하려 하면") {
                then("403 COMMUNITY-002 로 거절한다") {
                    val ownerToken = accessToken(9202L)
                    val postId = createPosting(ownerToken)
                    val otherToken = accessToken(9203L)

                    update(otherToken, postId, createBody(content = "남의 글")).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("COMMUNITY-002") }
                    }
                }
            }

            `when`("없는 글을 수정하려 하면") {
                then("COMMUNITY-001 로 거절한다") {
                    val token = accessToken(9204L)

                    update(token, 99999999L, createBody(content = "없는 글")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-001") }
                    }
                }
            }

            `when`("수정 내용이 본문 제약을 위반하면") {
                then("400 으로 거절한다") {
                    val token = accessToken(9205L)
                    val postId = createPosting(token)

                    update(token, postId, createBody(content = "가".repeat(2001)))
                        .andExpect { status { isBadRequest() } }
                }
            }

            `when`("수정에 미소유 이미지를 쓰면") {
                then("COMMUNITY-003 으로 거절한다") {
                    val token = accessToken(9206L)
                    val postId = createPosting(token)

                    update(token, postId, createBody(imagePaths = listOf(communityImagePath(9206L, "ghost"))))
                        .andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("COMMUNITY-003") }
                        }
                }
            }
        }

        given("게시글 삭제 API — DELETE /api/community/posts/{postId}") {
            fun createPosting(token: String): Long =
                postingIdOf(create(token, createBody()).andExpect { status { isOk() } })

            `when`("본인 글을 삭제하면") {
                then("200 을 반환하고 row 는 DELETED 로 보존된다") {
                    val token = accessToken(9300L)
                    val postId = createPosting(token)

                    remove(token, postId).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                    statusOf(postId) shouldBe "DELETED"
                }
            }

            `when`("삭제한 글을 다시 수정하려 하면") {
                then("COMMUNITY-001 로 거절한다") {
                    val token = accessToken(9301L)
                    val postId = createPosting(token)
                    remove(token, postId).andExpect { status { isOk() } }

                    update(token, postId, createBody(content = "부활")).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-001") }
                    }
                }
            }

            `when`("삭제한 글을 다시 삭제하려 하면") {
                then("COMMUNITY-001 로 거절한다") {
                    val token = accessToken(9302L)
                    val postId = createPosting(token)
                    remove(token, postId).andExpect { status { isOk() } }

                    remove(token, postId).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMUNITY-001") }
                    }
                }
            }

            `when`("타인의 글을 삭제하려 하면") {
                then("403 COMMUNITY-002 로 거절한다") {
                    val ownerToken = accessToken(9303L)
                    val postId = createPosting(ownerToken)
                    val otherToken = accessToken(9304L)

                    remove(otherToken, postId).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("COMMUNITY-002") }
                    }
                    statusOf(postId) shouldBe "ACTIVE"
                }
            }
        }
    }
}
