package com.kbap.batch.notification

import com.kbap.batch.BatchIntegrationTest
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.NotificationSetting
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.beans.factory.annotation.Autowired

@BatchIntegrationTest
class ScanSuggestionMemberIdReaderTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var settingRepository: NotificationSettingJpaRepository

    private fun readAll(reader: ScanSuggestionMemberIdReader): List<Long> {
        reader.open(ExecutionContext())
        return generateSequence { reader.read() }.toList()
    }

    init {
        given("스캔 제안 대상 회원 리더") {
            `when`("소식을 켠 회원 250명과 끈 회원이 섞여 있으면") {
                settingRepository.deleteAll()
                settingRepository.saveAll((1L..250L).map { NotificationSetting(memberId = it, installationId = "reader-$it", news = true) })
                settingRepository.save(NotificationSetting(memberId = 1L, installationId = "reader-1-b", news = true))
                settingRepository.save(NotificationSetting(memberId = 300L, installationId = "reader-300", news = false))
                val reader = ScanSuggestionMemberIdReader(settingRepository, 100)

                then("켠 회원만 중복 없이 회원 번호 오름차순으로 끝까지 읽는다") {
                    readAll(reader) shouldBe (1L..250L).toList()
                }
                then("다시 열면 처음부터 읽는다") {
                    readAll(reader) shouldBe (1L..250L).toList()
                }
            }

            `when`("대상이 없으면") {
                settingRepository.deleteAll()

                then("바로 끝난다") {
                    readAll(ScanSuggestionMemberIdReader(settingRepository, 100)) shouldBe emptyList()
                }
            }
        }
    }
}
