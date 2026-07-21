package com.kbap.app.batch.content

import com.kbap.domain.food.FoodContentBatchService
import com.kbap.domain.food.model.Food
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader

// 재시작 복원 지점은 "마지막으로 넘긴 음식 id"라 버퍼에 남은 미처리 건을 건너뛰지 않는다.
class IncompleteFoodItemReader(
    private val foodContentBatchService: FoodContentBatchService,
    private val pageSize: Int,
) : ItemStreamReader<Food> {
    private val buffer = ArrayDeque<Food>()
    private var lastReadId: Long? = null

    override fun open(executionContext: ExecutionContext) {
        lastReadId = if (executionContext.containsKey(CURSOR_KEY)) executionContext.getLong(CURSOR_KEY) else null
        buffer.clear()
    }

    override fun read(): Food? {
        if (buffer.isEmpty()) {
            val page = foodContentBatchService.getIncompleteFoods(lastReadId, pageSize)
            if (page.isEmpty()) return null
            buffer.addAll(page)
        }
        return buffer.removeFirst().also { lastReadId = it.id }
    }

    override fun update(executionContext: ExecutionContext) {
        lastReadId?.let { executionContext.putLong(CURSOR_KEY, it) }
    }

    override fun close() {
        buffer.clear()
    }

    companion object {
        private const val CURSOR_KEY = "food.content.lastReadId"
    }
}
