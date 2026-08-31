package com.kbap.common.domain.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.admin.model.AdminAccount
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class AdminAccountJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adminAccountJpaRepository: AdminAccountJpaRepository

    init {
        fun clear() = adminAccountJpaRepository.deleteAll()

        given("findByLoginId — 로그인 식별자 조회") {
            `when`("저장된 계정의 loginId 로 조회하면") {
                clear()
                adminAccountJpaRepository.save(AdminAccount(loginId = "admin", password = "bcrypt-hash"))

                then("계정을 반환한다") {
                    val found = adminAccountJpaRepository.findByLoginId("admin")
                    found.shouldNotBeNull()
                    found.loginId shouldBe "admin"
                    found.password shouldBe "bcrypt-hash"
                }
            }

            `when`("존재하지 않는 loginId 로 조회하면") {
                clear()

                then("null 을 반환한다") {
                    adminAccountJpaRepository.findByLoginId("ghost").shouldBeNull()
                }
            }

            `when`("소프트 삭제된 계정의 loginId 로 조회하면") {
                clear()
                val deleted = adminAccountJpaRepository.save(AdminAccount(loginId = "retired", password = "bcrypt-hash"))
                deleted.delete()
                adminAccountJpaRepository.save(deleted)

                then("조회되지 않는다") {
                    adminAccountJpaRepository.findByLoginId("retired").shouldBeNull()
                }
            }
        }
    }
}
