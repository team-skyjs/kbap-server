package com.kbap.api.core.observability

import com.kbap.api.core.ApiHeaders
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.server.observation.ServerRequestObservationContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ApiVersionObservationConventionTest : FunSpec({
    test("HTTP 메트릭에 기존 태그와 정규화된 요청 버전을 기록") {
        val convention = ApiVersionObservationConvention()
        for ((header, expected) in listOf("1.1" to "1.1", "3.3.0" to "3.3", null to "missing",
            " " to "missing", "9.9" to "unsupported", "invalid" to "unsupported")) {
            val request = MockHttpServletRequest("GET", "/api/scans")
            if (header != null) request.addHeader(ApiHeaders.API_VERSION, header)
            val context = ServerRequestObservationContext(request, MockHttpServletResponse())
            val tags = convention.getLowCardinalityKeyValues(context).associate { it.key to it.value }
            tags["api_version"] shouldBe expected
            tags["method"] shouldBe "GET"
        }
    }
})
