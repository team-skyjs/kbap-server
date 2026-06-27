package com.meogo.api.scan.infrastructure

import org.springframework.data.jpa.repository.JpaRepository

interface MenuScanJpaRepository : JpaRepository<MenuScanJpaEntity, Long>
