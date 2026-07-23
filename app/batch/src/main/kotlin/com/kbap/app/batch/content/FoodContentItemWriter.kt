package com.kbap.app.batch.content

import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.model.Food
import com.kbap.domain.food.model.FoodContentStatus
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter

// 콘텐츠 채우기는 프로세서가 작업별로 이미 커밋했다 — writer 는 수렴 전이(KB-226) 판정만 담당한다.
// 리더가 넘긴 엔티티는 detached 라 save(merge) 는 건당 SELECT 를 유발하고 전 칼럼을 덮어쓴다(병행
// 이미지 회수의 imageRef 를 지울 수 있음) — 전환 대상만 모아 content_status 만 조건부 벌크 UPDATE 로 친다.
// 스냅샷이 낡아 가드에 걸린 건은 갱신 0건으로 남고 다음 실행이 최신 상태로 수렴한다.
class FoodContentItemWriter(
    private val foodRepository: FoodJpaRepository,
) : ItemWriter<Food> {

    override fun write(chunk: Chunk<out Food>) {
        val byTarget = chunk.items.groupBy { it.transitionByContentState() }
        byTarget[FoodContentStatus.TEXT_READY]
            ?.let { foodRepository.markTextReadyByIdIn(it.map(Food::id)) }
        byTarget[FoodContentStatus.PENDING_REVIEW]
            ?.let { foodRepository.markPendingReviewByIdIn(it.map(Food::id)) }
    }
}
