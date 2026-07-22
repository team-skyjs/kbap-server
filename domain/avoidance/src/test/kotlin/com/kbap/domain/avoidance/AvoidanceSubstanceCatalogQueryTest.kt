package com.kbap.domain.avoidance

import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.domain.avoidance.model.AvoidanceSubstance
import com.kbap.domain.avoidance.model.AvoidanceSubstanceCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class AvoidanceSubstanceCatalogQueryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var substanceJpaRepository: AvoidanceSubstanceJpaRepository

    init {
        afterSpec { substanceJpaRepository.deleteAll() }

        fun saveSubstance(code: AvoidanceSubstanceCode, koreanName: String): AvoidanceSubstance =
            substanceJpaRepository.save(AvoidanceSubstance(code = code, koreanName = koreanName))

        given("활성 성분 전체 목록 조회") {
            `when`("여러 성분을 저장하고 findAll 로 조회하면") {
                then("활성 성분 전체를 반환한다") {
                    substanceJpaRepository.deleteAll()
                    saveSubstance(AvoidanceSubstanceCode.EGG, koreanName = "달걀")
                    saveSubstance(AvoidanceSubstanceCode.WHEAT, koreanName = "밀")
                    saveSubstance(AvoidanceSubstanceCode.PEANUT, koreanName = "땅콩")

                    substanceJpaRepository.findAll().map { it.code } shouldContainExactlyInAnyOrder listOf(
                        AvoidanceSubstanceCode.EGG,
                        AvoidanceSubstanceCode.WHEAT,
                        AvoidanceSubstanceCode.PEANUT,
                    )
                }
            }

            `when`("성분 하나를 소프트삭제한 뒤 조회하면") {
                then("@SQLRestriction 으로 삭제 성분은 빠지고 살아있는 성분만 반환한다") {
                    substanceJpaRepository.deleteAll()
                    saveSubstance(AvoidanceSubstanceCode.SHRIMP, koreanName = "새우")
                    val deleted = saveSubstance(AvoidanceSubstanceCode.CRAB, koreanName = "게")
                    deleted.delete()
                    substanceJpaRepository.save(deleted)

                    substanceJpaRepository.findAll().map { it.code } shouldContainExactlyInAnyOrder listOf(
                        AvoidanceSubstanceCode.SHRIMP,
                    )
                }
            }
        }
    }
}
