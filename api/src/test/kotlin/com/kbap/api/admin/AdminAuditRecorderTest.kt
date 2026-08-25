package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.admin.AdminAuditLogJpaRepository
import com.kbap.common.domain.admin.model.AdminAuditAction
import com.kbap.common.domain.admin.model.AdminAuditTargetType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.IllegalTransactionStateException
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@Import(MySqlContainerConfig::class)
class AdminAuditRecorderTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var recorder: AdminAuditRecorder

    @Autowired
    private lateinit var repository: AdminAuditLogJpaRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    init {
        beforeContainer { repository.deleteAll() }

        given("감사 기록기") {
            `when`("호출자 트랜잭션 안에서 변경 전/후를 기록하면") {
                then("변경된 필드만 JSON 으로 남고 조작자·대상·비고가 저장된다") {
                    transactionTemplate.execute {
                        recorder.record(
                            adminId = 7L,
                            action = AdminAuditAction.FOOD_UPDATE,
                            targetType = AdminAuditTargetType.FOOD,
                            targetId = 248L,
                            before = mapOf("description" to "old", "spiciness" to 3, "same" to "x"),
                            after = mapOf("description" to "new", "spiciness" to 3, "same" to "x"),
                            note = "테스트",
                        )
                    }

                    val logs = repository.findAll()
                    logs.size shouldBe 1
                    val log = logs.single()
                    log.adminAccountId shouldBe 7L
                    log.action shouldBe AdminAuditAction.FOOD_UPDATE
                    log.targetType shouldBe AdminAuditTargetType.FOOD
                    log.targetId shouldBe 248L
                    log.beforeJson shouldBe mapOf("description" to "old")
                    log.afterJson shouldBe mapOf("description" to "new")
                    log.note shouldBe "테스트"
                }
            }

            `when`("호출자 트랜잭션이 롤백되면") {
                then("감사 이력도 함께 사라진다") {
                    shouldThrow<IllegalStateException> {
                        transactionTemplate.execute {
                            recorder.record(1L, AdminAuditAction.FOOD_DELETE, AdminAuditTargetType.FOOD, 1L, null, null)
                            throw IllegalStateException("boom")
                        }
                    }

                    repository.count() shouldBe 0
                }
            }

            `when`("트랜잭션 밖에서 호출하면") {
                then("거부한다(MANDATORY)") {
                    shouldThrow<IllegalTransactionStateException> {
                        recorder.record(1L, AdminAuditAction.FOOD_DELETE, AdminAuditTargetType.FOOD, 1L, null, null)
                    }
                }
            }

            `when`("before 가 null 인 생성 조작이면") {
                then("after 전체가 남는다") {
                    transactionTemplate.execute {
                        recorder.record(1L, AdminAuditAction.FOOD_SEED, AdminAuditTargetType.FOOD, null, null, mapOf("created" to 3))
                    }

                    repository.findAll().single().afterJson shouldBe mapOf("created" to 3)
                }
            }
        }
    }
}
