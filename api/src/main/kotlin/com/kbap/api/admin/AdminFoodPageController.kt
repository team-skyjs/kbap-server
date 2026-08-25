package com.kbap.api.admin

import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.api.food.FoodImageBatchSubmitService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.food.model.FoodContentStatus
import jakarta.servlet.http.HttpServletRequest
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
    private val adminFoodCommandService: AdminFoodCommandService,
    private val adminFoodDashboardService: AdminFoodDashboardService,
    private val adminDashboardMetricsService: AdminDashboardMetricsService,
    private val adminFoodService: AdminFoodService,
    private val adminFoodOutboxQueryService: AdminFoodOutboxQueryService,
    private val adminImageBatchQueryService: AdminImageBatchQueryService,
    private val foodImageBatchSubmitService: FoodImageBatchSubmitService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/admin/foods")
    fun foods(model: Model): String {
        model.addAttribute("dashboard", adminFoodDashboardService.getDashboard())
        model.addAttribute("outbox", adminFoodOutboxQueryService.getOutboxDashboard())
        model.addAttribute("vectorOutbox", adminFoodDashboardService.getVectorOutboxDashboard())
        model.addAttribute("metrics", adminDashboardMetricsService.getMetrics())
        return "admin/foods"
    }

    @PostMapping("/admin/foods/vector-outboxes/enqueue")
    fun enqueueVectorOutboxes(): String {
        adminFoodDashboardService.enqueueReadyFoodsForVectorSync()
        return "redirect:/admin/foods"
    }

    @PostMapping("/admin/foods/vector-outboxes/{outboxId}/retry")
    fun retryVectorOutbox(@PathVariable outboxId: Long): String {
        adminFoodDashboardService.retryVectorOutbox(outboxId)
        return "redirect:/admin/foods"
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
        @RequestParam version: Long,
        @RequestParam koreanName: String,
        @RequestParam description: String,
        @RequestParam spiciness: Int,
        @RequestParam(defaultValue = "") imageRef: String,
        @RequestParam(defaultValue = "") nameTranslationsJson: String,
        @RequestParam(defaultValue = "") descriptionTranslationsJson: String,
        @RequestParam(defaultValue = "") ingredientsJson: String,
        request: HttpServletRequest,
    ): String {
        val safePage = (page?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val command = UpdateFoodCommand(
            koreanName = koreanName.trim(),
            description = description,
            spiciness = spiciness,
            imageRef = imageRef.trim(),
            nameTranslationsJson = nameTranslationsJson,
            descriptionTranslationsJson = descriptionTranslationsJson,
            ingredientsJson = ingredientsJson,
        )
        val editError = { code: String -> listRedirect(safePage, q, status, "detail" to id, "edit" to true, "error" to code) }
        return when (adminFoodService.updateFood(id, command, version, adminId(request))) {
            AdminFoodUpdateResult.UPDATED -> listRedirect(safePage, q, status, "updated" to id)
            AdminFoodUpdateResult.NOT_FOUND -> listRedirect(safePage, q, status, "error" to "not-found")
            AdminFoodUpdateResult.STALE -> editError("stale")
            AdminFoodUpdateResult.INVALID_NAME -> editError("invalid-name")
            AdminFoodUpdateResult.INVALID_JSON -> editError("invalid-json")
            AdminFoodUpdateResult.INVALID_CONTENT -> editError("invalid-content")
            AdminFoodUpdateResult.DUPLICATE_NAME -> editError("duplicate-name")
        }
    }

    @PostMapping("/admin/foods/{id}/approve")
    fun approveFood(
        @PathVariable id: Long,
        @RequestParam(required = false) page: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        request: HttpServletRequest,
    ): String = reviewRedirect(id, page, q, status) { adminFoodCommandService.approve(adminId(request), id) }

    @PostMapping("/admin/foods/{id}/reject")
    fun rejectFood(
        @PathVariable id: Long,
        @RequestParam(required = false) page: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "") reason: String,
        request: HttpServletRequest,
    ): String {
        if (reason.isBlank()) {
            return listRedirect((page?.toIntOrNull() ?: 1).coerceAtLeast(1), q, status, "detail" to id, "error" to "reason-required")
        }
        return reviewRedirect(id, page, q, status) { adminFoodCommandService.reject(adminId(request), id, reason.trim()) }
    }

    private fun reviewRedirect(id: Long, page: String?, q: String?, status: String?, action: () -> Unit): String {
        val safePage = (page?.toIntOrNull() ?: 1).coerceAtLeast(1)
        return try {
            action()
            listRedirect(safePage, q, status, "detail" to id, "reviewed" to id)
        } catch (e: BusinessException) {
            listRedirect(safePage, q, status, "detail" to id, "error" to "transition")
        }
    }

    private fun adminId(request: HttpServletRequest): Long =
        request.getAttribute(JwtAuthenticationFilter.ADMIN_ID_ATTRIBUTE) as Long

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

    private fun parseStatus(status: String?): FoodContentStatus? =
        FoodContentStatus.entries.find { it.name == status }

    private fun listRedirect(page: Int, q: String?, status: String?, vararg params: Pair<String, Any>): String {
        val query = buildList {
            add("page" to page.toString())
            q?.trim()?.takeIf { it.isNotEmpty() }?.let { add("q" to it) }
            parseStatus(status)?.let { add("status" to it.name) }
            params.forEach { (name, value) -> add(name to value.toString()) }
        }.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, Charsets.UTF_8)}" }
        return "redirect:/admin/foods/list?$query"
    }

    @PostMapping("/admin/foods/recollect")
    fun recollect(
        @RequestParam(required = false) page: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) status: String?,
    ): String {
        val safePage = (page?.toIntOrNull() ?: 1).coerceAtLeast(1)
        return try {
            val result = adminFoodService.requestRecollect(q, parseStatus(status))
            when {
                result.exceeded -> listRedirect(safePage, q, status, "recollectError" to "too-many", "recollectMax" to result.max)
                result.requested == 0L -> listRedirect(safePage, q, status, "recollectError" to "no-target")
                else -> listRedirect(safePage, q, status, "recollected" to result.created, "recollectSkipped" to result.skipped)
            }
        } catch (e: Exception) {
            log.error("재수집 요청 실패", e)
            listRedirect(safePage, q, status, "recollectError" to "failed")
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
        const val MAX_SEED_NAMES = 500
        const val MAX_SEED_NAME_LENGTH = 255
    }
}
