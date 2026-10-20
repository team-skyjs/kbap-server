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
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
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
                    publish("restaurants")
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
