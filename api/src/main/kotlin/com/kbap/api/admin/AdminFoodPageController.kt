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
import java.net.URLEncoder

@Controller
class AdminFoodPageController(
    private val adminFoodDashboardService: AdminFoodDashboardService,
    private val adminDashboardMetricsService: AdminDashboardMetricsService,
    private val adminFoodService: AdminFoodService,
    private val adminImageBatchQueryService: AdminImageBatchQueryService,
    private val foodImageBatchSubmitService: FoodImageBatchSubmitService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/admin/foods")
    fun foods(model: Model): String {
        model.addAttribute("dashboard", adminFoodDashboardService.getDashboard())
        model.addAttribute("metrics", adminDashboardMetricsService.getMetrics())
        return "admin/foods"
    }

    @GetMapping("/admin/foods/list")
    fun foodList(
        @RequestParam(required = false) page: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) detail: Long?,
        @RequestParam(required = false) edit: Boolean?,
        model: Model,
    ): String {
        val safePage = (page?.toIntOrNull() ?: 1).coerceAtLeast(1)
        model.addAttribute("foodPage", adminFoodService.getFoodPage(safePage, q, parseStatus(status)))
        model.addAttribute("editMode", detail != null && edit == true)
        detail?.let { id -> adminFoodService.getFoodDetailOrNull(id)?.let { model.addAttribute("foodDetail", it) } }
        return "admin/food-list"
    }

    @PostMapping("/admin/foods/{id}")
    fun updateFood(
        @PathVariable id: Long,
        @RequestParam(required = false) page: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam koreanName: String,
        @RequestParam description: String,
        @RequestParam spiciness: Int,
        @RequestParam contentStatus: FoodContentStatus,
        @RequestParam(defaultValue = "") imageRef: String,
        @RequestParam(defaultValue = "") nameTranslationsJson: String,
        @RequestParam(defaultValue = "") descriptionTranslationsJson: String,
        @RequestParam(defaultValue = "") ingredientsJson: String,
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
            ingredientsJson = ingredientsJson,
        )
        return when (adminFoodService.updateFood(id, command)) {
            AdminFoodUpdateResult.UPDATED -> listRedirect(safePage, q, status, "updated" to id)
            AdminFoodUpdateResult.NOT_FOUND -> listRedirect(safePage, q, status, "error" to "not-found")
            AdminFoodUpdateResult.INVALID_NAME ->
                listRedirect(safePage, q, status, "detail" to id, "edit" to true, "error" to "invalid-name")
            AdminFoodUpdateResult.INVALID_JSON ->
                listRedirect(safePage, q, status, "detail" to id, "edit" to true, "error" to "invalid-json")
            AdminFoodUpdateResult.DUPLICATE_NAME ->
                listRedirect(safePage, q, status, "detail" to id, "edit" to true, "error" to "duplicate-name")
        }
    }

    @PostMapping("/admin/foods/{id}/delete")
    fun deleteFood(
        @PathVariable id: Long,
        @RequestParam(required = false) page: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
    ): String {
        val safePage = (page?.toIntOrNull() ?: 1).coerceAtLeast(1)
        return when (adminFoodService.deleteFood(id)) {
            AdminFoodDeleteResult.DELETED -> listRedirect(safePage, q, status, "deleted" to id)
            AdminFoodDeleteResult.NOT_FOUND -> listRedirect(safePage, q, status, "error" to "not-found")
        }
    }

    // 알 수 없는 값은 400 이 아니라 필터 해제로 흡수한다 — enum 직접 바인딩은 변환 실패로 화면이 열리지 않는다
    private fun parseStatus(status: String?): FoodContentStatus? =
        FoodContentStatus.entries.find { it.name == status }

    // form-encode 강제 — UriComponentsBuilder.encode() 는 + 를 남겨 수신측 form-decode 가 공백으로 뭉갠다
    private fun listRedirect(page: Int, q: String?, status: String?, vararg params: Pair<String, Any>): String {
        val query = buildList {
            add("page" to page.toString())
            q?.trim()?.takeIf { it.isNotEmpty() }?.let { add("q" to it) }
            parseStatus(status)?.let { add("status" to it.name) }
            params.forEach { (name, value) -> add(name to value.toString()) }
        }.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        return "redirect:/admin/foods/list?$query"
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
