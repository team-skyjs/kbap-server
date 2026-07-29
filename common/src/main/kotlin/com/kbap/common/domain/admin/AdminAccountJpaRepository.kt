package com.kbap.common.domain.admin

import com.kbap.common.domain.admin.model.AdminAccount
import org.springframework.data.jpa.repository.JpaRepository

interface AdminAccountJpaRepository : JpaRepository<AdminAccount, Long> {
    fun findByLoginId(loginId: String): AdminAccount?
}
