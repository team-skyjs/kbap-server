package com.kbap.api.core

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kbap.api.IntegrationTest
import com.kbap.api.PoolProbe
import com.kbap.api.TestTables
import com.kbap.api.reviewbot.ReviewBotAccountService
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.orm.jpa.EntityManagerHolder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.transaction.InvalidIsolationLevelException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

@IntegrationTest
class JpaConnectionHandlingTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var entityManagerFactory: EntityManagerFactory
    @Autowired private lateinit var transactionManager: PlatformTransactionManager
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var memberRepository: MemberJpaRepository
    @Autowired private lateinit var tokenIssuer: TokenIssuer

    init {
        beforeContainer { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        fun held(): Int = PoolProbe.leastActiveConnections(dataSource)

        fun <T> withRequestBoundEntityManager(block: (EntityManager) -> T): T {
            val entityManager = entityManagerFactory.createEntityManager()
            TransactionSynchronizationManager.bindResource(entityManagerFactory, EntityManagerHolder(entityManager))
            try {
                return block(entityManager)
            } finally {
                TransactionSynchronizationManager.unbindResource(entityManagerFactory)
                entityManager.close()
            }
        }

        fun connectionIdOf(entityManager: EntityManager): Long =
            (entityManager.createNativeQuery("SELECT CONNECTION_ID()").singleResult as Number).toLong()

        given("웹 요청에 묶인 영속성 컨텍스트(open-in-view)") {
            `when`("트랜잭션이 끝나면") {
                then("DB 커넥션을 풀에 돌려준다 — 요청이 끝날 때까지 쥐고 있지 않는다") {
                    withRequestBoundEntityManager {
                        TransactionTemplate(transactionManager).apply { isReadOnly = true }.execute { foodRepository.count() }

                        held() shouldBe 0
                    }
                }
            }

            `when`("트랜잭션 밖에서 조회하면") {
                then("그 조회가 끝나는 대로 커넥션을 돌려준다") {
                    withRequestBoundEntityManager { entityManager ->
                        entityManager.createQuery("select count(f) from Food f").singleResult

                        held() shouldBe 0
                    }
                }
            }

            `when`("트랜잭션이 열려 있는 동안에는") {
                then("처음부터 끝까지 한 커넥션을 쓴다 — 문장마다 바뀌지 않는다") {
                    withRequestBoundEntityManager { entityManager ->
                        val ids = TransactionTemplate(transactionManager).execute {
                            listOf(connectionIdOf(entityManager), connectionIdOf(entityManager), jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long::class.java)!!)
                        }!!

                        ids.toSet() shouldHaveSize 1
                    }
                }
            }
        }

        given("JPA 트랜잭션 안의 JdbcTemplate 쓰기") {
            `when`("JPA 쓰기와 JdbcTemplate 쓰기 뒤에 예외가 나면") {
                then("둘 다 롤백된다 — JdbcTemplate 이 JPA 와 같은 커넥션을 탄다") {
                    val saved = foodRepository.save(Food.failed("커넥션찌개"))
                    val foodId = saved.id
                    val ingredientId = jdbcTemplate.queryForObject("SELECT id FROM ingredients WHERE code = 'SESAME'", Long::class.java)!!

                    withRequestBoundEntityManager { entityManager ->
                        shouldThrow<IllegalStateException> {
                            TransactionTemplate(transactionManager).executeWithoutResult {
                                foodRepository.findByIdForUpdate(foodId)!!.description = "바뀐 설명"
                                entityManager.flush()
                                jdbcTemplate.update(
                                    "INSERT INTO food_ingredient (food_id, ingredient_id, inclusion_percent, sort_order) VALUES (?, ?, 100, 100)",
                                    foodId,
                                    ingredientId,
                                )
                                error("롤백")
                            }
                        }
                    }

                    jdbcTemplate.queryForObject("SELECT description FROM food WHERE id = ?", String::class.java, foodId) shouldBe saved.description
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM food_ingredient WHERE food_id = ?", Long::class.java, foodId) shouldBe 0L
                }
            }
        }

        given("이름 잠금(GET_LOCK)을 쓰는 봇 계정 생성 요청") {
            `when`("요청이 끝나면") {
                then("잠금이 풀려 있고 커넥션도 풀에 돌아와 있다 — 획득과 해제가 같은 커넥션에서 돌았다") {
                    mockMvc.post("/api/admin/review-bots") {
                        header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(0, MemberRole.ADMIN)}")
                        contentType = MediaType.APPLICATION_JSON
                        content = """{"count":1}"""
                    }.andExpect { status { isOk() } }

                    jdbcTemplate.queryForObject("SELECT IS_FREE_LOCK(?)", Int::class.java, ReviewBotAccountService.LOCK_NAME) shouldBe 1
                    held() shouldBe 0
                }
            }

            `when`("잠금 해제가 실패하면(내 커넥션의 잠금이 아니라는 응답)") {
                then("ERROR 로그를 남긴다 — 잠금이 풀 커넥션에 남는 누수를 조용히 넘기지 않는다") {
                    val logger = LoggerFactory.getLogger(ReviewBotAccountService::class.java) as ch.qos.logback.classic.Logger
                    val appender = ListAppender<ILoggingEvent>().apply { start() }
                    logger.addAppender(appender)
                    val notReleasing = object : MemberJpaRepository by memberRepository {
                        override fun acquireNamedLock(name: String, timeoutSeconds: Int): Int = 1
                        override fun releaseNamedLock(name: String): Int? = 0
                    }
                    try {
                        ReviewBotAccountService(notReleasing, transactionManager).ensureBots(0)
                    } finally {
                        logger.detachAppender(appender)
                    }

                    appender.list.filter { it.level == Level.ERROR } shouldHaveSize 1
                }
            }
        }

        given("읽기 전용 트랜잭션") {
            `when`("엔티티를 바꾸고 트랜잭션이 끝나면") {
                then("바뀐 값이 저장되지 않는다 — 읽기 전용은 Hibernate 가 flush 하지 않는 것으로 지켜진다") {
                    val saved = foodRepository.save(Food.failed("읽기전용찌개"))

                    withRequestBoundEntityManager {
                        TransactionTemplate(transactionManager).apply { isReadOnly = true }.executeWithoutResult {
                            foodRepository.findById(saved.id).orElseThrow().description = "읽기 전용에서 바꾼 설명"
                        }
                    }

                    jdbcTemplate.queryForObject("SELECT description FROM food WHERE id = ?", String::class.java, saved.id) shouldBe saved.description
                }
            }
        }

        given("격리 수준을 지정한 트랜잭션") {
            `when`("시작하려 하면") {
                then("거절된다 — 커넥션을 트랜잭션 단위로 돌려주는 방식에서는 트랜잭션별 격리 수준을 걸 수 없다(컨벤션상 격리 수준은 손대지 않는다)") {
                    shouldThrow<InvalidIsolationLevelException> {
                        TransactionTemplate(transactionManager)
                            .apply { isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED }
                            .execute { foodRepository.count() }
                    }
                }
            }
        }
    }
}
