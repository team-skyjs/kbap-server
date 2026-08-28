package com.kbap.api.admin

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam

@Controller
class AdminMemberPageController(
    private val adminMemberService: AdminMemberService,
) {
    @GetMapping("/admin/members")
    fun members(@RequestParam(required = false) page: String?, model: Model): String {
        val safePage = (page?.toIntOrNull() ?: 1).coerceAtLeast(1)
        model.addAttribute("memberPage", adminMemberService.getMemberPage(safePage))
        return "admin/members"
    }

    @GetMapping("/admin/members/{id}")
    fun memberDetail(@PathVariable id: Long, model: Model): String {
        adminMemberService.getMemberDetailOrNull(id)?.let { model.addAttribute("member", it) }
        return "admin/member-detail"
    }
}
