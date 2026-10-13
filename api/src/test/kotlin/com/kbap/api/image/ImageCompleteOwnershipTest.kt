package com.kbap.api.image

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID
import javax.sql.DataSource

@IntegrationTest
class ImageCompleteOwnershipTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var storage: FakeStorageObjectStore
    @Autowired private lateinit var uploadProperties: ImageUploadProperties

    init {
        val mapper = jacksonObjectMapper()

        fun seedMember(memberId: Long) {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "INSERT INTO member (id, provider, provider_uid, member_status, onboarding_completed, status, created_at, updated_at) " +
                        "VALUES (?, 'GOOGLE', ?, 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6)) ON DUPLICATE KEY UPDATE id = id",
                ).use { ps -> ps.setLong(1, memberId); ps.setString(2, "owner-test-$memberId"); ps.executeUpdate() }
            }
        }

        fun token(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun issued(owner: Any, purpose: String = "scans", ext: String = "jpg") =
            "${uploadProperties.keyPrefix}/images/$purpose/2026/10/${owner}_${UUID.randomUUID()}.$ext"

        fun complete(token: String?, path: String, contentType: String, size: Long, installation: String? = null): MockHttpServletResponse =
            mockMvc.post("/api/images/complete") {
                if (token != null) header("Authorization", "Bearer $token")
                if (installation != null) header("X-Installation-Id", installation)
                header("X-API-Version", "1.0")
                this.contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("path" to path, "contentType" to contentType, "size" to size))
            }.andReturn().response

        fun code(response: MockHttpServletResponse) = mapper.readTree(response.contentAsString).path("code").asText()

        fun rows(path: String): Int = dataSource.connection.use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM uploaded_image WHERE object_path = ?").use { ps -> ps.setString(1, path); ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) } }
        }

        beforeEach {
            storage.heads.clear()
            storage.lastModified.clear()
            storage.deleted.clear()
            storage.headCalls.clear()
        }

        given("업로드 완료 신고의 경로 소유 검증 — POST /api/images/complete") {
            `when`("다른 회원에게 발급된 키로 신고하면") {
                then("크기 불일치든 정상 값이든 400 UPLOAD-004 — head·delete 호출 0, 행 0") {
                    val victimKey = issued(owner = 11L)
                    storage.stub(victimKey, "image/jpeg", 4096)
                    val attacker = token(12L)

                    val mismatch = complete(attacker, victimKey, "image/jpeg", 1)
                    val exact = complete(attacker, victimKey, "image/jpeg", 4096)

                    mismatch.status shouldBe 400
                    code(mismatch) shouldBe "UPLOAD-004"
                    exact.status shouldBe 400
                    code(exact) shouldBe "UPLOAD-004"
                    storage.deleted.size shouldBe 0
                    storage.headCalls.size shouldBe 0
                    rows(victimKey) shouldBe 0
                }
            }

            `when`("서버가 만든 오브젝트 경로(images/food/…, images/review-bot/…)로 신고하면") {
                then("400 UPLOAD-004 — 발급 형식이 아니다") {
                    val member = token(13L)
                    val catalog = "${uploadProperties.keyPrefix}/images/food/2026/10/bibimbap.webp"
                    val pool = "${uploadProperties.keyPrefix}/images/review-bot/6a2c1605affd/1.webp"
                    storage.stub(catalog, "image/webp", 10)
                    storage.stub(pool, "image/webp", 10)

                    code(complete(member, catalog, "image/webp", 1)) shouldBe "UPLOAD-004"
                    code(complete(member, pool, "image/webp", 1)) shouldBe "UPLOAD-004"
                    storage.deleted.size shouldBe 0
                    storage.headCalls.size shouldBe 0
                }
            }

            `when`("본인에게 발급된 키로 정상 신고하면") {
                then("200 으로 기록한다 — 용도 접두·연월·uuid·확장자 전부 발급 형식") {
                    val ownKey = issued(owner = 14L, purpose = "review", ext = "webp")
                    storage.stub(ownKey, "image/webp", 2048)

                    val response = complete(token(14L), ownKey, "image/webp", 2048)

                    response.status shouldBe 200
                    rows(ownKey) shouldBe 1
                }
            }

            `when`("게스트가 feedback 용도의 게스트 키로 신고하면") {
                then("200 — 게스트 소유 키는 자기 설치 id 의 g+해시만, 다른 설치의 키·회원 키는 400 UPLOAD-004, feedback 아닌 용도는 종전대로 401") {
                    val mine = PresignedUploadService.guestOwnerKey("owner-guest-0001")
                    val guestKey = issued(owner = mine, purpose = "feedback", ext = "webp")
                    storage.stub(guestKey, "image/webp", 512)
                    val otherGuestKey = issued(owner = PresignedUploadService.guestOwnerKey("owner-guest-0002"), purpose = "feedback", ext = "webp")
                    storage.stub(otherGuestKey, "image/webp", 512)
                    val memberStyleKey = issued(owner = 15L, purpose = "feedback", ext = "webp")
                    storage.stub(memberStyleKey, "image/webp", 512)
                    val otherPurposeKey = issued(owner = mine, purpose = "review", ext = "webp")
                    storage.stub(otherPurposeKey, "image/webp", 512)

                    complete(null, guestKey, "image/webp", 512, installation = "owner-guest-0001").status shouldBe 200
                    code(complete(null, otherGuestKey, "image/webp", 1, installation = "owner-guest-0001")) shouldBe "UPLOAD-004"
                    code(complete(null, memberStyleKey, "image/webp", 512, installation = "owner-guest-0001")) shouldBe "UPLOAD-004"
                    complete(null, otherPurposeKey, "image/webp", 512, installation = "owner-guest-0001").status shouldBe 401
                    rows(guestKey) shouldBe 1
                    rows(otherGuestKey) shouldBe 0
                    rows(memberStyleKey) shouldBe 0
                    rows(otherPurposeKey) shouldBe 0
                    storage.deleted.size shouldBe 0
                }
            }

            `when`("발급 형식이지만 접두가 다른 환경의 키로 신고하면") {
                then("400 UPLOAD-004 — 이 서버가 발급한 접두만 받는다") {
                    val foreign = "other-env/images/scans/2026/10/16_${UUID.randomUUID()}.jpg"
                    storage.stub(foreign, "image/jpeg", 10)

                    code(complete(token(16L), foreign, "image/jpeg", 10)) shouldBe "UPLOAD-004"
                    storage.headCalls.size shouldBe 0
                }
            }
        }
    }
}
