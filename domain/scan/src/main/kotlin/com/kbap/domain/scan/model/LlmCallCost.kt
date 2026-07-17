package com.kbap.domain.scan.model

import com.kbap.core.persistence.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.math.BigDecimal

@Entity
@Table(
    name = "llm_call_cost",
    indexes = [Index(name = "idx_llm_call_cost_created_at", columnList = "created_at")],
)
class LlmCallCost(
    @Column(name = "model_name", nullable = false, length = 100)
    var modelName: String = "",

    @Column(name = "input_tokens", nullable = false)
    var inputTokens: Long = 0,

    @Column(name = "output_tokens", nullable = false)
    var outputTokens: Long = 0,

    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    var costUsd: BigDecimal = BigDecimal.ZERO,

    @Column(name = "cost_krw", nullable = false, precision = 14, scale = 2)
    var costKrw: BigDecimal = BigDecimal.ZERO,
) : BaseEntity()
