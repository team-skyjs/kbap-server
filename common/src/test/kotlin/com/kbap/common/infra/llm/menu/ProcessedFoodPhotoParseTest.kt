package com.kbap.common.infra.llm.menu

import com.kbap.common.port.llm.ProcessedFoodPhotoKind
import com.kbap.common.port.llm.ProcessedFoodPhotoReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class ProcessedFoodPhotoParseTest : BehaviorSpec({
    val parser = MenuBoardResultParser()

    given("가공식품 사진 비전 응답 파싱") {
        `when`("앞면 응답에 하이픈 섞인 품목보고번호가 오면") {
            then("숫자만 남기고 성분표 필드는 버린다") {
                val reading = parser.parsePhoto(
                    """```json
                    {"kind":"package_front","confidence":0.93,"productName":" 바나나맛우유 ","maker":"빙그레","reportNo":"1976-0344-001","labelText":"원재료명: 우유","warnings":["곡면 반사", ""]}
                    ```""",
                )
                reading.kind shouldBe ProcessedFoodPhotoKind.PACKAGE_FRONT
                reading.productName shouldBe "바나나맛우유"
                reading.reportNo shouldBe "19760344001"
                reading.labelText.shouldBeNull()
                reading.warnings shouldBe listOf("곡면 반사")
            }
        }
        `when`("품목보고번호 자릿수가 맞지 않으면") {
            then("null 로 둔다") {
                parser.parsePhoto("""{"kind":"PACKAGE_FRONT","confidence":0.8,"productName":"새우깡","reportNo":"123"}""").reportNo.shouldBeNull()
            }
        }
        `when`("성분표·기타 응답이면") {
            then("성분표는 labelText 만, 기타는 reason 만 남기고 모르는 reason·범위 밖 confidence 는 정리한다") {
                val label = parser.parsePhoto("""{"kind":"INGREDIENT_LABEL","confidence":1.4,"productName":"새우깡","labelText":"원재료명: 소맥분(밀)"}""")
                label.labelText shouldBe "원재료명: 소맥분(밀)"
                label.productName.shouldBeNull()
                label.confidence shouldBe 1.0
                val other = parser.parsePhoto("""{"kind":"OTHER","confidence":0.6,"reason":"blurry"}""")
                other.reason shouldBe ProcessedFoodPhotoReason.BLURRY
                parser.parsePhoto("""{"kind":"OTHER","confidence":0.6,"reason":"sideways"}""").reason.shouldBeNull()
            }
        }
        `when`("kind 를 모르거나 JSON 이 아니면") {
            then("파싱 예외를 던진다") {
                shouldThrow<MenuBoardParseException> { parser.parsePhoto("""{"kind":"RECEIPT","confidence":0.5}""") }
                shouldThrow<MenuBoardParseException> { parser.parsePhoto("사진을 읽을 수 없습니다") }
            }
        }
    }
})
