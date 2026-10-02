package com.kbap.api.architecture

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.member.MemberJpaRepository
import jakarta.persistence.EntityManager
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

class ReadOnlyTransactionWriteFixture(
    private val jdbcTemplate: JdbcTemplate,
    private val namedJdbcTemplate: NamedParameterJdbcTemplate,
    private val foodRepository: FoodJpaRepository,
    private val memberRepository: MemberJpaRepository,
    private val entityManager: EntityManager,
    private val transactionTemplate: TransactionTemplate,
) {
    fun read(): Long = foodRepository.count()

    fun jdbcUpdate(): Int = jdbcTemplate.update("UPDATE food SET spiciness = 0")

    fun namedJdbcUpdate(): Int = namedJdbcTemplate.update("UPDATE food SET spiciness = 0", emptyMap<String, Any>())

    fun repositorySave(food: Food): Food = foodRepository.save(food)

    fun repositoryDelete(food: Food) = foodRepository.delete(food)

    fun modifyingQuery(): Int = memberRepository.increaseScanCount(1)

    fun entityAssignment(food: Food) {
        food.description = "읽기 전용에서 바꾼 설명"
    }

    fun entityManagerPersist(food: Food) = entityManager.persist(food)

    fun bulkUpdate(): Int = entityManager.createQuery("update Food f set f.spiciness = 0").executeUpdate()

    fun throughHelper(food: Food): Food = helper(food)

    private fun helper(food: Food): Food = foodRepository.save(food)

    fun saveInOptionalLambda(food: Food): Food = foodRepository.findById(1).orElseGet { foodRepository.save(food) }

    fun saveInStreamLambda(foods: List<Food>): List<Food> = foods.stream().map { foodRepository.save(it) }.toList()

    fun saveInTransactionTemplate(food: Food) = transactionTemplate.executeWithoutResult { foodRepository.save(food) }

    fun throughInterface(writer: Writer) = writer.write()

    interface Writer {
        fun write(): Int
    }

    class JdbcWriter(private val jdbcTemplate: JdbcTemplate) : Writer {
        override fun write(): Int = jdbcTemplate.update("UPDATE food SET spiciness = 0")
    }
}
