package com.kbap.api.image

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@IntegrationTest
class GuestUploadBridgeTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var storage: FakeStorageObjectStore
    @Autowired private lateinit var properties: ImageUploadProperties
    @Autowired private lateinit var dataSource: DataSource

    init {
        val mapper = jacksonObjectMapper()
        val installation = "release-check-guest-0001"

        fun complete(path: String) = mockMvc.post("/api/images/complete") {
            header("X-API-Version", "1.1")
            header("X-Installation-Id", installation)
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("path" to path, "contentType" to "image/jpeg", "size" to 512))
        }.andReturn().response

        beforeSpec {
            TestTables.clearAll(dataSource)
            storage.heads.clear()
            storage.lastModified.clear()
            storage.headCalls.clear()
            storage.deleted.clear()
            storage.headDelayMillis = 0
        }
        afterSpec { TestTables.clearAll(dataSource) }

        given("게스트 발급키 선행 전환과 기존 완료 신고") {
            `when`("새 발급 응답의 키로 완료 신고하면") {
                then("설치 해시 키를 기존 완료 로직으로 등록하고 같은 요청 재시도도 성공한다") {
                    val issued = mockMvc.post("/api/images/upload-url") {
                        header("X-API-Version", "1.1")
                        header("X-Installation-Id", installation)
                        contentType = MediaType.APPLICATION_JSON
                        content = mapper.writeValueAsString(mapOf("purpose" to "FEEDBACK", "contentType" to "image/jpeg", "contentLength" to 512))
                    }.andReturn().response
                    issued.status shouldBe 200
                    val path = mapper.readTree(issued.contentAsString).path("payload").path("objectKey").asText()
                    path.substringAfterLast('/') shouldMatch Regex("""ga747b7735c42_[0-9a-f-]{36}\.jpg""")
                    storage.stub(path, "image/jpeg", 512)

                    complete(path).status shouldBe 200
                    complete(path).status shouldBe 200
                }
            }
            `when`("이전 노드가 발급한 미등록 키의 업로드가 완료 허용 창 안에 있으면") {
                then("기존 정책대로 완료되어 선행 배포가 진행 중 업로드를 끊지 않는다") {
                    val path = "${properties.keyPrefix}/images/feedback/2026/10/0_${UUID.randomUUID()}.jpg"
                    storage.stub(path, "image/jpeg", 512, Instant.now().minusSeconds(60))

                    complete(path).status shouldBe 200
                    complete(path).status shouldBe 200
                }
            }
            `when`("이전 노드의 미등록 키가 이미 업로드됐지만 완료 신고 허용 창을 넘겼으면") {
                then("서명 URL 만료와 별도로 오브젝트 시각을 기준으로 IMAGE-003을 반환하고 삭제하지 않는다") {
                    val path = "${properties.keyPrefix}/images/feedback/2026/10/0_${UUID.randomUUID()}.jpg"
                    storage.stub(path, "image/jpeg", 512, Instant.now().minus(properties.completeWindow).minusSeconds(1))

                    val response = complete(path)
                    response.status shouldBe 400
                    mapper.readTree(response.contentAsString).path("code").asText() shouldBe "IMAGE-003"
                    storage.deleted.contains(path) shouldBe false
                }
            }
        }
    }
}
