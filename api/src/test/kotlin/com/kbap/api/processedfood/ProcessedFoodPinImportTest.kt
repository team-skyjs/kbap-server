package com.kbap.api.processedfood

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.image.FakeStorageObjectStore
import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
class ProcessedFoodPinImportTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var storage: FakeStorageObjectStore
    @Autowired private lateinit var importer: ProcessedFoodPinImporter

    private val mapper = jacksonObjectMapper()
    private val root = "local/admin-imports/translation-overrides"

    init {
        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
        fun long(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } } }
        fun seed(reportNo: String, name: String): Long {
            execute(
                "INSERT INTO processed_food_product (report_no, product_name, normalized_name, food_type, manufacturer, raw_materials, allergens, source, content_hash, collected_at, last_seen_at, status, created_at, updated_at) " +
                    "VALUES ('$reportNo', '$name', '${ProcessedFoodSnapshot.normalizeName(name)}', '과자', '농심', '소맥분(밀)', '밀 함유', 'HACCP', REPEAT('a', 64), NOW(6), NOW(6), 'ACTIVE', NOW(6), NOW(6))",
            )
            return long("SELECT id FROM processed_food_product WHERE report_no = '$reportNo'")
        }
        fun drop(name: String, body: Any) = storage.put("$root/pending/$name", (body as? String ?: mapper.writeValueAsString(body)).toByteArray(), "application/json")
        fun pins() = long("SELECT COUNT(*) FROM content_translation WHERE target_type IN ('PF_NAME_PINNED', 'PF_MAKER_PINNED')")
        fun result(path: String) = mapper.readTree(storage.get(path))

        beforeEach {
            TestTables.clearAll(dataSource)
            execute("DELETE FROM processed_food_product")
            storage.heads.keys.filter { it.startsWith(root) }.forEach { storage.delete(it) }
        }

        given("S3 파일 드롭으로 번역 고정(S-943)") {
            `when`("*.dryrun.json 을 올리면") {
                then("저장하지 않고 결과 파일만 쓰며 원본은 done 으로 옮긴다") {
                    val id = seed("IMP-1", "새우깡")
                    drop("batch-1.dryrun.json", mapOf("kind" to "NAME", "items" to listOf(mapOf("productId" to id, "language" to "en", "name" to "Saeukkang"))))
                    importer.runOnce()

                    pins() shouldBe 0
                    result("$root/done/batch-1.dryrun.json.result.json").path("applied").asInt() shouldBe 1
                    (storage.get("$root/done/batch-1.dryrun.json") != null) shouldBe true
                    storage.head("$root/pending/batch-1.dryrun.json") shouldBe null
                }
            }
            `when`("정상 파일을 올리면") {
                then("500건씩 나눠 저장하고, 결과(적용·무변·거절·사유)를 done 에 쓰며, 같은 파일을 다시 올려도 무변이다") {
                    val ids = (1..3).map { seed("IMP-2$it", "과자$it") }
                    val items = (0 until 1200).map { i -> mapOf("productId" to ids[i % 3], "language" to listOf("en", "ja", "vi", "id")[i % 4], "name" to "Snack ${i % 12}") } +
                        mapOf("productId" to 99_999_999L, "language" to "en", "name" to "Ghost")
                    drop("batch-2.json", mapOf("kind" to "NAME", "items" to items))
                    importer.runOnce()

                    pins() shouldBe 12
                    val done = result("$root/done/batch-2.json.result.json")
                    done.path("rejected").asInt() shouldBe 1
                    done.path("rejections")[0].path("index").asInt() shouldBe 1200
                    done.path("rejections")[0].path("reason").asText() shouldBe "PRODUCT_NOT_FOUND"
                    (storage.get("$root/done/batch-2.json") != null) shouldBe true
                    storage.head("$root/pending/batch-2.json") shouldBe null

                    drop("batch-2.json", mapOf("kind" to "NAME", "items" to items))
                    importer.runOnce()
                    result("$root/done/batch-2.json.result.json").path("applied").asInt() shouldBe 0
                    pins() shouldBe 12
                }
            }
            `when`("앞 파일의 보관(이동·삭제)이 실패하면 — Codex P2(#532)") {
                then("그 실행의 나머지 파일을 멈춰, 다음 실행에서 앞 파일부터 다시 처리해도 뒤 파일의 새 값이 남는다") {
                    val id = seed("IMP-4", "포카칩")
                    drop("a.json", mapOf("kind" to "NAME", "items" to listOf(mapOf("productId" to id, "language" to "en", "name" to "Old Name"))))
                    drop("b.json", mapOf("kind" to "NAME", "items" to listOf(mapOf("productId" to id, "language" to "en", "name" to "Pocachip"))))
                    storage.failDeletePaths += "$root/pending/a.json"
                    try {
                        importer.runOnce()
                        (storage.get("$root/pending/b.json") != null) shouldBe true
                    } finally {
                        storage.failDeletePaths.clear()
                    }
                    importer.runOnce()

                    dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery("SELECT translated_text FROM content_translation WHERE target_type = 'PF_NAME_PINNED' AND target_id = $id AND language = 'en'").use { r -> r.next(); r.getString(1) } } } shouldBe "Pocachip"
                    storage.head("$root/pending/a.json") shouldBe null
                    storage.head("$root/pending/b.json") shouldBe null
                }
            }
            `when`("pending 에 .json 아닌 객체가 한 페이지 넘게 있으면 — Codex P2(#532 2R)") {
                then("건너뛰며 다음 페이지로 넘겨 뒤의 .json 파일도 처리한다") {
                    val id = seed("IMP-5", "칸쵸")
                    (0 until 120).forEach { storage.put("$root/pending/a-%03d.txt".format(it), "x".toByteArray(), "text/plain") }
                    drop("z.json", mapOf("kind" to "NAME", "items" to listOf(mapOf("productId" to id, "language" to "en", "name" to "Kancho"))))
                    importer.runOnce()

                    pins() shouldBe 1
                    storage.head("$root/pending/z.json") shouldBe null
                }
            }
            `when`("JSON 이 깨졌거나 5,000건을 넘으면") {
                then("failed 로 옮기고 사유를 쓰며 저장하지 않는다") {
                    val id = seed("IMP-3", "꼬북칩")
                    drop("broken.json", "{\"kind\":\"NAME\",\"items\":[")
                    drop("huge.json", mapOf("kind" to "NAME", "items" to List(5001) { mapOf("productId" to id, "language" to "en", "name" to "Turtle Chips") }))
                    importer.runOnce()

                    pins() shouldBe 0
                    result("$root/failed/broken.json.result.json").path("error").asText().isNotBlank() shouldBe true
                    result("$root/failed/huge.json.result.json").path("error").asText() shouldBe "TOO_MANY_ITEMS"
                    storage.head("$root/pending/broken.json") shouldBe null
                    (storage.get("$root/failed/broken.json") != null) shouldBe true
                }
            }
        }
    }
}
