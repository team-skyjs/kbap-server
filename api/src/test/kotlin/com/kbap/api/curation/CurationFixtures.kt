package com.kbap.api.curation

import com.kbap.common.domain.curation.model.*
import java.time.LocalDate
import java.util.UUID

object CurationFixtures {
    fun text(value: String) = CurationValidation.languages.associateWith { "$it $value" }
    fun document(key: String, assetId: Long): CurationDocument {
        val today = LocalDate.now().toString()
        val photo = CurationPhoto(assetId, CurationRights("DIRECT", "synthetic-evidence", "fixture author", "fixture display", today, assetId, AttributionRequirement.NONE))
        val document = CurationDocument(text("title"), text("description"), photo, (1..10).map { i ->
            val url = "https://example.invalid/$key/$i"
            CurationEntry(UUID.nameUUIDFromBytes("$key/$i".toByteArray()).toString(), text("name$i"), text("area$i"), text("intro$i"), text("reason$i"), photo, url,
                if (key == "stations") (1..2).map { CurationNearby("n$it", text("nearby$it")) } else emptyList(),
                CurationEvidence(CurationPlaceIdentity("place$i", "location$i"), listOf("https://example.invalid/research/$key/$i"), today, CurationLinkReview(url, url, today)))
        })
        return reviewed(document)
    }
    fun reviewed(document: CurationDocument): CurationDocument = document.copy(editorialReview = CurationReview(CurationValidation().contentHash(document), LocalDate.now().toString()))
}
