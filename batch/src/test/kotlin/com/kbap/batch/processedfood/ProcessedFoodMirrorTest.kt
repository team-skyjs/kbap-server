package com.kbap.batch.processedfood

import com.kbap.batch.BatchIntegrationTest
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.port.publicdata.FoodMakerLookup
import com.kbap.common.port.publicdata.HaccpProduct
import com.kbap.common.port.publicdata.HaccpProductPage
import com.kbap.common.port.publicdata.HaccpProductSource
import com.kbap.common.port.publicdata.PublicDataCallCounter
import com.kbap.common.port.publicdata.PublicDataCallException
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.sql.DataSource

@BatchIntegrationTest
class ProcessedFoodMirrorTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var repository: ProcessedFoodProductJpaRepository
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var dataSource: DataSource

    class FakeCounter : PublicDataCallCounter {
        var attempts = 0L
        override fun httpAttempts(): Long = attempts
    }

    class FakeSource(var catalog: Map<String, List<HaccpProduct>>, private val counter: FakeCounter? = null) : HaccpProductSource {
        val calls = mutableListOf<Pair<String?, Int>>()
        val failTypes = mutableSetOf<String?>()
        val failPages = mutableSetOf<Pair<String?, Int>>()

        override fun getPage(foodType: String?, pageNo: Int, pageSize: Int): HaccpProductPage {
            calls += foodType to pageNo
            counter?.let { it.attempts += if (foodType in failTypes) 4 else 1 }
            if (foodType in failTypes || (foodType to pageNo) in failPages) throw PublicDataCallException("HACCP 제품정보 호출 실패(재시도 3회): HTTP 504")
            val all = if (foodType == null) catalog.values.flatten().distinctBy { it.reportNo } else catalog[foodType].orEmpty()
            return HaccpProductPage(all.drop((pageNo - 1) * pageSize).take(pageSize), all.size)
        }
    }

    class FakeLookup(private val makers: Map<String, String?>, private val counter: FakeCounter? = null) : FoodMakerLookup {
        val calls = mutableListOf<String>()
        val failing = mutableSetOf<String>()

        override fun getMakerOrNull(reportNo: String): String? {
            calls += reportNo
            counter?.let { it.attempts += if (reportNo in failing) 4 else 1 }
            if (reportNo in failing) throw PublicDataCallException("식품영양성분DB 호출 실패(재시도 3회): HTTP 504")
            return makers[reportNo]
        }
    }

    init {
        val clock = Clock.fixed(Instant.parse("2026-10-20T19:00:00Z"), ZoneId.of("Asia/Seoul"))

        fun product(no: String, name: String, type: String, maker: String = "㈜농심_서울", raw: String = "밀가루") = HaccpProduct(
            no, name, "식품", type, "유처리제품", maker, maker, null, "120g", raw, "밀", "총 내용량", "http://img/$no.jpg", "http://img/$no-m.jpg",
        )

        fun mirror(source: HaccpProductSource, lookup: FoodMakerLookup, types: List<String>, pageSize: Int = 2, makerLimit: Int = 100, counter: PublicDataCallCounter = FakeCounter()) =
            ProcessedFoodMirror(source, lookup, repository, TransactionTemplate(transactionManager), clock, types, pageSize, makerLimit, counter = counter)

        fun count(sql: String): Long = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { r -> r.next(); r.getLong(1) } } }

        beforeEach { dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM processed_food_product") } } }
        afterSpec { dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM processed_food_product") } } }

        val ramen = listOf(
            product("2001054954178", "얼큰한너구리", "유탕면류", maker = "알수없음"),
            product("2001054954113", "신라면컵", "유탕면류", maker = "알수없음"),
            product("2012051205516", "팔도틈새라면 빨계떡", "유탕면류", maker = "㈜팔도"),
        )
        val snacks = listOf(
            product("2011022182815", "포카칩 오리지널", "과자", maker = "오리온_청주"),
            product("2001054954113", "신라면컵", "유탕면류", maker = "알수없음"),
        )

        given("가공식품 제품 마스터 미러링 — 유형 필터 없이 출처 전체(KB-844)") {
            `when`("유형 목록이 비어 있으면") {
                then("유형 없이 한 번 전체 페이지를 끝까지 돌아 모든 유형을 저장한다") {
                    val dairy = listOf(product("19790188001100", "빙그레 바나나맛우유", "가공유", maker = "빙그레"))
                    val source = FakeSource(mapOf("유탕면" to ramen, "과자" to snacks, "가공유" to dairy))

                    val summary = mirror(source, FakeLookup(emptyMap()), emptyList()).mirror()

                    source.calls shouldBe listOf<Pair<String?, Int>>(null to 1, null to 2, null to 3)
                    summary.perType shouldBe mapOf("전체" to 5)
                    summary.inserted shouldBe 5
                    count("SELECT COUNT(*) FROM processed_food_product WHERE food_type = '가공유' AND product_name = '빙그레 바나나맛우유'") shouldBe 1
                }
            }
            `when`("중간 페이지 하나가 재시도까지 실패하면 — 공부 이관") {
                then("그 페이지만 건너뛰고 나머지 페이지를 적재하며, 실패 페이지를 남기고 부분 성공(PARTIAL)이다") {
                    val dairy = listOf(product("19790188001100", "빙그레 바나나맛우유", "가공유", maker = "빙그레"))
                    val source = FakeSource(mapOf("유탕면" to ramen, "과자" to snacks, "가공유" to dairy)).apply { failPages += null to 2 }

                    val summary = mirror(source, FakeLookup(emptyMap()), emptyList()).mirror()

                    source.calls shouldBe listOf<Pair<String?, Int>>(null to 1, null to 2, null to 3)
                    summary.failedPages shouldBe listOf("전체#2")
                    summary.inserted shouldBe 3
                    summary.isPartial() shouldBe true
                }
            }
            `when`("페이지가 연달아 3번 실패하면") {
                then("출처 장애로 보고 그 유형 순회를 멈춘다(실패 유형)") {
                    val many = (1..12).map { product("30000000000%03d".format(it), "제품$it", "과자") }
                    val source = FakeSource(mapOf("과자" to many)).apply { (2..6).forEach { failPages += null to it } }

                    val summary = mirror(source, FakeLookup(emptyMap()), emptyList()).mirror()

                    source.calls shouldBe listOf<Pair<String?, Int>>(null to 1, null to 2, null to 3, null to 4)
                    summary.failedTypes shouldBe listOf("전체")
                    summary.isPartial() shouldBe true
                }
            }
            `when`("유형별로 이미 적재된 행을 전체 순회가 다시 보면") {
                then("기존 행은 가산 없이 그대로(내용 해시 불변)이고 새 행만 더한다") {
                    mirror(FakeSource(mapOf("유탕면" to ramen)), FakeLookup(emptyMap()), listOf("유탕면")).mirror()
                    val before = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT report_no, HEX(content_hash) FROM processed_food_product ORDER BY report_no").use { r -> generateSequence { if (r.next()) r.getString(1) to r.getString(2) else null }.toList() } } }

                    val summary = mirror(FakeSource(mapOf("유탕면" to ramen, "과자" to snacks)), FakeLookup(emptyMap()), emptyList()).mirror()

                    summary.unchanged shouldBe ramen.size
                    summary.changed shouldBe 0
                    summary.inserted shouldBe 1
                    val after = dataSource.connection.use { c -> c.createStatement().use { s -> s.executeQuery("SELECT report_no, HEX(content_hash) FROM processed_food_product ORDER BY report_no").use { r -> generateSequence { if (r.next()) r.getString(1) to r.getString(2) else null }.toList() } } }
                    after.filter { (no, _) -> no in before.map { it.first } } shouldBe before
                }
            }
        }

        given("가공식품 제품 마스터 미러링") {
            `when`("두 유형을 페이지 단위(2건)로 처음 돌리면") {
                then("유형마다 마지막 페이지까지 읽고, 유형 간 겹친 행은 한 번만 저장하며, 정규화 이름을 함께 남긴다") {
                    val source = FakeSource(mapOf("유탕면" to ramen, "과자" to snacks))

                    val summary = mirror(source, FakeLookup(emptyMap()), listOf("유탕면", "과자")).mirror()

                    source.calls shouldBe listOf("유탕면" to 1, "유탕면" to 2, "과자" to 1)
                    summary.perType shouldBe mapOf("유탕면" to 3, "과자" to 2)
                    summary.inserted shouldBe 4
                    summary.crossTypeDuplicates shouldBe 1
                    summary.distinctProducts shouldBe 4
                    count("SELECT COUNT(*) FROM processed_food_product") shouldBe 4
                    count("SELECT COUNT(*) FROM processed_food_product WHERE report_no = '2011022182815' AND normalized_name = '포카칩' AND source = 'HACCP'") shouldBe 1
                }
            }

            `when`("같은 원본으로 두 번 더 돌리면(증분)") {
                then("행이 늘지 않고 전부 변경 없음, 원재료가 바뀐 행만 갱신된다") {
                    val source = FakeSource(mapOf("유탕면" to ramen))
                    val job = mirror(source, FakeLookup(emptyMap()), listOf("유탕면"))
                    job.mirror()

                    val second = job.mirror()
                    source.catalog = mapOf("유탕면" to ramen.map { if (it.reportNo == "2012051205516") it.copy(rawMaterials = "밀가루, 팜유") else it })
                    val third = job.mirror()

                    second.inserted shouldBe 0
                    second.unchanged shouldBe 3
                    third.inserted shouldBe 0
                    third.changed shouldBe 1
                    third.unchanged shouldBe 2
                    count("SELECT COUNT(*) FROM processed_food_product") shouldBe 3
                    count("SELECT COUNT(*) FROM processed_food_product WHERE raw_materials = '밀가루, 팜유'") shouldBe 1
                }
            }

            `when`("누끼 이미지가 붙은 행의 원본 이미지·원재료가 바뀌어 갱신되면 — KB-911") {
                then("image_url 은 새 원본으로 덮이고 cutout_image_url 은 그대로다") {
                    val source = FakeSource(mapOf("유탕면" to ramen))
                    val job = mirror(source, FakeLookup(emptyMap()), listOf("유탕면"))
                    job.mirror()
                    dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE processed_food_product SET cutout_image_url = 'https://cdn.test/cut.png?v=1' WHERE report_no = '2012051205516'") } }
                    source.catalog = mapOf("유탕면" to ramen.map { if (it.reportNo == "2012051205516") it.copy(rawMaterials = "밀가루, 팜유", imageUrl = "http://img/new.jpg") else it })

                    job.mirror().changed shouldBe 1

                    count("SELECT COUNT(*) FROM processed_food_product WHERE report_no = '2012051205516' AND image_url = 'http://img/new.jpg' AND cutout_image_url = 'https://cdn.test/cut.png?v=1'") shouldBe 1
                }
            }

            `when`("미러가 행을 읽어 둔 사이 어드민이 누끼를 저장하고 미러가 그 행을 고쳐 쓰면 — KB-911") {
                then("미러 쓰기는 바뀐 컬럼만 써서 방금 저장된 cutout_image_url 을 지우지 않는다") {
                    mirror(FakeSource(mapOf("유탕면" to ramen)), FakeLookup(emptyMap()), listOf("유탕면")).mirror()

                    TransactionTemplate(transactionManager).executeWithoutResult {
                        val row = repository.findAll().single { it.reportNo == "2001054954178" }
                        dataSource.connection.use { c -> c.createStatement().use { it.execute("UPDATE processed_food_product SET cutout_image_url = 'https://cdn.test/race.png?v=1' WHERE report_no = '2001054954178'") } }
                        row.supplementMaker("농심", java.time.LocalDateTime.now(clock))
                    }

                    count("SELECT COUNT(*) FROM processed_food_product WHERE report_no = '2001054954178' AND cutout_image_url = 'https://cdn.test/race.png?v=1'") shouldBe 1
                }
            }

            `when`("제조사가 '알수없음' 인 행이 있으면") {
                then("그 행만 영양DB 로 한 번씩 보완하고, 다음 실행에선 다시 조회하지 않는다(값이 없던 행 포함)") {
                    val lookup = FakeLookup(mapOf("2001054954178" to "㈜농심", "2001054954113" to null))
                    val job = mirror(FakeSource(mapOf("유탕면" to ramen)), lookup, listOf("유탕면"))

                    val first = job.mirror()
                    val second = job.mirror()

                    lookup.calls.sorted() shouldBe listOf("2001054954113", "2001054954178")
                    first.makerLookups shouldBe 2
                    first.makerFilled shouldBe 1
                    second.makerLookups shouldBe 0
                    count("SELECT COUNT(*) FROM processed_food_product WHERE report_no = '2001054954178' AND maker_supplement = '㈜농심' AND maker_checked_at IS NOT NULL") shouldBe 1
                    count("SELECT COUNT(*) FROM processed_food_product WHERE report_no = '2012051205516' AND maker_checked_at IS NULL") shouldBe 1
                }
            }

            `when`("제조사 보완 조회가 실패하면") {
                then("그 행은 확인 표시를 남기지 않아 다음 실행에서 다시 조회한다") {
                    val lookup = FakeLookup(mapOf("2001054954178" to "㈜농심", "2001054954113" to "㈜농심"))
                    lookup.failing += "2001054954113"
                    val job = mirror(FakeSource(mapOf("유탕면" to ramen)), lookup, listOf("유탕면"))

                    val first = job.mirror()
                    lookup.failing.clear()
                    val second = job.mirror()

                    first.makerLookupFailures shouldBe 1
                    second.makerLookups shouldBe 1
                    count("SELECT COUNT(*) FROM processed_food_product WHERE maker_supplement = '㈜농심'") shouldBe 2
                }
            }

            `when`("영양DB 가 전면 장애라 보완 조회가 연달아 실패하면 — Codex P1") {
                then("연속 실패 상한(5)에서 보완 단계를 멈추고, 남은 행은 확인 표시 없이 다음 회로 넘긴다") {
                    val many = (1..12).map { product("9000000000%03d".format(it), "봇면$it", "유탕면류", maker = "알수없음") }
                    val lookup = FakeLookup(emptyMap())
                    lookup.failing += many.map { it.reportNo }

                    val summary = mirror(FakeSource(mapOf("유탕면" to many)), lookup, listOf("유탕면"), pageSize = 100).mirror()

                    lookup.calls.size shouldBe 5
                    summary.makerLookupFailures shouldBe 5
                    summary.makerLookupAborted shouldBe true
                    count("SELECT COUNT(*) FROM processed_food_product WHERE maker_checked_at IS NOT NULL") shouldBe 0
                }
            }

            `when`("전면 장애로 보완이 멈추거나 유형 하나가 실패하면 — Codex P2") {
                then("요약은 부분 완료(isPartial)이고, apiCalls 는 재시도·실패를 포함한 HTTP 시도 수다") {
                    val counter = FakeCounter()
                    val many = (1..6).map { product("9200000000%03d".format(it), "봇면$it", "유탕면류", maker = "알수없음") }
                    val lookup = FakeLookup(emptyMap(), counter)
                    lookup.failing += many.map { it.reportNo }
                    val source = FakeSource(mapOf("유탕면" to many, "과자" to snacks), counter)
                    source.failTypes += "과자"

                    val summary = mirror(source, lookup, listOf("유탕면", "과자"), pageSize = 100, counter = counter).mirror()

                    summary.makerLookupAborted shouldBe true
                    summary.isPartial() shouldBe true
                    summary.apiCalls shouldBe 1 + 4 + 5 * 4
                    summary.apiCalls.toLong() shouldBe counter.attempts
                }
            }

            `when`("유형은 모두 성공했고 보완만 전면 장애로 멈추면") {
                then("그것만으로도 부분 완료다") {
                    val many = (1..6).map { product("9300000000%03d".format(it), "봇면$it", "유탕면류", maker = "알수없음") }
                    val lookup = FakeLookup(emptyMap())
                    lookup.failing += many.map { it.reportNo }

                    val summary = mirror(FakeSource(mapOf("유탕면" to many)), lookup, listOf("유탕면"), pageSize = 100).mirror()

                    summary.failedTypes shouldBe emptyList()
                    summary.makerLookupAborted shouldBe true
                    summary.isPartial() shouldBe true
                }
            }

            `when`("보완 대상이 정확히 상한(5)개이고 모두 실패하면 — Codex P2") {
                then("마지막 조회에서 상한에 닿아도 중단(부분 완료)으로 본다") {
                    val five = (1..5).map { product("9400000000%03d".format(it), "봇면$it", "유탕면류", maker = "알수없음") }
                    val lookup = FakeLookup(emptyMap())
                    lookup.failing += five.map { it.reportNo }

                    val summary = mirror(FakeSource(mapOf("유탕면" to five)), lookup, listOf("유탕면"), pageSize = 100).mirror()

                    lookup.calls.size shouldBe 5
                    summary.makerLookupAborted shouldBe true
                    summary.isPartial() shouldBe true
                }
            }

            `when`("유형 실패도 보완 중단도 없으면") {
                then("부분 완료가 아니다") {
                    mirror(FakeSource(mapOf("유탕면" to ramen)), FakeLookup(emptyMap()), listOf("유탕면")).mirror().isPartial() shouldBe false
                }
            }

            `when`("보완 조회 실패가 간헐적이면(성공이 사이에 끼면)") {
                then("연속 횟수가 초기화돼 끝까지 진행한다") {
                    val many = (1..8).map { product("9100000000%03d".format(it), "봇면$it", "유탕면류", maker = "알수없음") }
                    val lookup = FakeLookup(many.associate { it.reportNo to "㈜테스트" })
                    lookup.failing += many.filterIndexed { i, _ -> i % 2 == 0 }.map { it.reportNo }

                    val summary = mirror(FakeSource(mapOf("유탕면" to many)), lookup, listOf("유탕면"), pageSize = 100).mirror()

                    lookup.calls.size shouldBe 8
                    summary.makerLookupAborted shouldBe false
                    summary.makerFilled shouldBe 4
                }
            }

            `when`("페이지에 품목보고번호가 컬럼(20자)보다 긴 행과 판매원이 300자를 넘는 행이 섞여 있으면 — 공부 세션") {
                then("긴 번호 행만 건너뛰어 세고, 나머지는 저장하며 긴 문자열은 컬럼 길이로 자른다(페이지·유형이 멈추지 않는다)") {
                    val rows = listOf(
                        product("1".repeat(25), "번호긴행", "유탕면류"),
                        product("9500000000001", "판매원긴행", "유탕면류").copy(seller = "판".repeat(400)),
                        product("9500000000002", "보통행", "유탕면류"),
                    )

                    val summary = mirror(FakeSource(mapOf("유탕면" to rows)), FakeLookup(emptyMap()), listOf("유탕면"), pageSize = 100).mirror()

                    summary.skippedRows shouldBe 1
                    summary.inserted shouldBe 2
                    count("SELECT COUNT(*) FROM processed_food_product") shouldBe 2
                    count("SELECT CHAR_LENGTH(seller) FROM processed_food_product WHERE report_no = '9500000000001'") shouldBe 300
                }
            }

            `when`("제조사 미기재 판정이 SQL·코틀린에서 갈릴 수 있는 값이면 — 공부 세션") {
                then("'_주소' 처럼 회사명이 빈 판매원은 미기재로 조회하고, '알수없음상사' 처럼 접두만 같은 회사명은 조회하지 않는다") {
                    val rows = listOf(
                        product("9600000000001", "판매원빈회사", "유탕면류", maker = "알수없음").copy(seller = "_전라북도 익산시 서동로"),
                        product("9600000000002", "접두만같음", "유탕면류", maker = "알수없음상사"),
                    )
                    val lookup = FakeLookup(mapOf("9600000000001" to "㈜테스트"))

                    mirror(FakeSource(mapOf("유탕면" to rows)), lookup, listOf("유탕면"), pageSize = 100).mirror()

                    lookup.calls shouldBe listOf("9600000000001")
                }
            }

            `when`("한 유형의 호출이 재시도 끝에 실패하면") {
                then("그 유형만 실패로 남기고 다른 유형은 계속 수집한다") {
                    val source = FakeSource(mapOf("유탕면" to ramen, "과자" to snacks))
                    source.failTypes += "유탕면"

                    val summary = mirror(source, FakeLookup(emptyMap()), listOf("유탕면", "과자")).mirror()

                    summary.failedTypes shouldBe listOf("유탕면")
                    summary.perType["과자"] shouldBe 2
                    count("SELECT COUNT(*) FROM processed_food_product") shouldBe 2
                }
            }
        }
    }
}
