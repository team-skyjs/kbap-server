package com.kbap.common.domain.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodProduct
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface ProcessedFoodProductJpaRepository : JpaRepository<ProcessedFoodProduct, Long> {
    fun findAllByReportNoIn(reportNos: Collection<String>): List<ProcessedFoodProduct>

    @Query(
        """
        select p from ProcessedFoodProduct p
        where p.makerCheckedAt is null
          and (p.manufacturer is null or trim(p.manufacturer) = '' or p.manufacturer like '알수없음%' escape '\')
          and (p.seller is null or trim(p.seller) = '' or p.seller like '알수없음%' escape '\')
        order by p.id
        """,
    )
    fun findMakerLookupTargets(pageable: Pageable): List<ProcessedFoodProduct>
}
