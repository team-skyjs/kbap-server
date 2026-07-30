package com.kbap.api.admin

import com.kbap.api.food.FoodImageBatchSubmitService
import com.kbap.common.domain.food.model.FoodContentStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
class AdminFoodPageController(
    private val adminFoodDashboardService: AdminFoodDashboardService,
    private val adminFoodService: AdminFoodService,
    private val adminImageBatchQueryService: AdminImageBatchQueryService,
    private val foodImageBatchSubmitService: FoodImageBatchSubmitService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/admin/foods")
    fun foods(model: Model): String {
        model.addAttribute("dashboard", adminFoodDashboardService.getDashboard())
        return "admin/foods"
    }

    @GetMapping("/admin/foods/list")
    fun foodList(
        @RequestParam(required = false) page: String?,
        @RequestParam(required = false) detail: Long?,
        @RequestParam(required = false) edit: Boolean?,
        model: Model,
    ): String {
        val safePage = (page?.toIntOrNull() ?: 1).coerceAtLeast(1)
        model.addAttribute("foodPage", adminFoodService.getFoodPage(safePage))
        model.addAttribute("editMode", detail != null && edit == true)
        detail?.let { id -> adminFoodService.getFoodDetailOrNull(id)?.let { model.addAttribute("foodDetail", it) } }
        return "admin/food-list"
    }

    @PostMapping("/admin/foods/{id}")
    fun updateFood(
        @PathVariable id: Long,
        @RequestParam(required = false) page: String?,
        @RequestParam koreanName: String,
        @RequestParam description: String,
        @RequestParam spiciness: Int,
        @RequestParam contentStatus: FoodContentStatus,
        @RequestParam(defaultValue = "") imageRef: String,
        @RequestParam(defaultValue = "") nameTranslationsJson: String,
        @RequestParam(defaultValue = "") descriptionTranslationsJson: String,
        @RequestParam(defaultValue = "") avoidanceSubstancesJson: String,
    ): String {
        val safePage = (page?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val command = UpdateFoodCommand(
            koreanName = koreanName.trim(),
            description = description,
            spiciness = spiciness,
            contentStatus = contentStatus,
            imageRef = imageRef.trim(),
            nameTranslationsJson = nameTranslationsJson,
            descriptionTranslationsJson = descriptionTranslationsJson,
            avoidanceSubstancesJson = avoidanceSubstancesJson,
        )
        return when (adminFoodService.updateFood(id, command)) {
            AdminFoodUpdateResult.UPDATED -> "redirect:/admin/foods/list?page=$safePage&updated=$id#food-$id"
            AdminFoodUpdateResult.NOT_FOUND -> "redirect:/admin/foods/list?page=$safePage&error=not-found#food-$id"
            AdminFoodUpdateResult.INVALID_NAME -> "redirect:/admin/foods/list?page=$safePage&detail=$id&edit=true&error=invalid-name#food-$id"
            AdminFoodUpdateResult.INVALID_JSON -> "redirect:/admin/foods/list?page=$safePage&detail=$id&edit=true&error=invalid-json#food-$id"
            AdminFoodUpdateResult.DUPLICATE_NAME -> "redirect:/admin/foods/list?page=$safePage&detail=$id&edit=true&error=duplicate-name#food-$id"
        }
    }

    @GetMapping("/admin/foods/seed")
    fun seedPage(): String = "admin/food-seed"

    @PostMapping("/admin/foods/seed")
    fun seed(@RequestParam koreanNames: String): String {
        val names = koreanNames.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        if (names.isEmpty()) return "redirect:/admin/foods/seed?error=empty-seed"
        if (names.size > MAX_SEED_NAMES) return "redirect:/admin/foods/seed?error=too-many-names"
        if (names.any { it.length > MAX_SEED_NAME_LENGTH }) return "redirect:/admin/foods/seed?error=name-too-long"
        return try {
            val result = adminFoodService.seedIncomplete(names)
            if (result.requested == 0) {
                "redirect:/admin/foods/seed?error=no-valid-names"
            } else {
                "redirect:/admin/foods/seed?seeded=${result.created}&skipped=${result.skipped}"
            }
        } catch (e: Exception) {
            log.error("화면 시드 등록 실패", e)
            "redirect:/admin/foods/seed?error=seed-failed"
        }
    }

    @GetMapping("/admin/foods/images")
    fun imagesPage(model: Model): String {
        model.addAttribute("batches", adminImageBatchQueryService.getRecentBatches())
        return "admin/food-images"
    }

    @PostMapping("/admin/foods/images")
    fun submitImages(): String =
        try {
            val result = foodImageBatchSubmitService.submitMissingImages()
            "redirect:/admin/foods/images?submittedFoods=${result.submittedFoodCount}&submittedBatches=${result.submittedBatchCount}"
        } catch (e: Exception) {
            log.error("화면 이미지 배치 제출 실패", e)
            "redirect:/admin/foods/images?error=images-failed"
        }

    companion object {
        // REST(AdminFoodSeedRequest)와 동일한 검증 경계 — 폼 경로가 우회하지 않게 유지
        const val MAX_SEED_NAMES = 500
        const val MAX_SEED_NAME_LENGTH = 255
    }
}
