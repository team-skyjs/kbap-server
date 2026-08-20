package com.kbap.api.infra.exchange

import com.kbap.common.domain.CurrencyCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import java.math.BigDecimal

private const val BASE_URL = "https://exchange.test"
private const val LATEST_URL = "$BASE_URL/v1/latest?base=EUR"

private const val SUCCESS_BODY = """
{
  "amount": 1.0,
  "base": "EUR",
  "date": "2026-08-18",
  "rates": { "KRW": 1632.3, "USD": 1.1576, "JPY": 184.87, "IDR": 20677.34, "XYZ": 2.0 }
}
"""

class FrankfurterExchangeRateClientTest : BehaviorSpec({
    fun fixture(): Pair<FrankfurterExchangeRateClient, MockRestServiceServer> {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        return FrankfurterExchangeRateClient.create(BASE_URL, builder) to server
    }

    given("frankfurter 환율 조회") {
        `when`("지원 통화를 조회하면") {
            then("base=EUR 로 요청하고 KRW/X 를 HALF_UP 4자리로 계산한다") {
                val (client, server) = fixture()
                server.expect(requestTo(LATEST_URL))
                    .andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON))

                client.getKrwPerUnitOrNull(CurrencyCode.USD) shouldBe BigDecimal("1410.0726")
                server.verify()
            }

            then("JPY·IDR·EUR 도 같은 규칙으로 계산한다") {
                val (client, server) = fixture()
                server.expect(ExpectedCount.times(3), requestTo(LATEST_URL))
                    .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON))

                client.getKrwPerUnitOrNull(CurrencyCode.JPY) shouldBe BigDecimal("8.8294")
                client.getKrwPerUnitOrNull(CurrencyCode.IDR) shouldBe BigDecimal("0.0789")
                client.getKrwPerUnitOrNull(CurrencyCode.EUR) shouldBe BigDecimal("1632.3000")
            }
        }

        `when`("KRW 를 조회하면") {
            then("제공처 호출 없이 1.0000 을 돌려준다") {
                val (client, server) = fixture()
                server.expect(ExpectedCount.never(), requestTo(LATEST_URL))

                client.getKrwPerUnitOrNull(CurrencyCode.KRW) shouldBe BigDecimal("1.0000")
                server.verify()
            }
        }

        `when`("응답에 요청 통화가 없으면") {
            then("null 을 돌려준다") {
                val (client, server) = fixture()
                server.expect(requestTo(LATEST_URL))
                    .andRespond(withSuccess("""{"amount":1.0,"base":"EUR","date":"2026-08-18","rates":{"KRW":1632.3}}""", MediaType.APPLICATION_JSON))

                client.getKrwPerUnitOrNull(CurrencyCode.USD) shouldBe null
            }
        }

        `when`("응답에 KRW 가 없으면") {
            then("null 을 돌려준다") {
                val (client, server) = fixture()
                server.expect(requestTo(LATEST_URL))
                    .andRespond(withSuccess("""{"amount":1.0,"base":"EUR","date":"2026-08-18","rates":{"USD":1.1576}}""", MediaType.APPLICATION_JSON))

                client.getKrwPerUnitOrNull(CurrencyCode.USD) shouldBe null
            }
        }

        `when`("제공처가 서버 오류를 돌려주면") {
            then("null 을 돌려준다") {
                val (client, server) = fixture()
                server.expect(requestTo(LATEST_URL)).andRespond(withServerError())

                client.getKrwPerUnitOrNull(CurrencyCode.USD) shouldBe null
            }
        }

        `when`("응답 본문이 JSON 이 아니면") {
            then("null 을 돌려준다") {
                val (client, server) = fixture()
                server.expect(requestTo(LATEST_URL))
                    .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON))

                client.getKrwPerUnitOrNull(CurrencyCode.USD) shouldBe null
            }
        }
    }
})
