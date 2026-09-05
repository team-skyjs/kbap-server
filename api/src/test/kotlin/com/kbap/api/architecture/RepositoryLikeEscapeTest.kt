package com.kbap.api.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository

@Tags("arch")
class RepositoryLikeEscapeTest : BehaviorSpec({

    given("리포지토리 @Query 의 LIKE 사용") {
        `when`("like 절을 쓰는 쿼리를 전수 검사하면") {
            then("모든 like 절이 escape 절을 동반한다") {
                val imported = ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TEST_FIXTURES)
                    .importPackages("com.kbap")
                val like = Regex("""\blike\b""", RegexOption.IGNORE_CASE)
                val escape = Regex("""\bescape\b""", RegexOption.IGNORE_CASE)

                val violations = imported
                    .filter { it.isInterface && it.isAssignableTo(Repository::class.java) }
                    .flatMap { repo -> repo.methods.map { it.fullName to it.tryGetAnnotationOfType(Query::class.java).orElse(null) } }
                    .filter { (_, query) -> query != null }
                    .flatMap { (method, query) ->
                        listOf(query!!.value, query.countQuery)
                            .filter { sql -> like.findAll(sql).count() > escape.findAll(sql).count() }
                            .map { sql -> "$method: ${sql.trim().lineSequence().first()}" }
                    }

                violations shouldBe emptyList()
            }
        }
    }
})
