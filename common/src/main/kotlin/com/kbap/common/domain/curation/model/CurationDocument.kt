package com.kbap.common.domain.curation.model

data class CurationDocument(
    val title: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val cover: CurationPhoto? = null,
    val items: List<CurationEntry> = emptyList(),
    val editorialReview: CurationReview? = null,
)

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
)

data class CurationNearby(val nearbyId: String = "", val label: Map<String, String> = emptyMap())
data class CurationPhoto(val assetId: Long, val rights: CurationRights = CurationRights())
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
data class CurationEvidence(
    val placeIdentity: CurationPlaceIdentity = CurationPlaceIdentity(),
    val researchSources: List<String> = emptyList(),
    val checkedOn: String = "",
    val externalLinkReview: CurationLinkReview? = null,
    val editorialNotes: String = "",
)
data class CurationLinkReview(val url: String = "", val finalUrl: String = "", val checkedOn: String = "")
data class CurationReview(val contentHash: String = "", val checkedOn: String = "")

data class CurationPlaceIdentity(val canonicalName: String = "", val locationDiscriminator: String = "")
