package com.kbap.domain.food

import com.kbap.domain.food.model.Food
import com.kbap.core.menu.KoreanMenuNameNormalizer
import com.kbap.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

@SpringBootTest(classes = [FoodTestApp::class])
@Import(MySqlContainerConfig::class)
class FoodMatchKeySyncTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun generatedKeyOf(id: Long): String =
            dataSource.connection.use { connection ->
                connection.prepareStatement("SELECT korean_match_key FROM food WHERE id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getString(1)
                    }
                }
            }

        given("foods.korean_match_key 생성 컬럼과 kernel 정규화 규칙") {
            `when`("다양한 한국어 원문을 저장하면") {
                then("DB 생성 컬럼 값이 KoreanMenuNameNormalizer.matchKey 와 일치한다") {
                    val samples = listOf(
                        "김치찌개",
                        "돼지 국밥",
                        "제육볶음!",
                        "김치찌개 (2인)",
                        "된장찌개 doenjang",
                    )
                    samples.forEach { korean ->
                        val id = foodJpaRepository.save(
                            Food(koreanName = korean, description = "설명"),
                        ).id
                        generatedKeyOf(id) shouldBe KoreanMenuNameNormalizer.matchKey(korean)
                    }
                }
            }
        }
    }
}
