package com.meogo.infra.persistence.food

import org.springframework.data.jpa.repository.JpaRepository

interface IngredientJpaRepository : JpaRepository<IngredientJpaEntity, Long>
