package com.kbap.api.review

import com.kbap.common.domain.LanguageCode
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ReviewLanguageDetectorTest : BehaviorSpec({

    val detector = ReviewLanguageDetector()

    given("리뷰 본문 언어 판별") {
        `when`("한 언어로 쓴 리뷰풍 글이면") {
            then("그 앱 언어를 돌려준다") {
                listOf(
                    "맛있어요" to LanguageCode.KO,
                    "진짜 맛있음 ㅋㅋㅋ 또 올게요 ㅠㅠ" to LanguageCode.KO,
                    "김치찌개 JMT 진짜 맛있어요" to LanguageCode.KO,
                    "2인분 시켰는데 양 많음\n가격 ₩12,000 ★★★\n19:30 도착, 매장까지 500m\n굿" to LanguageCode.KO,
                    "おいしかったです" to LanguageCode.JA,
                    "スープが濃厚で最高" to LanguageCode.JA,
                    "美味しい" to LanguageCode.JA,
                    "汤头很浓郁，非常好吃" to LanguageCode.ZH_HANS,
                    "这家店的拌饭真的很好吃" to LanguageCode.ZH_HANS,
                    "湯頭濃郁，非常好吃" to LanguageCode.ZH_HANT,
                    "這家店的拌飯真的很好吃" to LanguageCode.ZH_HANT,
                    "อร่อยมาก" to LanguageCode.TH,
                    "Очень вкусно" to LanguageCode.RU,
                    "Было очень вкусно, рекомендую" to LanguageCode.RU,
                    "The broth was rich and so tasty" to LanguageCode.EN,
                    "Really good, will come again" to LanguageCode.EN,
                    "La sopa estaba muy picante" to LanguageCode.ES,
                    "Muy bueno, lo recomiendo" to LanguageCode.ES,
                    "Hơi cay nhưng rất ngon" to LanguageCode.VI,
                    "Rất ngon" to LanguageCode.VI,
                    "Kuahnya gurih dan pedas" to LanguageCode.ID,
                    "Makanan terbaik yang pernah saya coba" to LanguageCode.ID,
                    "Saya akan datang lagi" to LanguageCode.ID,
                ).forEach { (text, expected) -> withClue(text) { detector.detect(text) shouldBe expected } }
            }
        }

        `when`("판별이 애매하면") {
            then("null 이다 — 틀리게 붙이느니 모른다고 한다") {
                listOf(
                    "본문 없음" to null,
                    "빈 글" to "   ",
                    "자모만" to "ㅋㅋㅋ",
                    "자모만(자판 두드림)" to "ㅇㄹㄹㄹㄹ",
                    "이모지만" to "😋😋👍",
                    "숫자와 기호만" to "10/10 ★★★",
                    "해시태그·URL·@아이디만" to "#kfood https://example.com/menu @kbap_official",
                    "라틴 한 낱말" to "Good",
                    "라틴 한 낱말(음식 이름)" to "Kimchi",
                    "뜻 없는 한 낱말" to "gigi",
                    "로마자로 쓴 한국어" to "jinjja mashisseoyo daebak",
                    "병음으로 쓴 중국어" to "hen hao chi",
                    "로마자로 쓴 한국어(네 낱말)" to "jinjja mashisseoyo daebak jmt",
                    "병음으로 쓴 중국어(여섯 낱말)" to "zhe jia dian hen hao chi",
                    "전용 글자가 없는 베트남어 — 모델의 베트남어 판정은 쓰지 않는다" to "Món này ngon quá",
                    "간체·번체 공통 한자뿐" to "很好吃",
                    "한자 여덟 자 미만" to "下次还会来",
                    "가나 없는 일본 한자(짧음)" to "国内最高",
                    "가나 없는 일본 한자(짧음, 번체 쪽 글자)" to "焼飯最高",
                    "가나 없는 일본 한자(여덟 자 이상, 일본에만 있는 글자 駅·焼 + 번체 쪽 글자 東)" to "東京駅前焼肉定食最高",
                    "문자가 섞여 지배 문자가 없음" to "Bibimbap 맛있어요",
                    "한글과 한자가 반반" to "맛있다 好吃好吃",
                ).forEach { (case, text) -> withClue(case) { detector.detect(text) shouldBe null } }
            }
        }

        `when`("앱이 지원하지 않는 언어로 쓴 글이면") {
            then("null 이다 — 가까운 앱 언어로 붙이지 않는다") {
                listOf(
                    "프랑스어" to "C'était délicieux, je reviendrai bientôt",
                    "포르투갈어" to "Muito bom, vou voltar com certeza",
                    "독일어" to "Die Suppe war etwas zu salzig",
                    "이탈리아어" to "La zuppa era un po' troppo salata",
                    "타갈로그어" to "Masarap pero medyo maalat talaga",
                    "아랍어" to "الطعام لذيذ جدا",
                    "우크라이나어(러시아어에 없는 글자 і)" to "Суп був густий і гострий",
                    "세르비아어(ј)" to "Било је веома укусно",
                    "카자흐어(ө·ә)" to "Өте дәмді болды",
                    "몽골어(ү)" to "Хоол үнэхээр амттай",
                ).forEach { (case, text) -> withClue(case) { detector.detect(text) shouldBe null } }
            }
        }

        `when`("베트남어 글자가 든 낱말이 다른 언어 문장에 하나 끼어 있으면") {
            then("베트남어로 붙이지 않는다") {
                detector.detect("The phở was really great today") shouldBe LanguageCode.EN
                detector.detect("Le phở était vraiment délicieux") shouldBe null
            }
        }

        `when`("한국 음식 이름을 로마자로 섞어 쓴 영어 리뷰이면") {
            then("en 이다 — 음식 이름 때문에 모델이 타갈로그어·말레이어로 기울어도 영어 표지 낱말로 붙인다") {
                listOf(
                    "Best bibimbap ever lol 😋 #kfood #seoul https://example.com/menu @kbap_official",
                    "Best bibimbap ever lol",
                    "The bibimbap was great",
                    "Tteokbokki was too spicy but really good",
                ).forEach { text -> withClue(text) { detector.detect(text) shouldBe LanguageCode.EN } }
            }
        }

        `when`("두세 낱말짜리 라틴 글이면") {
            then("모델의 1위 앱 언어가 다른 판정과 충분히 벌어질 때만 붙인다") {
                listOf(
                    "not bad" to LanguageCode.EN,
                    "Nice tasty" to LanguageCode.EN,
                    "Nice tasty food" to LanguageCode.EN,
                    "delicious but soso" to LanguageCode.EN,
                    "Very good" to LanguageCode.EN,
                    "Muy bueno" to LanguageCode.ES,
                    "Sangat enak" to LanguageCode.ID,
                    "Enak sekali" to LanguageCode.ID,
                ).forEach { (text, expected) -> withClue(text) { detector.detect(text) shouldBe expected } }
            }
            then("한 낱말·앱 밖 언어·차이가 작은 판정은 null 이다") {
                listOf("Fr", "gigi", "goooood", "Oooooooo", "ghhhh", "Ang sarap", "Lekker eten", "Très bon", "Muito bom", "hen hao chi")
                    .forEach { text -> withClue(text) { detector.detect(text) shouldBe null } }
            }
        }

        `when`("짧은 라틴 글의 판정 차이 기준을 높이면") {
            then("같은 글도 null 이다") {
                val strict = ReviewLanguageDetector(shortLatinMinMargin = 0.9)
                strict.detect("Very good") shouldBe null
                strict.detect("Muy bueno") shouldBe null
                strict.detect("The broth was rich and so tasty") shouldBe LanguageCode.EN
            }
        }

        `when`("말레이어 글이면") {
            then("인도네시아어로 본다 — 서로 읽히는 언어라 받아들인 판정이다") {
                detector.detect("Kuahnya pekat dan pedas, memang sedap") shouldBe LanguageCode.ID
            }
        }
    }
})
