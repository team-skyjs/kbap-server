package com.kbap.common.domain.scan.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "menu_name_estimate",
    uniqueConstraints = [UniqueConstraint(name = "uq_menu_name_estimate_name_key", columnNames = ["name_key"])],
)
class MenuNameEstimate(
    @Column(name = "name_key", nullable = false, length = 255)
    val nameKey: String = "",

    @Column(name = "kind", nullable = false, length = 16)
    val kind: String = "",

    @Column(name = "english_name", length = 200)
    val englishName: String? = null,

    @Column(name = "description", length = 500)
    val description: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "avoidance_codes", columnDefinition = "json")
    val avoidanceCodes: List<String>? = null,

    @Column(name = "spiciness", nullable = false)
    val spiciness: Int = -1,

    @Column(name = "confidence", precision = 4, scale = 3)
    val confidence: BigDecimal? = null,

    @Column(name = "model", nullable = false, length = 64)
    val model: String = "",

    @Column(name = "prompt_version", nullable = false, length = 32)
    val promptVersion: String = "",

    @Column(name = "promoted_at")
    var promotedAt: LocalDateTime? = null,

    @Column(name = "promoted_by")
    var promotedBy: Long? = null,
) : BaseEntity() {
    fun isPromoted(): Boolean = promotedAt != null

    fun promote(adminId: Long) {
        promotedAt = LocalDateTime.now()
        promotedBy = adminId
    }
}
