package com.kbap.api.admin

import com.kbap.api.food.FoodImageBatchSubmitService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class AdminFoodPageController(
    private val adminFoodDashboardService: AdminFoodDashboardService,
    private val adminFoodService: AdminFoodService,
    private val foodImageBatchSubmitService: FoodImageBatchSubmitService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/admin/foods")
    fun foods(model: Model): String {
        model.addAttribute("dashboard", adminFoodDashboardService.getDashboard())
        return "admin/foods"
    }

    @PostMapping("/admin/foods/seed")
    fun seed(@RequestParam koreanNames: String): String {
        val names = koreanNames.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return "redirect:/admin/foods?error=empty-seed"
        if (names.size > MAX_SEED_NAMES) return "redirect:/admin/foods?error=too-many-names"
        if (names.any { it.length > MAX_SEED_NAME_LENGTH }) return "redirect:/admin/foods?error=name-too-long"
        return try {
            val result = adminFoodService.seedIncomplete(names)
            if (result.requested == 0) {
                "redirect:/admin/foods?error=no-valid-names"
            } else {
                "redirect:/admin/foods?seeded=${result.created}&skipped=${result.skipped}"
            }
        } catch (e: Exception) {
            log.error("화면 시드 등록 실패", e)
            "redirect:/admin/foods?error=seed-failed"
        }
    }

    @PostMapping("/admin/foods/images")
    fun submitImages(): String =
        try {
            val result = foodImageBatchSubmitService.submitMissingImages()
            "redirect:/admin/foods?submittedFoods=${result.submittedFoodCount}&submittedBatches=${result.submittedBatchCount}"
        } catch (e: Exception) {
            log.error("화면 이미지 배치 제출 실패", e)
            "redirect:/admin/foods?error=images-failed"
        }

    companion object {
        // REST(AdminFoodSeedRequest)와 동일한 검증 경계 — 폼 경로가 우회하지 않게 유지
        const val MAX_SEED_NAMES = 500
        const val MAX_SEED_NAME_LENGTH = 255
    }
}
