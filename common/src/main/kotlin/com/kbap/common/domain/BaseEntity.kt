package com.kbap.common.domain

import jakarta.persistence.Column
import jakarta.persistence.Enumerated
import jakarta.persistence.EnumType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.SQLRestriction
import org.hibernate.annotations.UpdateTimestamp
import java.time.LocalDateTime

@MappedSuperclass
@SQLRestriction("status = 'ACTIVE'")
abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open val id: Long = 0

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, columnDefinition = "ENUM('ACTIVE','DELETED')")
    private var status: EntityStatus = EntityStatus.ACTIVE

    @CreationTimestamp
    open val createdAt: LocalDateTime = LocalDateTime.MIN

    @UpdateTimestamp
    open val updatedAt: LocalDateTime = LocalDateTime.MIN

    fun active() {
        status = EntityStatus.ACTIVE
    }

    fun isActive(): Boolean {
        return status == EntityStatus.ACTIVE
    }

    fun delete() {
        status = EntityStatus.DELETED
    }

    fun isDeleted(): Boolean {
        return status == EntityStatus.DELETED
    }
}
