package com.kbap.app.api.upload

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.application.auth.token.TokenIssuer
import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.domain.member.model.MemberRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class ImageUploadControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    init {
        val objectMapper = jacksonObjectMapper()

        fun accessToken(memberId: Long = 42L): String =
            tokenIssuer.issueAccessToken(memberId, MemberRole.USER)

        fun body(purpose: String? = "MENU_SCAN", contentType: String? = "image/jpeg", contentLength: Long? = 384512L): String {
            val map = buildMap {
                if (purpose != null) put("purpose", purpose)
                if (contentType != null) put("contentType", contentType)
                if (contentLength != null) put("contentLength", contentLength)
            }
            return objectMapper.writeValueAsString(map)
        }

        given("업로드 URL 발급 API — POST /api/v1/images/upload-url") {
            `when`("유효한 요청이면") {
                then("200 과 업로드·공개 URL 을 반환한다") {
                    mockMvc.post("/api/v1/images/upload-url") {
                        header("Authorization", "Bearer ${accessToken()}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body()
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.uploadUrl") { exists() }
                        jsonPath("$.payload.method") { value("PUT") }
                        jsonPath("$.payload.publicUrl") { exists() }
                        jsonPath("$.payload.objectKey") { exists() }
                        jsonPath("$.payload.requiredHeaders['Content-Type']") { value("image/jpeg") }
                    }
                }
            }

            `when`("액세스 토큰 없이 호출하면") {
                then("401 을 반환한다") {
                    mockMvc.post("/api/v1/images/upload-url") {
                        contentType = MediaType.APPLICATION_JSON
                        content = body()
                    }.andExpect {
                        status { isUnauthorized() }
                    }
                }
            }

            `when`("허용되지 않은 Content-Type 이면") {
                then("400·UPLOAD-001 로 거절한다") {
                    mockMvc.post("/api/v1/images/upload-url") {
                        header("Authorization", "Bearer ${accessToken()}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(contentType = "image/gif")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("UPLOAD-001") }
                    }
                }
            }

            `when`("지원하지 않는 용도이면") {
                then("400·UPLOAD-002 로 거절한다") {
                    mockMvc.post("/api/v1/images/upload-url") {
                        header("Authorization", "Bearer ${accessToken()}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(purpose = "UNKNOWN")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("UPLOAD-002") }
                    }
                }
            }

            `when`("허용 크기를 초과하면") {
                then("400·UPLOAD-003 로 거절한다") {
                    mockMvc.post("/api/v1/images/upload-url") {
                        header("Authorization", "Bearer ${accessToken()}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(contentLength = 2_000_000L)
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("UPLOAD-003") }
                    }
                }
            }

            `when`("필수 필드가 빠지면") {
                then("400 으로 거절한다") {
                    mockMvc.post("/api/v1/images/upload-url") {
                        header("Authorization", "Bearer ${accessToken()}")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(contentLength = null)
                    }.andExpect {
                        status { isBadRequest() }
                    }
                }
            }
        }
    }
}
