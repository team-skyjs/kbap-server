package com.kbap.common.domain.curation

import com.kbap.common.domain.curation.model.*
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CurationReleaseRepository : JpaRepository<CurationRelease, Long> {
    fun findByRegionKey(regionKey: String): CurationRelease?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CurationRelease r where r.regionKey = 'SEOUL'")
    fun lockSeoul(): CurationRelease?
}
interface CurationCollectionRepository : JpaRepository<CurationCollection, Long> {
    fun findByCollectionKey(collectionKey: String): CurationCollection?
    fun findByReleaseId(releaseId: Long): List<CurationCollection>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CurationCollection c where c.releaseId = :releaseId order by c.id")
    fun lockByReleaseId(@Param("releaseId") releaseId: Long): List<CurationCollection>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CurationCollection c where c.collectionKey = :key")
    fun lockByKey(@Param("key") key: String): CurationCollection?
}
interface CurationAssetRepository : JpaRepository<CurationAsset, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CurationAsset a where a.id = :id")
    fun lockById(@Param("id") id: Long): CurationAsset?

    fun findByAssetStateAndDisplayKeyIsNullAndFinalKeyStartingWithOrderById(assetState: String, finalKeyPrefix: String): List<CurationAsset>
}
