package com.kbap.common.domain.food

import com.kbap.common.domain.food.model.FoodViewLog
import org.springframework.data.jpa.repository.JpaRepository

interface FoodViewLogJpaRepository : JpaRepository<FoodViewLog, Long>
