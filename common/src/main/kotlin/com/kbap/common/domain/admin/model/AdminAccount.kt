package com.kbap.common.domain.admin.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "admin_account",
    uniqueConstraints = [UniqueConstraint(name = "uk_admin_account_admin_id", columnNames = ["admin_id"])],
)
class AdminAccount(
    @Column(name = "admin_id", nullable = false, length = 50)
    var loginId: String = "",

    @Column(name = "admin_pwd", nullable = false, length = 60)
    var password: String = "",

    @Column(name = "display_name", length = MAX_DISPLAY_NAME_LENGTH)
    var displayName: String? = null,
) : BaseEntity() {
    fun displayNameOrLoginId(): String = displayName?.takeIf { it.isNotBlank() } ?: loginId

    companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 50
    }
}
