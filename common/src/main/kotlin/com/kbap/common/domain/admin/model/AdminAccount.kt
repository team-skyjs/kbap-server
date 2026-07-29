package com.kbap.common.domain.admin.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

@Entity
@Table(
    name = "admin_account",
    uniqueConstraints = [UniqueConstraint(name = "uk_admin_account_login_id", columnNames = ["login_id"])],
)
class AdminAccount(
    @Column(name = "login_id", nullable = false, length = 50)
    var loginId: String = "",

    @Column(name = "password", nullable = false, length = 60)
    var password: String = "",
) : BaseEntity()
