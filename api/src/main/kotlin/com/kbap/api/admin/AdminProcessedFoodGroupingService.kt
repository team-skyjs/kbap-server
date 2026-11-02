package com.kbap.api.admin

import com.kbap.api.processedfood.ProcessedFoodGrouping
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminProcessedFoodGroupingService(private val products: ProcessedFoodProductJpaRepository) {
    @Transactional(readOnly = true)
    fun getGroupingPreview(sampleSize: Int): AdminProcessedFoodGroupingPreview {
        val rows = products.findCandidateRows().map { row ->
            val maker = ProcessedFoodProduct.displayMakerOf(row.manufacturer, row.seller, row.makerSupplement)
            GroupedRow(row.id, row.normalizedName, maker, ProcessedFoodGrouping.keyOf(row.normalizedName, maker, ignoreSize = false), ProcessedFoodGrouping.keyOf(row.normalizedName, maker))
        }
        val merged = rows.groupBy { it.after }.values
            .filter { group -> group.map { it.before }.distinct().size > 1 }
            .sortedWith(compareByDescending<List<GroupedRow>> { it.size }.thenBy { it.first().after })
        return AdminProcessedFoodGroupingPreview(
            products = rows.size,
            groupsBefore = rows.map { it.before }.distinct().size,
            groupsAfter = rows.map { it.after }.distinct().size,
            mergedGroups = merged.size,
            samples = merged.take(sampleSize).map { group ->
                AdminProcessedFoodGroupingSample(group.first().after, group.sortedBy { it.id }.map { AdminProcessedFoodGroupingMember(it.id, it.name, it.maker) })
            },
        )
    }

    private data class GroupedRow(val id: Long, val name: String, val maker: String?, val before: String, val after: String)
}

@Schema(description = "용량·용기 묶음 규칙 미리보기 — 활성 가공식품 전체를 옛 묶음 키(이름+제조사)와 새 키(용량·용기 낱말 제거)로 나눈 수와, 새로 합쳐지는 묶음 표본. 제조사 미상 접기(#516) 전 기준")
data class AdminProcessedFoodGroupingPreview(
    val products: Int,
    val groupsBefore: Int,
    val groupsAfter: Int,
    @field:Schema(description = "옛 묶음 둘 이상이 합쳐진 새 묶음 수")
    val mergedGroups: Int,
    @field:Schema(description = "합쳐진 묶음 표본 — 구성원 수 내림차순")
    val samples: List<AdminProcessedFoodGroupingSample>,
)

data class AdminProcessedFoodGroupingSample(val key: String, val members: List<AdminProcessedFoodGroupingMember>)

data class AdminProcessedFoodGroupingMember(val id: Long, val normalizedName: String, val maker: String?)
