package com.meogo.domain.scan.infrastructure

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.time.Instant

/**
 * menu_scan 영속 엔티티. scan 모듈 내부에 은닉(상위 import 금지).
 * 항목은 단방향 @OneToMany(scan_id FK) 로 소유 — 자식에 부모 역참조를 두지 않아 프록시 불필요.
 * 모든 프로퍼티에 기본값을 둬 Kotlin 이 no-arg 생성자를 합성(Hibernate 요구) 하게 한다.
 */
@Entity
@Table(name = "menu_scan")
class MenuScanJpaEntity(
    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,

    @field:Column(nullable = false)
    var status: String = "",

    @field:Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.EPOCH,

    @field:OneToMany(cascade = [CascadeType.ALL], fetch = FetchType.EAGER, orphanRemoval = true)
    @field:JoinColumn(name = "scan_id", nullable = false)
    var items: MutableList<ScannedMenuItemJpaEntity> = mutableListOf(),
)
