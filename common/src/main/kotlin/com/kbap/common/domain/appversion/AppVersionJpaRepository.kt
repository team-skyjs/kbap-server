package com.kbap.common.domain.appversion

import com.kbap.common.domain.appversion.model.AppVersion
import org.springframework.data.jpa.repository.JpaRepository

interface AppVersionJpaRepository : JpaRepository<AppVersion, Long> {
    fun findTopByOrderByIdAsc(): AppVersion?
}
