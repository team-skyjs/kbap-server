package com.kbap.common.domain.appversion.model

import com.kbap.common.domain.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Table

@Entity
@Table(name = "app_version")
class AppVersion(
    @Column(name = "min_supported_version", nullable = false, length = 20)
    var minSupportedVersion: String = "",

    @Column(name = "latest_version", nullable = false, length = 20)
    var latestVersion: String = "",

    @Column(name = "ios_store_url", length = 512)
    var iosStoreUrl: String? = null,

    @Column(name = "aos_store_url", length = 512)
    var aosStoreUrl: String? = null,
) : BaseEntity() {
    fun update(
        minSupportedVersion: String,
        latestVersion: String,
        iosStoreUrl: String?,
        aosStoreUrl: String?,
    ) {
        this.minSupportedVersion = minSupportedVersion
        this.latestVersion = latestVersion
        this.iosStoreUrl = iosStoreUrl
        this.aosStoreUrl = aosStoreUrl
    }
}
