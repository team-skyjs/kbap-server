package com.kbap.api.admin

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDateTime

@Schema(description = "음식 이미지 갤러리 — 대표가 항상 첫 번째, 이후 정렬 순서")
data class AdminFoodImageGalleryResponse(
    @field:Schema(description = "음식 id", example = "42")
    val foodId: Long,

    @field:Schema(description = "음식 낙관잠금 버전. 대표 교체 요청 바디에 그대로 되돌려 보낸다", example = "3")
    val version: Long,

    @field:Schema(description = "음식 콘텐츠 상태", example = "READY")
    val contentStatus: String,

    @field:Schema(
        description = "마지막 재생성 상태 — 진행 중(IN_PROGRESS)·실패(FAILED)와 제출 시 의도·사유. 이력 없음·마지막 성공이면 null",
        nullable = true,
    )
    val regeneration: AdminRegenerationStateResponse?,

    @field:Schema(description = "이미지 목록. 소프트 삭제된 이미지는 제외되며 없으면 빈 배열")
    val items: List<Item>,
) {
    @Schema(description = "갤러리 이미지 한 장")
    data class Item(
        @field:Schema(description = "이미지 id", example = "7")
        val id: Long,
        @field:Schema(description = "저장 키(도메인 없는 경로)", example = "images/webp/bulgogi.webp")
        val imageKey: String,
        @field:Schema(description = "해석된 이미지 URL", example = "https://cdn.kbap.site/images/webp/bulgogi.webp", nullable = true)
        val imageUrl: String?,
        @field:Schema(description = "대표 여부", example = "true")
        val isPrimary: Boolean,
        @field:Schema(description = "정렬 순서", example = "0")
        val sortOrder: Int,
        @field:Schema(description = "생성 출처", example = "GENERATED")
        val source: String,
        @field:Schema(description = "등록 시각")
        val createdAt: LocalDateTime,
    )

    companion object {
        fun from(result: AdminFoodImageGalleryResult): AdminFoodImageGalleryResponse =
            AdminFoodImageGalleryResponse(
                foodId = result.foodId,
                version = result.version,
                contentStatus = result.contentStatus,
                regeneration = result.regeneration,
                items = result.items.map {
                    Item(
                        id = it.id,
                        imageKey = it.imageKey,
                        imageUrl = it.imageUrl,
                        isPrimary = it.isPrimary,
                        sortOrder = it.sortOrder,
                        source = it.source,
                        createdAt = it.createdAt,
                    )
                },
            )
    }
}
