// KB-301: 음식 콘텐츠 채움이 kbap-langchain 으로 이관돼 이 잡은 더 이상 실행하지 않는다.
// 복구 가능성을 위해 원본을 주석으로 보존한다 — 최종 삭제는 KB-302.
// package com.kbap.batch.content
//
// import com.kbap.common.domain.food.FoodJpaRepository
// import com.kbap.common.domain.food.model.Food
// import com.kbap.common.domain.food.model.FoodContentStatus
// import org.springframework.batch.infrastructure.item.Chunk
// import org.springframework.batch.infrastructure.item.ItemWriter
//
// class FoodContentItemWriter(
//     private val foodRepository: FoodJpaRepository,
// ) : ItemWriter<Food> {
//
//     override fun write(chunk: Chunk<out Food>) {
//         val byTarget = chunk.items.groupBy { it.transitionByContentState() }
//         byTarget[FoodContentStatus.PENDING_IMAGE]
//             ?.let { foodRepository.markPendingImageByIdIn(it.map(Food::id)) }
//         byTarget[FoodContentStatus.PENDING_REVIEW]
//             ?.let { foodRepository.markPendingReviewByIdIn(it.map(Food::id)) }
//     }
// }
