package com.kbap.api.curation

import com.kbap.common.domain.curation.model.*
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class CurationValidationTest : BehaviorSpec({
    given("큐레이션 게시 검증") {
        `when`("빈 초안이면") {
            then("항목수와 필수 번역과 사진 결손을 반환한다") {
                val issues = CurationValidation().validate("sights", CurationDocument())
                issues.map { it.reason } shouldContain "ITEM_COUNT"
                issues.map { it.reason } shouldContain "TRANSLATION_MISSING"
                issues.map { it.reason } shouldContain "ASSET_NOT_READY"
            }
        }
        `when`("링크 표시 의무가 있는 표지이면") {
            then("게시를 차단한다") {
                val document = CurationDocument(cover = CurationPhoto(1, CurationRights(attributionRequirement = AttributionRequirement.LINK)))
                CurationValidation().validate("sights", document).map { it.reason } shouldContain "ATTRIBUTION_UNSUPPORTED"
            }
        }
    }
    given("항목 사진 여러 장(갤러리)") {
        val validator = CurationValidation()
        val base = CurationFixtures.document("sights", 1)
        val first = base.items[0]
        fun photo(id: Long, rights: CurationRights.() -> CurationRights = { this }) = first.image!!.copy(assetId = id, rights = first.image!!.rights.copy(checkedAssetId = id).rights())
        fun withImages(vararg images: CurationPhoto) = CurationFixtures.reviewed(base.copy(items = listOf(first.copy(images = images.toList())) + base.items.drop(1)))
        `when`("첫 장이 image 이고 사진마다 권리 증빙이 있으면") {
            then("검증을 통과한다") { validator.validate("sights", withImages(first.image!!, photo(2), photo(3))) shouldBe emptyList() }
        }
        `when`("둘째 사진의 권리 증빙이 비거나 출처 문구가 80자를 넘으면") {
            then("그 사진 경로로 PHOTO_RIGHTS_MISSING·ATTRIBUTION_TEXT_INVALID 를 낸다") {
                val missing = validator.validate("sights", withImages(first.image!!, photo(2) { copy(rightsHolder = "") }))
                missing.map { it.path to it.reason } shouldContain ("items[0].images[1].rights" to "PHOTO_RIGHTS_MISSING")
                val long = validator.validate("sights", withImages(first.image!!, photo(2) { copy(attributionRequirement = AttributionRequirement.TEXT, attributionText = "a".repeat(81)) }))
                long.map { it.path to it.reason } shouldContain ("items[0].images[1].rights.attributionText" to "ATTRIBUTION_TEXT_INVALID")
            }
        }
        `when`("첫 장이 image 와 다르거나 같은 사진이 두 번 들어가면") {
            then("GALLERY_INVALID 로 막는다") {
                validator.validate("sights", withImages(photo(2), first.image!!)).map { it.reason } shouldContain "GALLERY_INVALID"
                validator.validate("sights", withImages(first.image!!, photo(2), photo(2))).map { it.reason } shouldContain "GALLERY_INVALID"
            }
        }
        `when`("사진이 10장을 넘으면") {
            then("그 항목 갤러리 경로(items[i].images)의 DOCUMENT_LIMIT 로 막는다") {
                validator.validate("sights", withImages(first.image!!, *(2L..11L).map { photo(it) }.toTypedArray())).map { it.path to it.reason } shouldContain ("items[0].images" to "DOCUMENT_LIMIT")
            }
        }
        `when`("사진 목록이 없는 문서(배포 전 검수본)이면 — 공부 #432") {
            then("발행본 해시가 이 PR 이전과 같은 고정 문자열이고 직렬화에 images 키가 없다") {
                val photo = CurationPhoto(7, CurationRights("DIRECT", "evidence", "holder", "display", "2026-10-01", 7, AttributionRequirement.NONE))
                val doc = CurationDocument(mapOf("en" to "title"), mapOf("en" to "desc"), photo, listOf(CurationEntry("0b6b5c6e-3f0a-4c8e-9a51-1f0f3b7d2a10", mapOf("en" to "name"), mapOf("en" to "area"), mapOf("en" to "intro"), mapOf("en" to "reason"), photo, "https://example.invalid/1")))
                validator.contentHash(doc) shouldBe "9adacb574997f9d64f6e03b3caa027116b47f65b50a547eb67f3964f1bb293d4"
                com.fasterxml.jackson.module.kotlin.jacksonObjectMapper().writeValueAsString(doc.items[0]).contains("\"images\"") shouldBe false
            }
        }
        `when`("검수 뒤 사진 목록이 바뀌면") {
            then("발행본 해시가 달라져 EDITORIAL_REVIEW_MISSING 이다") {
                val reviewed = withImages(first.image!!, photo(2))
                val changed = reviewed.copy(items = listOf(reviewed.items[0].copy(images = listOf(first.image!!, photo(3)))) + reviewed.items.drop(1))
                validator.validate("sights", changed).map { it.reason } shouldContain "EDITORIAL_REVIEW_MISSING"
            }
        }
    }
    given("승인된 게시 문서") {
        val validator = CurationValidation()
        val valid = CurationFixtures.document("sights", 1)
        `when`("10항목과 10언어 및 NONE 증빙이 있으면") {
            then("검증을 통과한다") { validator.validate("sights", valid) shouldBe emptyList() }
        }
        `when`("두 항목의 외부 링크가 호스트 끝점(.)만 다르면 — Codex P2") {
            then("같은 목적지로 보아 DUPLICATE_EXTERNAL_URL 로 막는다") {
                val first = valid.items[0]
                val twin = valid.items[1].copy(externalUrl = first.externalUrl.replace("example.invalid/", "example.invalid./"))
                validator.validate("sights", valid.copy(items = listOf(first, twin) + valid.items.drop(2))).map { it.reason } shouldContain "DUPLICATE_EXTERNAL_URL"
            }
        }
        `when`("조사 출처가 URL 이 아니거나 https 가 아니면 — Codex P2") {
            then("그 항목을 RESEARCH_EVIDENCE_MISSING 으로 막는다(외부 링크와 같은 URL 안전 규칙)") {
                listOf("not a URL", "http://example.invalid/research", "https://user@example.invalid/x").forEach { source ->
                    val item = valid.items.first().let { it.copy(evidence = it.evidence.copy(researchSources = listOf(source))) }
                    validator.validate("sights", valid.copy(items = listOf(item) + valid.items.drop(1)))
                        .any { it.reason == "RESEARCH_EVIDENCE_MISSING" && it.entryId == item.entryId } shouldBe true
                }
            }
        }
        `when`("표시 의무가 TEXT 이고 출처 문구가 있으면") {
            then("표지·항목 모두 게시를 허용한다") {
                val rights = valid.cover!!.rights.copy(attributionRequirement = AttributionRequirement.TEXT, attributionText = "Photo: Korea Tourism Organization (KOGL Type 1)")
                val cover = valid.cover!!.copy(rights = rights)
                val item = valid.items.first().copy(image = cover)
                validator.validate("sights", valid.copy(cover = cover, items = listOf(item) + valid.items.drop(1))).map { it.reason }.filter { it.startsWith("ATTRIBUTION") } shouldBe emptyList()
            }
        }
        `when`("표시 의무가 TEXT 인데 출처 문구가 없거나 80자를 넘거나 제어문자를 품으면") {
            then("표지·항목 모두 ATTRIBUTION_TEXT_INVALID 로 막는다") {
                listOf(null, "", "   ", "a".repeat(81), "Photo:\nname", "Photo:\u2028name", "Photo:\u2029name", "Photo:\u0085name", "Photo:\u202Eeman", "Photo:\u2066name", "Photo:\u200Bname", "Photo:\uFEFFname", "Photo:" + String(Character.toChars(0xE0001)) + "name").forEach { text ->
                    val cover = valid.cover!!.copy(rights = valid.cover!!.rights.copy(attributionRequirement = AttributionRequirement.TEXT, attributionText = text))
                    validator.validate("sights", valid.copy(cover = cover)).map { it.path to it.reason } shouldContain ("cover.rights.attributionText" to "ATTRIBUTION_TEXT_INVALID")
                    val item = valid.items.first().copy(image = cover)
                    validator.validate("sights", valid.copy(items = listOf(item) + valid.items.drop(1))).map { it.reason } shouldContain "ATTRIBUTION_TEXT_INVALID"
                }
                val cover = valid.cover!!.copy(rights = valid.cover!!.rights.copy(attributionRequirement = AttributionRequirement.TEXT, attributionText = "a".repeat(80)))
                validator.validate("sights", valid.copy(cover = cover)).map { it.reason } shouldNotContain "ATTRIBUTION_TEXT_INVALID"
            }
        }
        `when`("표지와 항목의 표시 의무가 지원되지 않으면") {
            then("LINK·UNKNOWN·null 을 차단한다") {
                listOf(AttributionRequirement.LINK, AttributionRequirement.UNKNOWN, null).forEach { requirement ->
                    val cover = valid.cover!!.copy(rights = valid.cover!!.rights.copy(attributionRequirement = requirement))
                    validator.validate("sights", valid.copy(cover = cover)).map { it.reason } shouldContain "ATTRIBUTION_UNSUPPORTED"
                    val item = valid.items.first().copy(image = cover)
                    validator.validate("sights", valid.copy(items = listOf(item) + valid.items.drop(1))).map { it.reason } shouldContain "ATTRIBUTION_UNSUPPORTED"
                }
            }
        }
        `when`("언어 값이 공백이거나 항목이 9개 또는11개이면") {
            then("모든 로케일과 두 항목수 경계를 차단한다") {
                CurationValidation.languages.forEach { lang ->
                    validator.validate("sights", valid.copy(title = valid.title + (lang to " "))).any { it.reason == "TRANSLATION_MISSING" && it.locale == lang } shouldBe true
                }
                listOf(valid.items.take(9), valid.items + valid.items.first()).forEach {
                    validator.validate("sights", valid.copy(items = it)).map { it.reason } shouldContain "ITEM_COUNT"
                }
            }
        }
        `when`("검수 후 콘텐츠와 사진과 링크가 변경되면") {
            then("기존 검수 해시로 게시하지 못한다") {
                validator.validate("sights", valid.copy(title = valid.title + ("en" to "changed"))).map { it.reason } shouldContain "EDITORIAL_REVIEW_MISSING"
                val photo = valid.cover!!.copy(assetId = 2)
                validator.validate("sights", valid.copy(cover = photo)).map { it.reason } shouldContain "PHOTO_RIGHTS_MISSING"
                val item = valid.items.first().copy(externalUrl = "https://example.invalid/changed")
                validator.validate("sights", valid.copy(items = listOf(item) + valid.items.drop(1))).map { it.reason } shouldContain "EXTERNAL_LINK_UNVERIFIED"
            }
        }
        `when`("URL이 실행 스킴이나 로컬 주소이면") {
            then("서버 조회 없이 문법으로 거절한다") {
                listOf("http://example.com", "javascript:alert(1)", "https://user@example.com", "https://127.0.0.1", "https://10.0.0.1", "https://192.168.1.1", "https://[::1]", "https://localhost", "https://2130706433").forEach { validator.urlValid(it) shouldBe false }
                validator.urlValid("https://example.invalid/path?language=en") shouldBe true
                listOf("https://localhost.", "https://localhost..", "https://foo.localhost.", "https://printer.local.", "https://127.0.0.1.", "https://10.0.0.1.", "https://intranet.")
                    .forEach { validator.urlValid(it) shouldBe false }
                validator.urlValid("https://example.com./path") shouldBe true
            }
        }
        `when`("역 주변 항목 수가 한개나 네개이면") {
            then("주변 항목의 게시 제한을 지킨다") {
                val station = CurationFixtures.document("stations", 1)
                listOf(1, 4).forEach { count ->
                    val item = station.items.first().let { it.copy(nearby = (1..count).map { n -> CurationNearby("n$n", CurationFixtures.text("nearby$n")) }) }
                    validator.validate("stations", station.copy(items = listOf(item) + station.items.drop(1))).map { it.reason } shouldContain "NEARBY_COUNT"
                }
            }
        }
    }

})
