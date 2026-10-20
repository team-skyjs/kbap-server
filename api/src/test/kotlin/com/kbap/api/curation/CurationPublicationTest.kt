package com.kbap.api.curation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAccount
import com.kbap.common.domain.curation.*
import com.kbap.common.domain.curation.model.*
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.TimeUnit
import java.time.LocalDateTime
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@IntegrationTest
class CurationPublicationTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)
    @Autowired private lateinit var service: CurationService
    @Autowired private lateinit var releases: CurationReleaseRepository
    @Autowired private lateinit var collections: CurationCollectionRepository
    @Autowired private lateinit var assets: CurationAssetRepository
    @Autowired private lateinit var admins: AdminAccountJpaRepository
    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    private var adminId = 0L
    private val documents = mutableMapOf<String, CurationDocument>()
    private fun reset() {
        jdbc.update("DELETE FROM curation_asset")
        jdbc.update("DELETE FROM curation_collection")
        jdbc.update("DELETE FROM curation_release")
        adminId = (admins.findByLoginId("curation-test") ?: admins.save(AdminAccount("curation-test", "test"))).id
        val root = releases.save(CurationRelease())
        CurationService.keys.forEach { key ->
            val row = collections.save(CurationCollection(releaseId = root.id, collectionKey = key))
            val a = assets.save(CurationAsset(collectionId = row.id, issuedByAdminId = adminId, assetState = "READY", stagingKey = "curation/dev/staging/$key", finalKey = "curation/dev/final/$key", contentType = "image/png", sizeBytes = 10, expiresAt = LocalDateTime.now().plusMinutes(5), completeDeadline = LocalDateTime.now().plusDays(1), completedAt = LocalDateTime.now(), sourceFingerprint = "fixture"))
            documents[key] = CurationFixtures.document(key, a.id)
        }
    }
    private fun publish(key: String) {
        val d = service.getAdminDetail(adminId, key)
        val saved = service.updateDraft(adminId, "dev", key, d.version, documents.getValue(key))
        service.publish(adminId, "dev", key, saved.version)
    }
    init {
        beforeEach { reset() }
        afterSpec { reset() }
        given("최초 세 모음 원자 활성화") {
            `when`("두 모음을 게시하고 하나를 내린 뒤 세번째를 게시하면") {
                then("보존 스냅샷 셋을 공개 셋으로 오인하지 않는다") {
                    publish("sights"); publish("stations")
                    service.unpublish(adminId, "dev", "sights", service.getAdminDetail(adminId, "sights").version)
                    publish("busan")
                    service.getIndex("en").state shouldBe "UNPUBLISHED"
                    shouldThrow<BusinessException> { service.getDetail("stations", "en") }.errorCode.code shouldBe "CURATION-001"
                    service.publish(adminId, "dev", "sights", service.getAdminDetail(adminId, "sights").version)
                    service.getIndex("en").collections.size shouldBe 3
                    service.getDetail("stations", "en").items.size shouldBe 10
                    service.unpublish(adminId, "dev", "sights", service.getAdminDetail(adminId, "sights").version)
                    service.getIndex("en").state shouldBe "UNPUBLISHED"
                    service.getDetail("stations", "en").items.size shouldBe 10
                    shouldThrow<BusinessException> { service.getDetail("sights", "en") }.errorCode.code shouldBe "CURATION-001"
                }
            }
        }
        given("세 번째 모음 부산 명소(R-97)") {
            `when`("세 모음을 게시하면") {
                then("홈 카드는 sights·stations·busan 순서이고 옛 restaurants 키는 404 다") {
                    shouldThrow<BusinessException> { service.getDetail("busan", "en") }.errorCode.code shouldBe "CURATION-001"
                    CurationService.keys.forEach(::publish)
                    service.getIndex("en").collections.map { it.collectionKey } shouldBe listOf("sights", "stations", "busan")
                    service.getDetail("busan", "en").items.size shouldBe 10
                    shouldThrow<BusinessException> { service.getDetail("restaurants", "en") }.errorCode.code shouldBe "CURATION-001"
                    mvc.get("/api/curations/seoul/restaurants?lang=en") { header("X-API-Version", "1.0") }.andExpect { status { isNotFound() } }
                }
            }
            `when`("DB 에 옛 restaurants 키를 쓰려 하면") {
                then("키 CHECK 제약이 거절한다") {
                    shouldThrow<DataAccessException> { jdbc.update("UPDATE curation_collection SET collection_key = 'restaurants' WHERE collection_key = 'busan'") }
                }
            }
        }
        given("이전 읽기 스냅샷을 가진 동시 최초 게시") {
            `when`("세 관리 트랜잭션이 같은 미게시 상태에서 출발하면") {
                then("마지막 현재읽기가 세 게시본을 보고 최초 활성화한다") {
                    val versions = CurationService.keys.associateWith { key -> service.updateDraft(adminId, "dev", key, 0, documents.getValue(key)).version }
                    val barrier = CountDownLatch(3)
                    val pool = Executors.newFixedThreadPool(3)
                    try {
                        val tasks = versions.map { (key, version) -> pool.submit(Callable {
                            TransactionTemplate(transactionManager).execute {
                                admins.existsById(adminId) shouldBe true
                                barrier.countDown()
                                check(barrier.await(10, TimeUnit.SECONDS))
                                service.publish(adminId, "dev", key, version)
                            }
                        }) }
                        tasks.forEach { it.get() }
                        service.getIndex("en").collections.size shouldBe 3
                        service.getIndex("en").state shouldBe "PUBLISHED"
                    } finally { pool.shutdownNow() }
                }
            }
        }
        given("게시본과 초안의 격리") {
            `when`("게시 뒤 9개 초안을 저장하고 게시를 시도하면") {
                then("실패가 공개 스냅샷과 버전을 바꾸지 않는다") {
                    CurationService.keys.forEach(::publish)
                    CurationValidation.languages.forEach { lang ->
                        val detail = service.getDetail("sights", lang)
                        detail.resolvedLang shouldBe lang
                        detail.items.map { it.position } shouldBe (1..10).toList()
                        detail.items.all { it.name.startsWith(lang) } shouldBe true
                        val json = mvc.get("/api/curations/seoul/sights?lang=$lang") { header("X-API-Version", "1.0") }
                            .andExpect { status { isOk() } }.andReturn().response.contentAsString
                        listOf("rights", "staging", "evidence", "adminId", "draftDocument").forEach { json.contains(it) shouldBe false }
                    }
                    val response = mvc.get("/api/curations/seoul/sights?lang=en") { header("X-API-Version", "1.0") }
                        .andExpect { status { isOk() }; jsonPath("$.payload.publishedAt") { exists() }; jsonPath("$.payload.publishedBy") { doesNotExist() } }.andReturn()
                    val publishedAt = jacksonObjectMapper().readTree(response.response.contentAsString).path("payload").path("publishedAt").asText()
                    LocalDateTime.parse(publishedAt) shouldBe service.getAdminDetail(adminId, "sights").publishedAt
                    val before = service.getDetail("sights", "en")
                    val d = service.getAdminDetail(adminId, "sights")
                    val saved = service.updateDraft(adminId, "dev", "sights", d.version, documents.getValue("sights").let { it.copy(items = it.items.take(9)) })
                    val invalid = service.validate(adminId, "dev", "sights", saved.version)
                    invalid.valid shouldBe false
                    shouldThrow<BusinessException> { service.publish(adminId, "dev", "sights", saved.version) }.errorCode.code shouldBe "CURATION-002"
                    service.getAdminDetail(adminId, "sights").version shouldBe saved.version
                    service.getDetail("sights", "en") shouldBe before
                }
            }
            `when`("같은 기대버전으로 두 저장이 경쟁하면") {
                then("하나만 성공하고 나머지는 현재버전 충돌이다") {
                    val version = service.getAdminDetail(adminId, "sights").version
                    val gate = CountDownLatch(1)
                    val pool = Executors.newFixedThreadPool(2)
                    try {
                        val tasks = (1..2).map { pool.submit(Callable {
                            gate.await()
                            try { service.updateDraft(adminId, "dev", "sights", version, documents.getValue("sights")); "OK" }
                            catch (e: BusinessException) { e.errorCode.code }
                        }) }
                        gate.countDown()
                        tasks.map { it.get() }.sorted() shouldBe listOf("CURATION-003", "OK")
                    } finally { pool.shutdownNow() }
                }
            }
            `when`("같은 버전으로 게시와 내림이 경쟁하면") {
                then("최종 상태는 한 요청만 반영하고 부분 공개가 없다") {
                    CurationService.keys.forEach(::publish)
                    val version = service.getAdminDetail(adminId, "sights").version
                    val start = CountDownLatch(1)
                    val pool = Executors.newFixedThreadPool(2)
                    try {
                        val tasks = listOf(true, false).map { publish -> pool.submit(Callable {
                            start.await()
                            try {
                                if (publish) service.publish(adminId, "dev", "sights", version)
                                else service.unpublish(adminId, "dev", "sights", version)
                                "OK"
                            } catch (e: BusinessException) { e.errorCode.code }
                        }) }
                        start.countDown()
                        tasks.map { it.get() }.sorted() shouldBe listOf("CURATION-003", "OK")
                        val index = service.getIndex("en")
                        index.collections.size shouldBe if (index.state == "PUBLISHED") 3 else 0
                        service.getDetail("stations", "en").items.size shouldBe 10
                    } finally { pool.shutdownNow() }
                }
            }
            `when`("동일 초안을 다시 저장하면") {
                then("관리 version은 다시 증가한다") {
                    val first = service.updateDraft(adminId, "dev", "sights", 0, documents.getValue("sights"))
                    val second = service.updateDraft(adminId, "dev", "sights", first.version, first.draftDocument)
                    second.version shouldBe first.version + 1
                }
            }
        }
        given("게시 번역의 런타임 결손") {
            `when`("한 카드의 일본어가 손상되면") {
                then("세 카드 모두 영어로 내려가며 영어도 없으면503이다") {
                    CurationService.keys.forEach(::publish)
                    val doc = documents.getValue("sights")
                    val mapper = jacksonObjectMapper()
                    jdbc.update("UPDATE curation_collection SET published_document=? WHERE collection_key='sights'", mapper.writeValueAsString(doc.copy(title = doc.title - "ja")))
                    val fallback = service.getIndex("ja")
                    fallback.resolvedLang shouldBe "en"
                    fallback.fallbackUsed shouldBe true
                    fallback.collections.all { it.title.startsWith("en") } shouldBe true
                    jdbc.update("UPDATE curation_collection SET published_document=? WHERE collection_key='sights'", mapper.writeValueAsString(doc.copy(title = doc.title - "ja" - "en")))
                    shouldThrow<BusinessException> { service.getIndex("ja") }.errorCode.code shouldBe "CURATION-006"
                }
            }
        }
        given("항목 사진 여러 장(갤러리)") {
            fun readyAsset(key: String, name: String) = assets.save(CurationAsset(collectionId = collections.findByCollectionKey(key)!!.id, issuedByAdminId = adminId, assetState = "READY", stagingKey = "curation/dev/staging/$name", finalKey = "curation/dev/final/$name", contentType = "image/png", sizeBytes = 10, expiresAt = LocalDateTime.now().plusMinutes(5), completeDeadline = LocalDateTime.now().plusDays(1), completedAt = LocalDateTime.now(), sourceFingerprint = "fixture"))
            fun photoOf(base: CurationPhoto, id: Long, attribution: String?) = base.copy(assetId = id, rights = base.rights.copy(checkedAssetId = id, attributionRequirement = if (attribution == null) AttributionRequirement.NONE else AttributionRequirement.TEXT, attributionText = attribution))
            `when`("사진 목록이 비어 있으면(기존 게시본)") {
                then("images 는 image 1장이고 구 필드 imageUrl·imageAttribution 은 그대로다") {
                    CurationService.keys.forEach(::publish)
                    val item = service.getDetail("sights", "en").items.first()
                    item.images shouldBe listOf(CurationPublicImage(item.imageUrl, item.imageAttribution))
                    mvc.get("/api/curations/seoul/sights?lang=en") { header("X-API-Version", "1.0") }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.items[0].images.length()") { value(1) }
                        jsonPath("$.payload.items[0].images[0].url") { value(item.imageUrl) }
                        jsonPath("$.payload.items[0].imageUrl") { value(item.imageUrl) }
                    }
                }
            }
            `when`("첫 항목에 사진 3장을 사진별 출처와 함께 게시하면") {
                then("공개 images 가 3장이고 첫 장 = image, 사진마다 출처 문구가 붙는다") {
                    val doc = documents.getValue("sights")
                    val first = doc.items[0]
                    val second = readyAsset("sights", "gallery-2")
                    val third = readyAsset("sights", "gallery-3")
                    val gallery = listOf(first.image!!, photoOf(first.image!!, second.id, "Photo: KTO"), photoOf(first.image!!, third.id, null))
                    documents["sights"] = CurationFixtures.reviewed(doc.copy(items = listOf(first.copy(images = gallery)) + doc.items.drop(1)))
                    CurationService.keys.forEach(::publish)

                    val item = service.getDetail("sights", "en").items.first()
                    item.images.size shouldBe 3
                    item.images[0].url shouldBe item.imageUrl
                    item.images[1].url.endsWith("/curation/dev/final/gallery-2") shouldBe true
                    item.images[2].url.endsWith("/curation/dev/final/gallery-3") shouldBe true
                    item.images.map { it.attribution } shouldBe listOf(null, "Photo: KTO", null)
                    service.getDetail("sights", "en").items.drop(1).all { it.images.size == 1 } shouldBe true
                }
            }
            `when`("갤러리 사진이 다른 모음의 자산이면") {
                then("게시를 그 사진 경로의 ASSET_TARGET_MISMATCH 로 막는다") {
                    val doc = documents.getValue("sights")
                    val first = doc.items[0]
                    val foreign = readyAsset("busan", "foreign")
                    documents["sights"] = CurationFixtures.reviewed(doc.copy(items = listOf(first.copy(images = listOf(first.image!!, photoOf(first.image!!, foreign.id, null)))) + doc.items.drop(1)))
                    val error = shouldThrow<BusinessException> { publish("sights") }
                    @Suppress("UNCHECKED_CAST")
                    ((error.payload as Map<String, Any>)["errors"] as List<CurationIssue>).map { it.path to it.reason } shouldContain ("items[0].images[1]" to "ASSET_TARGET_MISMATCH")
                }
            }
        }

        given("공개 읽기 검사 범위 — 공부 세션 지적") {
            `when`("게시 뒤 저장본이 지금 규칙과 달라져도(편집 해시 불일치·모델에 없는 필드 추가 — 규칙·모델 변경 시뮬)") {
                then("화면에 필요한 것(10항목·언어·READY 사진)만 보므로 공개 GET 은 200 이다") {
                    CurationService.keys.forEach(::publish)
                    val doc = documents.getValue("sights")
                    val mapper = jacksonObjectMapper()
                    val changed = mapper.valueToTree<com.fasterxml.jackson.databind.node.ObjectNode>(doc.copy(title = doc.title + ("en" to "en 바뀐 제목")))
                    changed.put("futureField", "모델 확장 시뮬")
                    jdbc.update("UPDATE curation_collection SET published_document=? WHERE collection_key='sights'", mapper.writeValueAsString(changed))

                    service.getIndex("en").collections.first { it.collectionKey == "sights" }.title shouldBe "en 바뀐 제목"
                    service.getDetail("sights", "en").items.size shouldBe 10
                    mvc.get("/api/curations/seoul/sights?lang=en") { header("X-API-Version", "1.0") }.andExpect { status { isOk() } }
                }
            }

            `when`("사진 표시 의무가 TEXT 로 게시되면") {
                then("공개 카드·상세에 출처 문구를 내리고 NONE 사진은 null 이다") {
                    val doc = documents.getValue("busan")
                    val credited = doc.cover!!.copy(rights = doc.cover!!.rights.copy(attributionRequirement = AttributionRequirement.TEXT, attributionText = "Photo: KTO / KOGL Type 1"))
                    documents["busan"] = CurationFixtures.reviewed(doc.copy(cover = credited, items = listOf(doc.items.first().copy(image = credited)) + doc.items.drop(1)))
                    val sights = documents.getValue("sights")
                    documents["sights"] = CurationFixtures.reviewed(sights.copy(cover = sights.cover!!.copy(rights = sights.cover!!.rights.copy(attributionText = "leftover"))))
                    CurationService.keys.forEach(::publish)

                    val cards = service.getIndex("en").collections.associateBy { it.collectionKey }
                    cards.getValue("busan").coverImageAttribution shouldBe "Photo: KTO / KOGL Type 1"
                    cards.getValue("sights").coverImageAttribution shouldBe null
                    val detail = service.getDetail("busan", "en")
                    detail.coverImageAttribution shouldBe "Photo: KTO / KOGL Type 1"
                    detail.items.map { it.imageAttribution } shouldBe listOf("Photo: KTO / KOGL Type 1") + List(9) { null }
                    mvc.get("/api/curations/seoul/busan?lang=en") { header("X-API-Version", "1.0") }.andExpect {
                        status { isOk() }
                        jsonPath("$.payload.coverImageAttribution") { value("Photo: KTO / KOGL Type 1") }
                        jsonPath("$.payload.items[0].imageAttribution") { value("Photo: KTO / KOGL Type 1") }
                        jsonPath("$.payload.items[1].imageAttribution") { value(null) }
                    }
                }
            }

            `when`("게시본의 표지나 항목 사진이 비어 있으면(저장본 손상) — Codex P2") {
                then("500 이 아니라 503(CURATION-006) 으로 막는다") {
                    CurationService.keys.forEach(::publish)
                    val doc = documents.getValue("sights")
                    val mapper = jacksonObjectMapper()
                    jdbc.update("UPDATE curation_collection SET published_document=? WHERE collection_key='sights'", mapper.writeValueAsString(doc.copy(cover = null)))

                    shouldThrow<BusinessException> { service.getIndex("en") }.errorCode.code shouldBe "CURATION-006"
                    mvc.get("/api/curations/seoul/sights?lang=en") { header("X-API-Version", "1.0") }.andExpect { status { isServiceUnavailable() } }

                    val items = doc.items.mapIndexed { i, item -> if (i == 3) item.copy(image = null) else item }
                    jdbc.update("UPDATE curation_collection SET published_document=? WHERE collection_key='sights'", mapper.writeValueAsString(doc.copy(items = items)))
                    shouldThrow<BusinessException> { service.getDetail("sights", "en") }.errorCode.code shouldBe "CURATION-006"
                }
            }

            `when`("편집 해시가 내용과 맞지 않는 초안을 게시하려 하면") {
                then("게시 시점 검증이 CURATION-002(EDITORIAL_REVIEW_MISSING)로 막는다") {
                    val d = service.getAdminDetail(adminId, "sights")
                    val doc = documents.getValue("sights")
                    val stale = doc.copy(title = doc.title + ("en" to "en 검토 뒤 바뀐 제목"))
                    val saved = service.updateDraft(adminId, "dev", "sights", d.version, stale)

                    val error = shouldThrow<BusinessException> { service.publish(adminId, "dev", "sights", saved.version) }

                    error.errorCode.code shouldBe "CURATION-002"
                    error.payload.toString().contains("EDITORIAL_REVIEW_MISSING") shouldBe true
                }
            }
        }
    }
}
