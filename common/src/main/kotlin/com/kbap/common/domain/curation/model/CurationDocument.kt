package com.kbap.common.domain.curation.model

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class CurationDocument(
    val title: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val cover: CurationPhoto? = null,
    val items: List<CurationEntry> = emptyList(),
    val editorialReview: CurationReview? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CurationEntry(
    val entryId: String = "",
    val name: Map<String, String> = emptyMap(),
    val area: Map<String, String> = emptyMap(),
    val introduction: Map<String, String> = emptyMap(),
    val recommendationReason: Map<String, String> = emptyMap(),
    val image: CurationPhoto? = null,
    val externalUrl: String = "",
    val nearby: List<CurationNearby> = emptyList(),
    val evidence: CurationEvidence = CurationEvidence(),
    val images: List<CurationPhoto> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class CurationNearby(val nearbyId: String = "", val label: Map<String, String> = emptyMap())
@JsonIgnoreProperties(ignoreUnknown = true)
data class CurationPhoto(val assetId: Long, val rights: CurationRights = CurationRights())
@JsonIgnoreProperties(ignoreUnknown = true)
data class CurationRights(
    val basis: String = "",
    val sourceOrEvidenceRef: String = "",
    val rightsHolder: String = "",
    val permittedUse: String = "",
    val checkedOn: String = "",
    val checkedAssetId: Long? = null,
    val attributionRequirement: AttributionRequirement? = null,
    val attributionText: String? = null,
)
enum class AttributionRequirement { NONE, TEXT, LINK, UNKNOWN }
@JsonIgnoreProperties(ignoreUnknown = true)
data class CurationEvidence(
    val placeIdentity: CurationPlaceIdentity = CurationPlaceIdentity(),
    val researchSources: List<String> = emptyList(),
    val checkedOn: String = "",
    val externalLinkReview: CurationLinkReview? = null,
    val editorialNotes: String = "",
)
@JsonIgnoreProperties(ignoreUnknown = true)
data class CurationLinkReview(val url: String = "", val finalUrl: String = "", val checkedOn: String = "")
@JsonIgnoreProperties(ignoreUnknown = true)
data class CurationReview(val contentHash: String = "", val checkedOn: String = "")

@JsonIgnoreProperties(ignoreUnknown = true)
data class CurationPlaceIdentity(val canonicalName: String = "", val locationDiscriminator: String = "")
