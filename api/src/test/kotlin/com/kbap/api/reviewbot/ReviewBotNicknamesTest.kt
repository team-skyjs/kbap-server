package com.kbap.api.reviewbot

import com.kbap.common.domain.member.model.CountryCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.random.Random

class ReviewBotNicknamesTest : BehaviorSpec({
    val marked = Regex("^[a-z]{5,14}$")

    fun followsBotRule(nickname: String) =
        marked.matches(nickname) && nickname[3] == nickname[4] && nickname[2] != nickname[3]

    given("봇 닉네임 표식(운영 식별 규칙)") {
        `when`("이름의 네 번째 글자를 한 번 더 쓰면") {
            then("kevin 은 keviin, jason 은 jasoon, 네 글자 yuki 는 yukii 가 된다") {
                ReviewBotNicknames.mark("kevin") shouldBe "keviin"
                ReviewBotNicknames.mark("jason") shouldBe "jasoon"
                ReviewBotNicknames.mark("yuki") shouldBe "yukii"
            }
        }
    }

    given("이름 풀의 조건") {
        `when`("모든 풀의 모든 이름을 검사하면") {
            then("전부 조건을 만족하고, 표식을 붙인 닉네임은 4·5번째가 같고 3·4번째가 다르며 14자 이하 소문자다") {
                val names = ReviewBotNicknames.allNames()

                names.filterNot(ReviewBotNicknames::isEligible).shouldBeEmpty()
                names.map(ReviewBotNicknames::mark).filterNot(::followsBotRule).shouldBeEmpty()
            }
        }

        `when`("조건에 안 맞는 이름이면") {
            then("풀에 넣을 수 없다 — 세 글자 이하·3·4번째 겹침(시드 규칙과 혼동)·4·5번째 자연 겹침·대문자·기호·표식 뒤 14자 초과") {
                listOf("amy", "jenny", "kelly", "hannah", "jihoon", "Kevin", "kim_lee", "minh2", "abcdefghijklmn")
                    .filter(ReviewBotNicknames::isEligible).shouldBeEmpty()
            }
        }

        `when`("표식을 붙인 닉네임을 전부 모으면") {
            then("서로 겹치지 않고 봇 최대 수(50)보다 많다 — 어드민 한도 안에서는 풀이 소진되지 않는다") {
                val nicknames = ReviewBotNicknames.allNames().map(ReviewBotNicknames::mark)

                nicknames.size shouldBe nicknames.toSet().size
                nicknames.size shouldBeGreaterThanOrEqual 50
            }
        }
    }

    given("봇 닉네임 고르기") {
        `when`("국가마다 고르면") {
            then("그 국가의 이름 풀에서 나온다 — 봇이 쓰는 모든 국가에 풀이 있다") {
                CountryCode.entries.forEach { country ->
                    val picked = ReviewBotNicknames.pick(country, emptySet(), Random(7))!!
                    (picked in ReviewBotNicknames.poolOf(country).map(ReviewBotNicknames::mark)) shouldBe true
                }
            }
        }

        `when`("그 국가 풀의 이름이 전부 쓰였으면") {
            then("다른 국가 풀에서 고른다") {
                val japanese = ReviewBotNicknames.poolOf(CountryCode.JP).map(ReviewBotNicknames::mark).toSet()

                val picked = ReviewBotNicknames.pick(CountryCode.JP, japanese, Random(7))!!

                (picked in japanese) shouldBe false
                followsBotRule(picked) shouldBe true
            }
        }

        `when`("모든 풀이 소진됐으면") {
            then("null — 규칙을 깨는 닉네임을 지어내지 않는다") {
                val all = ReviewBotNicknames.allNames().map(ReviewBotNicknames::mark).toSet()

                ReviewBotNicknames.pick(CountryCode.US, all, Random(7)) shouldBe null
            }
        }

        `when`("이미 쓰인 닉네임을 넘기며 풀 전체 수만큼 고르면") {
            then("하나도 겹치지 않는다") {
                val used = mutableSetOf<String>()
                repeat(ReviewBotNicknames.allNames().size) {
                    used += ReviewBotNicknames.pick(ReviewBotCountries.pick(Random(it)), used, Random(it))!!
                }

                used.size shouldBe ReviewBotNicknames.allNames().size
            }
        }
    }
})
