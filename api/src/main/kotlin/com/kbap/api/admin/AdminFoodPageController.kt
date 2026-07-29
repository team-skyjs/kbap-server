package com.kbap.api.admin

import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class AdminFoodPageController(
    private val adminFoodDashboardService: AdminFoodDashboardService,
) {
    @GetMapping("/admin/foods")
    fun foods(model: Model): String {
        model.addAttribute("dashboard", adminFoodDashboardService.getDashboard())
        return "admin/foods"
    }
}
