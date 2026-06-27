package com.meogo.domain.scan.infrastructure

import org.springframework.data.jpa.repository.JpaRepository

/** Spring Data JPA 리포지토리(scan 모듈 내부). */
interface MenuScanJpaRepository : JpaRepository<MenuScanJpaEntity, Long>
