package com.kbap.api.architecture

import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaFieldAccess
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import jakarta.persistence.EntityManager
import jakarta.persistence.Query
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.MappedSuperclass
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.repository.Repository
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Tags("arch")
class ReadOnlyTransactionWriteTest : BehaviorSpec({

    val fixtureName = ReadOnlyTransactionWriteFixture::class.java.simpleName
    val imported = ClassFileImporter()
        .withImportOption { location -> ImportOption.Predefined.DO_NOT_INCLUDE_TESTS.includes(location) || location.contains(fixtureName) }
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TEST_FIXTURES)
        .importPackages("com.kbap")

    fun JavaCodeUnit.transactional(): Transactional? =
        if (isAnnotatedWith(Transactional::class.java)) getAnnotationOfType(Transactional::class.java) else null

    fun JavaClass.isPersistent(): Boolean =
        isAnnotatedWith(Entity::class.java) || isAnnotatedWith(MappedSuperclass::class.java) || isAnnotatedWith(Embeddable::class.java)

    fun writeOf(access: JavaAccess<*>): String? {
        val owner = access.targetOwner
        val name = access.name
        return when {
            (owner.isAssignableTo(JdbcOperations::class.java) || owner.isAssignableTo(NamedParameterJdbcOperations::class.java)) &&
                name in JDBC_WRITES -> "JDBC $name"
            owner.isAssignableTo(JdbcClient.StatementSpec::class.java) && name == "update" -> "JDBC $name"
            owner.isAssignableTo(Repository::class.java) && (name.startsWith("save") || name.startsWith("delete")) -> "${owner.simpleName}.$name"
            owner.isAssignableTo(EntityManager::class.java) && name in ENTITY_MANAGER_WRITES -> "EntityManager.$name"
            owner.isAssignableTo(Query::class.java) && name == "executeUpdate" -> "Query.executeUpdate"
            else -> null
        }
    }

    fun findings(entry: JavaCodeUnit): Pair<List<String>, List<String>> {
        val writes = mutableListOf<String>()
        val mutations = mutableListOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(codeUnit: JavaCodeUnit, path: String) {
            if (!visited.add(codeUnit.fullName)) return
            if (codeUnit.owner.isPersistent() && !codeUnit.isConstructor) {
                codeUnit.fieldAccesses
                    .filter { it.accessType == JavaFieldAccess.AccessType.SET && it.targetOwner.isPersistent() }
                    .forEach { mutations += "$path sets ${it.targetOwner.simpleName}.${it.name}" }
            }
            val accesses = codeUnit.callsFromSelf + codeUnit.codeUnitReferencesFromSelf
            accesses.forEach { access ->
                writeOf(access)?.let { writes += "$path -> $it" }
                val target = (access.target.resolveMember() as java.util.Optional<*>).orElse(null) as? JavaCodeUnit ?: return@forEach
                if (target.isAnnotatedWith(Modifying::class.java)) writes += "$path -> ${target.owner.simpleName}.${target.name}(@Modifying)"
                if (!target.owner.packageName.startsWith("com.kbap")) return@forEach
                val propagation = target.transactional()?.propagation
                if (propagation == Propagation.REQUIRES_NEW || propagation == Propagation.NOT_SUPPORTED) return@forEach
                visit(target, "$path > ${target.owner.simpleName}.${target.name}")
            }
        }
        visit(entry, "${entry.owner.simpleName}.${entry.name}")
        return writes to mutations
    }

    val readOnlyEntries = imported.flatMap { it.codeUnits }.filter { it.transactional()?.readOnly == true }

    given("읽기 전용 트랜잭션(@Transactional(readOnly = true))에서 닿는 코드") {
        `when`("정적 호출 그래프를 따라가면") {
            then("쓰기가 없다 — JDBC 쓰기·@Modifying 쿼리·save/delete·EntityManager 쓰기·벌크 update·엔티티 필드 변경 모두 0건") {
                withClue(
                    "읽기 전용 트랜잭션은 DB 세션에 read-only 를 걸지 않는다(커넥션을 트랜잭션 단위로 돌려주는 방식, KB-681). " +
                        "여기서 쓰기를 부르면 MySQL 이 거절하지 않고 그대로 나가거나(JDBC·@Modifying·save), flush 가 없어 조용히 버려진다(엔티티 변경). " +
                        "쓰기가 필요하면 그 메서드의 readOnly 를 떼라.",
                ) {
                    readOnlyEntries.flatMap { entry -> findings(entry).let { (writes, mutations) -> writes + mutations } }.distinct() shouldBe emptyList()
                }
            }
        }
    }

    given("쓰기 검사기 자체(테스트 전용 고정물로 확인)") {
        val fixture = imported.get(ReadOnlyTransactionWriteFixture::class.java)

        fun found(method: String): List<String> =
            findings(fixture.codeUnits.single { it.name == method }).let { (writes, mutations) -> writes + mutations }.map { it.substringAfterLast(" > ") }

        `when`("쓰기 종류마다 한 메서드씩 넣어 보면") {
            then("직접 부른 쓰기는 전부 잡는다 — JDBC·save·delete·@Modifying·엔티티 대입·persist·벌크 update") {
                found("read") shouldBe emptyList()
                found("jdbcUpdate").single() shouldEndWith "-> JDBC update"
                found("namedJdbcUpdate").single() shouldEndWith "-> JDBC update"
                found("repositorySave").single() shouldEndWith "-> FoodJpaRepository.save"
                found("repositoryDelete").single() shouldEndWith "-> FoodJpaRepository.delete"
                found("modifyingQuery").single() shouldEndWith "-> MemberJpaRepository.increaseScanCount(@Modifying)"
                found("entityAssignment").single() shouldEndWith "sets Food.description"
                found("entityManagerPersist").single() shouldEndWith "-> EntityManager.persist"
                found("bulkUpdate").single() shouldEndWith "-> Query.executeUpdate"
            }

            then("같은 클래스의 다른 메서드를 거친 간접 쓰기도 잡는다") {
                found("throughHelper").single() shouldEndWith "-> FoodJpaRepository.save"
            }

            then("인라인되지 않는 람다 안의 쓰기도 잡는다 — Optional.orElseGet·stream.map·TransactionTemplate") {
                found("saveInOptionalLambda").single() shouldEndWith "-> FoodJpaRepository.save"
                found("saveInStreamLambda").single() shouldEndWith "-> FoodJpaRepository.save"
                found("saveInTransactionTemplate").single() shouldEndWith "-> FoodJpaRepository.save"
            }

            then("한계: 인터페이스 뒤의 구현은 따라가지 않는다 — 포트 구현·이벤트 리스너·리플렉션으로 닿는 쓰기는 이 검사가 보지 못한다") {
                found("throughInterface") shouldBe emptyList()
            }
        }
    }

    given("클래스 단위 @Transactional") {
        `when`("운영 코드를 전수 검사하면") {
            then("없다 — 읽기 전용 클래스의 쓰기 메서드가 덮어쓰기를 빠뜨려 읽기 전용으로 도는 일이 생기지 않는다") {
                imported.filter { it.isAnnotatedWith(Transactional::class.java) }.map { it.name } shouldBe emptyList()
            }
        }
    }
}) {
    private companion object {
        val JDBC_WRITES = setOf("update", "batchUpdate", "execute")
        val ENTITY_MANAGER_WRITES = setOf("persist", "merge", "remove")
    }
}
