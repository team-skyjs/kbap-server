package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenParser
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class AdminPageController(
    private val adminLoginService: AdminLoginService,
    private val tokenParser: TokenParser,
) {
    @GetMapping("/admin/login")
    fun loginForm(request: HttpServletRequest): String {
        if (authenticated(request)) return "redirect:/admin"
        return "admin/login"
    }

    @PostMapping("/admin/login")
    fun login(
        @RequestParam loginId: String,
        @RequestParam password: String,
        response: HttpServletResponse,
        model: Model,
    ): String {
        val token = adminLoginService.login(loginId, password)
        if (token == null) {
            model.addAttribute("error", "아이디 또는 비밀번호가 올바르지 않습니다.")
            return "admin/login"
        }
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(token).toString())
        return "redirect:/admin"
    }

    @PostMapping("/admin/logout")
    fun logout(response: HttpServletResponse): String {
        response.addHeader(HttpHeaders.SET_COOKIE, expiredCookie().toString())
        return "redirect:/admin/login"
    }

    @GetMapping("/admin")
    fun home(): String = "admin/home"

    private fun authenticated(request: HttpServletRequest): Boolean {
        val token = request.cookies?.firstOrNull { it.name == AdminPageAuthInterceptor.COOKIE_NAME }?.value
            ?: return false
        return try {
            tokenParser.parseAccessToken(token).role == MemberRole.ADMIN
        } catch (e: BusinessException) {
            false
        }
    }

    // Max-Age 미지정 세션 쿠키 — 만료는 토큰 자체 만료를 인터셉터가 검증(TTL 설정 배관 불필요)
    private fun sessionCookie(token: String): ResponseCookie =
        ResponseCookie.from(AdminPageAuthInterceptor.COOKIE_NAME, token)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/admin")
            .build()

    private fun expiredCookie(): ResponseCookie =
        ResponseCookie.from(AdminPageAuthInterceptor.COOKIE_NAME, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/admin")
            .maxAge(0)
            .build()
}
