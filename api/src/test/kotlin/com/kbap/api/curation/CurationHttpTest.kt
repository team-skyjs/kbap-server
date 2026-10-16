package com.kbap.api.curation

import com.kbap.api.IntegrationTest
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.http.MediaType
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAccount
import io.kotest.matchers.shouldBe
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.curation.CurationAssetRepository
import com.kbap.common.port.storage.ImmutableObjectMetadata
import com.kbap.common.domain.curation.CurationReleaseRepository
import com.kbap.common.domain.curation.CurationCollectionRepository
import com.kbap.common.domain.curation.model.CurationRelease
import com.kbap.common.domain.curation.model.CurationCollection
import org.springframework.jdbc.core.JdbcTemplate

@IntegrationTest
class CurationHttpTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var tokens: TokenIssuer
    @Autowired private lateinit var admins: AdminAccountJpaRepository
    @Autowired private lateinit var assets: CurationAssetRepository
    @Autowired private lateinit var store: FakeImmutableObjectStore
    @Autowired private lateinit var releases: CurationReleaseRepository
    @Autowired private lateinit var collections: CurationCollectionRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    init {
        beforeEach {
            jdbc.update("DELETE FROM curation_asset")
            jdbc.update("DELETE FROM curation_collection")
            jdbc.update("DELETE FROM curation_release")
            val root = releases.save(CurationRelease())
            CurationService.keys.forEach { collections.save(CurationCollection(root.id, it)) }
        }
        given("빈 서울 큐레이션") {
            `when`("게스트가 홈 모음을 조회하면") {
                then("정상 미게시 상태를 명시한다") {
                    mvc.get("/api/curations/seoul?lang=en") { header("X-API-Version", "1.0") }
                        .andExpect {
                            status { isOk() }
                            jsonPath("$.payload.state") { value("UNPUBLISHED") }
                            jsonPath("$.payload.collections.length()") { value(0) }
                            header { string("Cache-Control", "no-store") }
                        }
                }
            }
        }
        given("큐레이션 관리 HTTP 경계") {
            `when`("인증과 환경과 필수 헤더를 검증하면") {
                then("정상 관리자 대조군과 오류 코드를 반환한다") {
                    val account = admins.findByLoginId("curation-http") ?: admins.save(AdminAccount("curation-http", "test"))
                    val token = tokens.issueAccessToken(account.id, MemberRole.ADMIN)
                    val path = "/api/admin/curations/seoul"
                    mvc.get(path) { header("X-API-Version", "1.0") }.andExpect { status { isUnauthorized() } }
                    mvc.get(path) { header("X-API-Version", "1.0"); header("Authorization", "Bearer ${tokens.issueAccessToken(account.id, MemberRole.USER)}") }.andExpect { status { isForbidden() } }
                    mvc.get(path) { header("X-API-Version", "1.0"); header("Authorization", "Bearer $token") }.andExpect { status { isOk() }; jsonPath("$.payload.collections.length()") { value(3) } }
                    mvc.get(path) { header("X-API-Version", "1.0"); header("Authorization", "Bearer ${tokens.issueAccessToken(Long.MAX_VALUE, MemberRole.ADMIN)}") }.andExpect { status { isForbidden() } }
                    mvc.post("$path/sights/validate") {
                        header("X-API-Version", "1.0"); header("Authorization", "Bearer $token"); header("X-Target-Environment", "prod")
                        contentType = MediaType.APPLICATION_JSON; content = "{\"expectedVersion\":0}"
                    }.andExpect { status { isConflict() }; jsonPath("$.code") { value("CURATION-004") } }
                    mvc.post("$path/sights/validate") {
                        header("X-API-Version", "1.0"); header("Authorization", "Bearer $token")
                        contentType = MediaType.APPLICATION_JSON; content = "{\"expectedVersion\":0}"
                    }.andExpect { status { isBadRequest() } }
                    mvc.get("/api/curations/seoul") { header("X-API-Version", "1.0") }.andExpect { status { isBadRequest() } }
                    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/curations/seoul?lang=en").with { request -> request.removeHeader("X-API-Version"); request })
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest)
                    mvc.get("/api/curations/seoul") { param("lang", " "); header("X-API-Version", "1.0") }.andExpect { status { isBadRequest() } }
                    val detail = mvc.get("$path/sights") { header("X-API-Version", "1.0"); header("Authorization", "Bearer $token") }.andExpect { status { isOk() } }.andReturn()
                    val mapper = jacksonObjectMapper()
                    val version = mapper.readTree(detail.response.contentAsString).path("payload").path("version").asLong()
                    listOf("{\"expectedVersion\":0,\"document\":{\"title\":{\"en\":null}}}", " ".repeat(1024 * 1024) + "{}").forEach { malformed ->
                        mvc.put("$path/sights/draft") {
                            header("X-API-Version", "1.0"); header("Authorization", "Bearer $token"); header("X-Target-Environment", "dev")
                            contentType = MediaType.APPLICATION_JSON; content = malformed
                        }.andExpect { status { isBadRequest() } }
                    }
                    val saved = mvc.put("$path/sights/draft") {
                        header("X-API-Version", "1.0"); header("Authorization", "Bearer $token"); header("X-Target-Environment", "dev")
                        contentType = MediaType.APPLICATION_JSON; content = mapper.writeValueAsString(mapOf("expectedVersion" to version, "document" to mapOf("title" to mapOf("en" to "draft"))))
                    }.andExpect { status { isOk() }; jsonPath("$.payload.hasUnpublishedChanges") { value(true) } }.andReturn()
                    val next = mapper.readTree(saved.response.contentAsString).path("payload").path("version").asLong()
                    mvc.post("$path/sights/validate") {
                        header("X-API-Version", "1.0"); header("Authorization", "Bearer $token"); header("X-Target-Environment", "dev")
                        contentType = MediaType.APPLICATION_JSON; content = mapper.writeValueAsString(mapOf("expectedVersion" to next))
                    }.andExpect { status { isOk() }; jsonPath("$.payload.valid") { value(false) } }
                    mvc.post("$path/sights/publish") {
                        header("X-API-Version", "1.0"); header("Authorization", "Bearer $token"); header("X-Target-Environment", "dev")
                        contentType = MediaType.APPLICATION_JSON; content = mapper.writeValueAsString(mapOf("expectedVersion" to next))
                    }.andExpect { status { isBadRequest() }; jsonPath("$.code") { value("CURATION-002") } }
                    val upload = mvc.post("$path/sights/assets/upload-url") {
                        header("X-API-Version", "1.0"); header("Authorization", "Bearer $token"); header("X-Target-Environment", "dev")
                        contentType = MediaType.APPLICATION_JSON; content = mapper.writeValueAsString(mapOf("expectedVersion" to next, "contentType" to "image/png", "contentLength" to 100))
                    }.andExpect { status { isOk() } }.andReturn()
                    val id = mapper.readTree(upload.response.contentAsString).path("payload").path("assetId").asLong()
                    store.objects[assets.findById(id).get().stagingKey] = ImmutableObjectMetadata("image/png", 100, "http-etag")
                    mvc.post("$path/sights/assets/$id/complete") {
                        header("X-API-Version", "1.0"); header("Authorization", "Bearer $token"); header("X-Target-Environment", "dev")
                        contentType = MediaType.APPLICATION_JSON; content = "{\"contentType\":\"image/png\",\"size\":100}"
                    }.andExpect { status { isOk() }; jsonPath("$.payload.state") { value("READY") } }
                    mvc.get("/api/curations/seoul?lang=unsupported") { header("X-API-Version", "1.0") }.andExpect { status { isOk() }; jsonPath("$.payload.resolvedLang") { value("en") } }
                }
            }
            `when`("Swagger 문서를 조회하면") {
                then("공개와 관리자 업로드 및 게시 계약을 찾을 수 있다") {
                    val response = mvc.get("/v3/api-docs/1.0").andExpect { status { isOk() } }.andReturn().response.contentAsString
                    val paths = jacksonObjectMapper().readTree(response).path("paths")
                    paths.has("/api/curations/seoul") shouldBe true
                    paths.has("/api/admin/curations/seoul/{collectionKey}/publish") shouldBe true
                    paths.has("/api/admin/curations/seoul/{collectionKey}/assets/{assetId}/complete") shouldBe true
                }
            }
        }

    }
}
