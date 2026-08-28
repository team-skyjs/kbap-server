package com.kbap.api.core.auth

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.model.MemberRole
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.MethodParameter
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.ServletWebRequest

class AuthAdminIdArgumentResolverTest : BehaviorSpec({
    val resolver = AuthAdminIdArgumentResolver()

    class Probe {
        @Suppress("unused")
        fun handler(@AuthAdminId adminId: Long, plain: Long) = Unit
    }

    val method = Probe::class.java.getDeclaredMethod("handler", Long::class.java, Long::class.java)
    val annotated = MethodParameter(method, 0)
    val plain = MethodParameter(method, 1)

    fun request(role: String?, adminId: Long?): ServletWebRequest =
        ServletWebRequest(
            MockHttpServletRequest().apply {
                role?.let { setAttribute(JwtAuthenticationFilter.ROLE_ATTRIBUTE, it) }
                adminId?.let { setAttribute(JwtAuthenticationFilter.ADMIN_ID_ATTRIBUTE, it) }
            },
        )

    given("@AuthAdminId 리졸버") {
        `when`("@AuthAdminId Long 파라미터면") {
            then("지원하고, 애너테이션 없는 Long 은 지원하지 않는다") {
                resolver.supportsParameter(annotated) shouldBe true
                resolver.supportsParameter(plain) shouldBe false
            }
        }

        `when`("필터가 관리자 속성을 심어 두었으면") {
            then("관리자 계정 id 를 돌려준다") {
                resolver.resolveArgument(annotated, null, request(MemberRole.ADMIN.name, 5L), null) shouldBe 5L
            }
        }

        `when`("회원 토큰(USER)으로 들어왔으면") {
            then("ADMIN_FORBIDDEN 으로 거절한다") {
                val e = shouldThrow<BusinessException> {
                    resolver.resolveArgument(annotated, null, request(MemberRole.USER.name, null), null)
                }
                e.errorCode shouldBe ErrorCode.ADMIN_FORBIDDEN
            }
        }

        `when`("속성이 아예 없으면") {
            then("ADMIN_FORBIDDEN 으로 거절한다") {
                val e = shouldThrow<BusinessException> {
                    resolver.resolveArgument(annotated, null, request(null, null), null)
                }
                e.errorCode shouldBe ErrorCode.ADMIN_FORBIDDEN
            }
        }
    }
})
