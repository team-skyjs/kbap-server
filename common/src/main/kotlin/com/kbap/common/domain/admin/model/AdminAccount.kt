package com.kbap.common.domain.admin.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

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
) : BaseEntity() {
    @Column(name = "last_login_at")
    var lastLoginAt: LocalDateTime? = null

    @Column(name = "password_changed_at")
    var passwordChangedAt: LocalDateTime? = null

    fun recordLogin() {
        lastLoginAt = LocalDateTime.now()
    }

    fun changePassword(encodedPassword: String) {
        password = encodedPassword
        passwordChangedAt = LocalDateTime.now()
    }

    companion object {
        const val MIN_LOGIN_ID_LENGTH = 4
        const val MAX_LOGIN_ID_LENGTH = 50
        const val MIN_PASSWORD_LENGTH = 8
    }
}
