package com.kbap.common.infra.llm.menu

import com.kbap.common.port.llm.MenuBoardLine
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt

class MenuBoardLineExtractionTest : BehaviorSpec({
    val parser = MenuBoardResultParser()

    given("묶음 스캔용 메뉴판 행 파싱") {
        `when`("원문 이름·한국어 이름·가격·옵션이 오면") {
            then("행 그대로 담고, 가격 0·음수는 null, 빈 옵션은 null, 한국어 이름이 비면 원문 이름을 쓴다") {
                parser.parseLines(
                    """{"results":[
                    {"rawName":"김치찌개 (대)","koreanName":"김치찌개","price":12000,"option":"대"},
                    {"rawName":"Bulgogi 불고기","koreanName":"불고기","price":0,"option":"  "},
                    {"rawName":"공기밥","koreanName":"","price":-1,"option":null}
                    ]}""",
                ) shouldBe listOf(
                    MenuBoardLine("김치찌개 (대)", "김치찌개", 12000, "대"),
                    MenuBoardLine("Bulgogi 불고기", "불고기", null, null),
                    MenuBoardLine("공기밥", "공기밥", null, null),
                )
            }
        }
        `when`("코드펜스로 감싸 오거나 배열로 오면") {
            then("벗겨서 읽고, 원문 이름이 빈 행은 버린다") {
                parser.parseLines("```json\n[{\"rawName\":\"냉면\",\"koreanName\":\"냉면\",\"price\":9000},{\"rawName\":\" \",\"price\":1000}]\n```") shouldBe
                    listOf(MenuBoardLine("냉면", "냉면", 9000, null))
            }
        }
        `when`("JSON 이 아니면") {
            then("MenuBoardParseException") {
                shouldThrow<MenuBoardParseException> { parser.parseLines("메뉴판이 아닙니다") }
            }
        }
    }

    given("묶음 스캔용 비전 추출") {
        `when`("extractLines 를 부르면") {
            then("괄호·옵션을 지우지 않는 묶음 프롬프트로 사진 URL 과 함께 묻는다") {
                var captured: Prompt? = null
                val chatModel = object : ChatModel {
                    override fun call(prompt: Prompt): ChatResponse {
                        captured = prompt
                        return ChatResponse(listOf(Generation(AssistantMessage("""{"results":[{"rawName":"짜장면(곱빼기)","koreanName":"짜장면","price":8000,"option":"곱빼기"}]}"""))), ChatResponseMetadata.builder().model("m").build())
                    }
                }
                val extractor = OpenAiMenuBoardVisionExtractor(chatModel, parser, "https://cdn.test")
                extractor.extractLines("dev/images/scans/a.jpg") shouldBe listOf(MenuBoardLine("짜장면(곱빼기)", "짜장면", 8000, "곱빼기"))
                val system = captured!!.instructions.filterIsInstance<SystemMessage>().single().text
                system shouldContain "rawName"
                system shouldContain "option"
                system shouldNotContain "괄호 설명 등 음식명이 아닌 부가 정보는 모두 제거"
                captured!!.instructions.filterIsInstance<UserMessage>().single().media.single().data.toString() shouldBe "https://cdn.test/dev/images/scans/a.jpg"
            }
        }
    }
})
