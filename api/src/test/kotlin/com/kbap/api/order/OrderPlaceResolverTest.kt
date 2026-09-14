package com.kbap.api.order

import com.kbap.api.place.FakePlaceSearchClient
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.place.FoundPlace
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.math.BigDecimal

class OrderPlaceResolverTest : BehaviorSpec({

    val lat = BigDecimal("37.5636000")
    val lng = BigDecimal("126.9834000")

    fun countOf(meter: SimpleMeterRegistry, result: String): Double =
        meter.find("order.place.resolve").tag("result", result).counter()?.count() ?: 0.0

    given("OrderPlaceResolver.resolve") {
        `when`("주변 식당이 검색되면") {
            then("첫 결과로 스냅샷을 만들고 hit 카운터를 올린다(language=요청 lang)") {
                val client = FakePlaceSearchClient().apply {
                    returns(FoundPlace("place-1", "백년옥", "서울 중구", null, null))
                }
                val meter = SimpleMeterRegistry()

                val snap = OrderPlaceResolver(client, meter).resolve(lat, lng, LanguageCode.EN)

                snap!!.externalId shouldBe "place-1"
                snap.language shouldBe "en"
                countOf(meter, "hit") shouldBe 1.0
            }
        }

        `when`("검색 결과에 placeId 가 없으면") {
            then("외부 식별자 없는 스냅샷은 만들지 않고 empty 로 집계한다") {
                val client = FakePlaceSearchClient().apply {
                    returns(FoundPlace(placeId = null, name = "백년옥", address = null, latitude = null, longitude = null))
                }
                val meter = SimpleMeterRegistry()

                val snap = OrderPlaceResolver(client, meter).resolve(lat, lng, LanguageCode.KO)

                snap.shouldBeNull()
                countOf(meter, "empty") shouldBe 1.0
            }
        }

        `when`("식당명·주소가 최대 길이를 넘으면") {
            then("경계 길이로 잘라 스냅샷에 담는다") {
                val client = FakePlaceSearchClient().apply {
                    returns(FoundPlace("place-1", "가".repeat(120), "나".repeat(250), null, null))
                }
                val snap = OrderPlaceResolver(client, SimpleMeterRegistry()).resolve(lat, lng, LanguageCode.KO)!!

                snap.name.length shouldBe 100
                snap.address!!.length shouldBe 200
            }
        }

        `when`("검색 결과가 없으면") {
            then("null 을 반환하고 empty 카운터를 올린다") {
                val meter = SimpleMeterRegistry()

                val snap = OrderPlaceResolver(FakePlaceSearchClient(), meter).resolve(lat, lng, LanguageCode.KO)

                snap.shouldBeNull()
                countOf(meter, "empty") shouldBe 1.0
            }
        }

        `when`("PLACE_SEARCH_FAILED 가 던져지면") {
            then("fail-open 으로 null·error 카운터를 올린다") {
                val client = FakePlaceSearchClient().apply {
                    failure = BusinessException(ErrorCode.PLACE_SEARCH_FAILED)
                }
                val meter = SimpleMeterRegistry()

                val snap = OrderPlaceResolver(client, meter).resolve(lat, lng, LanguageCode.KO)

                snap.shouldBeNull()
                countOf(meter, "error") shouldBe 1.0
            }
        }

        `when`("다른 BusinessException 이 던져지면") {
            then("전파한다(fail-open 대상 아님)") {
                val client = FakePlaceSearchClient().apply {
                    failure = BusinessException(ErrorCode.FOOD_NOT_FOUND)
                }
                val meter = SimpleMeterRegistry()

                shouldThrow<BusinessException> {
                    OrderPlaceResolver(client, meter).resolve(lat, lng, LanguageCode.KO)
                }
            }
        }
    }
})
