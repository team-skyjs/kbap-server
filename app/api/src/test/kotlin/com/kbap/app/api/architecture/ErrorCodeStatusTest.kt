package com.kbap.app.api.architecture

import com.kbap.core.error.ErrorCode
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus

class ErrorCodeStatusTest : BehaviorSpec({

    val imported: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.kbap")

    val errorCodeValues: List<ErrorCode> =
        imported
            .filter { it.isEnum && it.isAssignableTo(ErrorCode::class.java) }
            .map { Class.forName(it.name) }
            .flatMap { it.enumConstants.asList() }
            .map { it as ErrorCode }

    given("전 모듈의 ErrorCode enum status") {
        `when`("classpath 에서 ErrorCode 구현을 스캔하면") {
            then("최소 하나 이상 발견된다") {
                errorCodeValues.shouldNotBeEmpty()
            }
        }

        `when`("각 status 정수를 HTTP 상태로 변환하면") {
            then("실제 존재하는 4xx 또는 5xx 코드로 매핑된다") {
                errorCodeValues.forEach { errorCode ->
                    withClue("${errorCode::class.simpleName}.$errorCode -> status=${errorCode.status}") {
                        val status = HttpStatus.resolve(errorCode.status)
                        status.shouldNotBeNull()
                        (status.is4xxClientError || status.is5xxServerError) shouldBe true
                    }
                }
            }
        }
    }
})
