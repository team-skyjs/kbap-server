package com.kbap.api.curation

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.curation.model.*
import org.springframework.stereotype.Component
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDate
import java.util.UUID

data class CurationIssue(val path: String, val reason: String, val entryId: String? = null, val locale: String? = null)

@Component
class CurationValidation {
    private val mapper = jacksonObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)

    fun copy(document: CurationDocument): CurationDocument = mapper.readValue(mapper.writeValueAsBytes(document), CurationDocument::class.java)

    fun contentHash(document: CurationDocument): String = MessageDigest.getInstance("SHA-256")
        .digest(mapper.writeValueAsBytes(document.copy(editorialReview = null)))
        .joinToString("") { "%02x".format(it) }

    fun structure(document: CurationDocument): List<CurationIssue> = buildList {
        if (document.items.size > 50 || mapper.writeValueAsBytes(document).size > 1024 * 1024) add(CurationIssue("document", "DOCUMENT_LIMIT"))
        texts(document).forEach { (path, values, limit, id) ->
            val raw: Map<*, *> = values
            raw.forEach { (locale, text) ->
                if (locale !in languages) add(CurationIssue(path, "UNSUPPORTED_LOCALE", id, locale as? String))
                if (text !is String) add(CurationIssue(path, "INVALID_TEXT", id, locale as? String))
                else if (text.length > limit) add(CurationIssue(path, "TEXT_TOO_LONG", id, locale as? String))
            }
        }
        document.items.forEachIndexed { i, item ->
            if (runCatching { UUID.fromString(item.entryId).toString() == item.entryId.lowercase() }.getOrDefault(false).not()) add(CurationIssue("items[$i].entryId", "INVALID_ENTRY_ID", item.entryId))
            if (item.externalUrl.length > 2048 || item.nearby.size > 50) add(CurationIssue("items[$i]", "DOCUMENT_LIMIT", item.entryId))
            if (item.nearby.map { it.nearbyId }.distinct().size != item.nearby.size || item.nearby.any { it.nearbyId.isBlank() }) add(CurationIssue("items[$i].nearby", "INVALID_NEARBY_ID", item.entryId))
        }
        if (document.items.map { it.entryId.lowercase() }.distinct().size != document.items.size) add(CurationIssue("items", "DUPLICATE_ENTRY"))
    }

    fun validate(key: String, document: CurationDocument): List<CurationIssue> = buildList {
        addAll(structure(document))
        if (document.items.size != 10) add(CurationIssue("items", "ITEM_COUNT"))
        texts(document).forEach { (path, values, _, id) ->
            languages.forEach { locale -> if (values[locale].isNullOrBlank()) add(CurationIssue(path, "TRANSLATION_MISSING", id, locale)) }
        }
        photos(document).forEach { (path, photo, id) ->
            if (photo == null) add(CurationIssue(path, "ASSET_NOT_READY", id))
            else {
                val r = photo.rights
                if (r.attributionRequirement != AttributionRequirement.NONE) add(CurationIssue("$path.rights.attributionRequirement", "ATTRIBUTION_UNSUPPORTED", id))
                if (listOf(r.basis, r.sourceOrEvidenceRef, r.rightsHolder, r.permittedUse).any { it.isBlank() } || !dateValid(r.checkedOn) || r.checkedAssetId != photo.assetId) add(CurationIssue("$path.rights", "PHOTO_RIGHTS_MISSING", id))
            }
        }
        document.items.forEachIndexed { i, item ->
            val path = "items[$i]"
            if ((key == "stations" && item.nearby.size !in 2..3) || (key != "stations" && item.nearby.isNotEmpty())) add(CurationIssue("$path.nearby", "NEARBY_COUNT", item.entryId))
            if (!urlValid(item.externalUrl)) add(CurationIssue("$path.externalUrl", "INVALID_EXTERNAL_URL", item.entryId))
            val evidence = item.evidence
            if (evidence.placeIdentity.canonicalName.isBlank() || evidence.placeIdentity.locationDiscriminator.isBlank()) add(CurationIssue("$path.evidence", "PLACE_IDENTITY_MISSING", item.entryId))
            if (evidence.researchSources.isEmpty() || evidence.researchSources.any { !urlValid(it) } || !dateValid(evidence.checkedOn)) add(CurationIssue("$path.evidence", "RESEARCH_EVIDENCE_MISSING", item.entryId))
            val link = evidence.externalLinkReview
            if (link == null || link.url != item.externalUrl || !urlValid(link.finalUrl) || !dateValid(link.checkedOn)) add(CurationIssue("$path.evidence.externalLinkReview", "EXTERNAL_LINK_UNVERIFIED", item.entryId))
        }
        val identities = document.items.map { normalize(it.evidence.placeIdentity.canonicalName) to normalize(it.evidence.placeIdentity.locationDiscriminator) }
        if (identities.distinct().size != identities.size) add(CurationIssue("items", "DUPLICATE_PLACE"))
        val urls = document.items.map { normalizedUrl(it.externalUrl) }
        if (urls.distinct().size != urls.size) add(CurationIssue("items", "DUPLICATE_EXTERNAL_URL"))
        if (document.editorialReview?.contentHash != contentHash(document) || !dateValid(document.editorialReview?.checkedOn.orEmpty())) add(CurationIssue("editorialReview", "EDITORIAL_REVIEW_MISSING"))
    }

    fun hasLanguage(document: CurationDocument, language: String, cardsOnly: Boolean = false): Boolean =
        texts(document).filter { !cardsOnly || it.path == "title" || it.path == "description" }.all { !it.values[language].isNullOrBlank() }

    fun photos(document: CurationDocument): List<PhotoField> = listOf(PhotoField("cover", document.cover, null)) +
        document.items.mapIndexed { i, it -> PhotoField("items[$i].image", it.image, it.entryId) }

    private fun texts(document: CurationDocument): List<TextField> = buildList {
        add(TextField("title", document.title, 120, null))
        add(TextField("description", document.description, 400, null))
        document.items.forEachIndexed { i, it ->
            add(TextField("items[$i].name", it.name, 200, it.entryId))
            add(TextField("items[$i].area", it.area, 120, it.entryId))
            add(TextField("items[$i].introduction", it.introduction, 1000, it.entryId))
            add(TextField("items[$i].recommendationReason", it.recommendationReason, 600, it.entryId))
            it.nearby.forEachIndexed { n, nearby -> add(TextField("items[$i].nearby[$n].label", nearby.label, 200, it.entryId)) }
        }
    }

    private fun dateValid(raw: String): Boolean = runCatching { !LocalDate.parse(raw).isAfter(LocalDate.now()) }.getOrDefault(false)
    private fun normalize(raw: String): String = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKC).lowercase().replace(Regex("\\s+"), " ")
    private fun normalizedUrl(raw: String): String = runCatching {
        val u = URI(raw).normalize()
        URI(u.scheme?.lowercase(), u.userInfo, u.host?.lowercase(), if (u.port == 443) -1 else u.port, u.path.ifEmpty { "/" }, u.query, null).toASCIIString()
    }.getOrDefault(raw)

    fun urlValid(raw: String): Boolean = runCatching {
        val uri = URI(raw)
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
        if (raw.any { it.isISOControl() } || !uri.scheme.equals("https", true) || uri.userInfo != null || uri.port == 0 || uri.port > 65535 || Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE).containsMatchIn(raw)) return false
        if (host.contains(':')) {
            if (!host.matches(Regex("[0-9a-f:.]+"))) return false
            val address = java.net.InetAddress.getByName(host)
            return !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isSiteLocalAddress && !address.isMulticastAddress && (address.address[0].toInt() and 0xfe) != 0xfc
        }
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || !host.contains('.')) return false
        if (host.split('.').any { it.startsWith("0x") }) return false
        if (host.all { it.isDigit() || it == '.' }) {
            val parts = host.split('.').map { it.toIntOrNull() ?: return false }
            if (parts.size != 4 || parts.any { it !in 0..255 } || host.split('.').any { it.length > 1 && it.startsWith('0') }) return false
            val a = parts[0]; val b = parts[1]
            if (a == 0 || a == 10 || a == 127 || a >= 224 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)) return false
        }
        true
    }.getOrDefault(false)

    data class PhotoField(val path: String, val photo: CurationPhoto?, val entryId: String?)
    private data class TextField(val path: String, val values: Map<String, String>, val limit: Int, val entryId: String?)
    companion object { val languages = LanguageCode.entries.map { it.code }.toSet() }
}
