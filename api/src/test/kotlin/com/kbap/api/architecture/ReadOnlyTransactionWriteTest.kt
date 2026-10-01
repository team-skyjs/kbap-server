package com.kbap.api.architecture

import com.kbap.api.admin.AdminFoodService
import com.tngtech.archunit.core.domain.JavaAccess
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaCodeUnit
import com.tngtech.archunit.core.domain.JavaFieldAccess
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.withClue
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import jakarta.persistence.Embeddable
import jakarta.persistence.Entity
import jakarta.persistence.MappedSuperclass
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.repository.Repository
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Tags("arch")
class ReadOnlyTransactionWriteTest : BehaviorSpec({

    val imported = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
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
            owner.isAssignableTo(JdbcOperations::class.java) && (name == "update" || name == "batchUpdate" || name == "execute") -> "JdbcTemplate.$name"
            owner.isAssignableTo(Repository::class.java) && (name.startsWith("save") || name.startsWith("delete")) -> "${owner.simpleName}.$name"
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
            then("쓰기가 없다 — JdbcTemplate 쓰기·@Modifying 쿼리·save/delete·엔티티 필드 변경 모두 0건") {
                withClue(
                    "읽기 전용 트랜잭션은 DB 세션에 read-only 를 걸지 않는다(커넥션을 트랜잭션 단위로 돌려주는 방식, KB-681). " +
                        "여기서 쓰기를 부르면 MySQL 이 거절하지 않고 그대로 나가거나(JDBC·@Modifying·save), flush 가 없어 조용히 버려진다(엔티티 변경). " +
                        "쓰기가 필요하면 그 메서드의 readOnly 를 떼라.",
                ) {
                    readOnlyEntries.flatMap { entry -> findings(entry).let { (writes, mutations) -> writes + mutations } }.distinct() shouldBe emptyList()
                }
            }

            then("검사기가 쓰기를 실제로 찾아낸다 — 쓰기 트랜잭션(어드민 음식 수정)에서는 JDBC 쓰기·save·엔티티 변경이 잡힌다") {
                val (writes, mutations) = findings(imported.get(AdminFoodService::class.java).codeUnits.single { it.name == "updateFood" })

                writes.map { it.substringAfterLast(" -> ") }.toSet() shouldContainAll setOf("JdbcTemplate.batchUpdate", "FoodVectorOutboxJpaRepository.save")
                mutations.any { it.endsWith("sets Food.description") } shouldBe true
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
})
