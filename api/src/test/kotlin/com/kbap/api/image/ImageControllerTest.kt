package com.kbap.api.image

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class ImageControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var storage: FakeStorageObjectStore

    init {
        val mapper = jacksonObjectMapper()

        fun seedMember(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps -> ps.setLong(1, memberId); ps.setString(2, "image-test-$memberId"); ps.executeUpdate() }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun countImage(path: String): Int =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT COUNT(*) FROM uploaded_image WHERE object_path = ?").use { ps ->
                    ps.setString(1, path)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun body(path: String, contentType: String, size: Long) =
            mapper.writeValueAsString(mapOf("path" to path, "contentType" to contentType, "size" to size))

        given("업로드 완료 신고 — POST /api/v1/images/complete") {
            `when`("실제 오브젝트가 사진이고 신고값과 일치하면") {
                then("200 과 경로를 반환하고 이미지를 기록한다") {
                    val path = "scan/1/success.jpg"
                    storage.stub(path, "image/jpeg", 1048576)

                    mockMvc.post("/api/v1/images/complete") {
                        header("Authorization", "Bearer ${accessToken(1L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, "image/jpeg", 1048576)
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.path") { value(path) }
                    }

                    countImage(path) shouldBe 1
                }
            }

            `when`("실제 오브젝트가 이미지가 아니면(영상 등)") {
                then("400 IMAGE-001 로 거절하고 오브젝트를 삭제한다") {
                    val path = "scan/2/clip.mp4"
                    storage.stub(path, "video/mp4", 5048576)

                    mockMvc.post("/api/v1/images/complete") {
                        header("Authorization", "Bearer ${accessToken(2L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, "video/mp4", 5048576)
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("IMAGE-001") }
                    }

                    storage.deleted shouldContain path
                    countImage(path) shouldBe 0
                }
            }

            `when`("신고한 형식·크기가 실제와 다르면") {
                then("400 IMAGE-002 로 거절하고 오브젝트를 삭제한다") {
                    val path = "scan/3/mismatch.jpg"
                    storage.stub(path, "image/jpeg", 2048)

                    mockMvc.post("/api/v1/images/complete") {
                        header("Authorization", "Bearer ${accessToken(3L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, "image/jpeg", 9999)
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("IMAGE-002") }
                    }

                    storage.deleted shouldContain path
                    countImage(path) shouldBe 0
                }
            }

            `when`("해당 경로에 오브젝트가 없으면") {
                then("400 IMAGE-003 으로 거절한다") {
                    val path = "scan/4/missing.jpg"

                    mockMvc.post("/api/v1/images/complete") {
                        header("Authorization", "Bearer ${accessToken(4L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(path, "image/jpeg", 1024)
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("IMAGE-003") }
                    }
                }
            }

            `when`("같은 경로로 다시 신고하면") {
                then("재검증 없이 성공하고 기록은 1건이다(멱등)") {
                    val path = "scan/5/idem.jpg"
                    storage.stub(path, "image/png", 3000)

                    repeat(2) {
                        mockMvc.post("/api/v1/images/complete") {
                            header("Authorization", "Bearer ${accessToken(5L)}")
                            contentType = MediaType.APPLICATION_JSON
                            content = body(path, "image/png", 3000)
                        }.andExpect { status { isOk() } }
                    }

                    storage.headCalls.count { it == path } shouldBe 1
                    countImage(path) shouldBe 1
                }
            }

            `when`("경로 대신 전체 URL 을 넘기면") {
                then("400 으로 거절한다(경로만 허용)") {
                    mockMvc.post("/api/v1/images/complete") {
                        header("Authorization", "Bearer ${accessToken(6L)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body("https://cdn.example.com/scan/6/x.jpg", "image/jpeg", 1024)
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("액세스 토큰 없이 호출하면") {
                then("401 을 반환한다") {
                    mockMvc.post("/api/v1/images/complete") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body("scan/7/x.jpg", "image/jpeg", 1024)
                    }.andExpect {
                        status { isUnauthorized() }
                    }
                }
            }
        }
    }
}
