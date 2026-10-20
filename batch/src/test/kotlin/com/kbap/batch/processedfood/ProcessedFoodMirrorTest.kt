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
        val calls = mutableListOf<Pair<String, Int>>()
        val failTypes = mutableSetOf<String>()

        override fun getPage(foodType: String, pageNo: Int, pageSize: Int): HaccpProductPage {
            calls += foodType to pageNo
            counter?.let { it.attempts += if (foodType in failTypes) 4 else 1 }
            if (foodType in failTypes) throw PublicDataCallException("HACCP 제품정보 호출 실패(재시도 3회): HTTP 504")
            val all = catalog[foodType].orEmpty()
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
