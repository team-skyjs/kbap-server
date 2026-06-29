package com.meogo.app.api.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.BehaviorSpec

class ModuleBoundaryTest : BehaviorSpec({

    val imported: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.meogo")

    val kernel = "com.meogo.core.kernel.."
    val anyDomain = "com.meogo.core.."
    val domains =
        listOf(
            "com.meogo.core.food..",
            "com.meogo.core.member..",
            "com.meogo.core.scan..",
            "com.meogo.core.assessment..",
            "com.meogo.core.research..",
            "com.meogo.core.review..",
        )
    val spring = "org.springframework.."
    val jpa = "jakarta.persistence.."

    given("커널 모듈(core:kernel) 경계") {
        `when`("커널이 의존하는 패키지를 검사하면") {
            then("스프링·JPA·도메인·상위 계층에 의존하지 않는다") {
                noClasses().that().resideInAPackage(kernel)
                    .should().dependOnClassesThat().resideInAnyPackage(
                        spring,
                        jpa,
                        *domains.toTypedArray(),
                        "com.meogo.application..",
                        "com.meogo.infra..",
                        "com.meogo.app..",
                        "com.meogo.common..",
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("도메인 모듈(core:*) 경계") {
        `when`("도메인이 스프링·JPA 에 의존하는지 검사하면") {
            then("완전 Spring-free·ORM-free 다") {
                noClasses().that().resideInAPackage(anyDomain).and().resideOutsideOfPackage(kernel)
                    .should().dependOnClassesThat().resideInAnyPackage(spring, jpa)
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }

        `when`("도메인이 상위 계층(application·infra·app·common)에 의존하는지 검사하면") {
            then("상위 계층을 알지 못한다") {
                noClasses().that().resideInAPackage(anyDomain).and().resideOutsideOfPackage(kernel)
                    .should().dependOnClassesThat().resideInAnyPackage(
                        "com.meogo.application..",
                        "com.meogo.infra..",
                        "com.meogo.app..",
                        "com.meogo.common..",
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }

        domains.forEach { domain ->
            `when`("$domain 가 다른 도메인 컨텍스트에 의존하는지 검사하면") {
                then("서로 다른 도메인 컨텍스트는 격리된다") {
                    val others = domains.filter { it != domain }.toTypedArray()
                    noClasses().that().resideInAPackage(domain)
                        .should().dependOnClassesThat().resideInAnyPackage(*others)
                        .allowEmptyShould(true)
                        .check(imported)
                }
            }
        }
    }

    given("유스케이스 모듈(application) 경계") {
        `when`("application 이 인프라·부트앱에 의존하는지 검사하면") {
            then("infra·app 구현에 직접 의존하지 않는다") {
                noClasses().that().resideInAPackage("com.meogo.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        "com.meogo.infra..",
                        "com.meogo.app..",
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("부트앱 모듈(app:api) 경계") {
        `when`("app:api 가 영속·도메인 내부에 의존하는지 검사하면") {
            then("persistence·도메인 컨텍스트를 직접 import 하지 않는다") {
                noClasses().that().resideInAPackage("com.meogo.app.api..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        "com.meogo.infra.persistence..",
                        *domains.toTypedArray(),
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("공유 모듈(common) 경계") {
        `when`("common 이 도메인·상위 계층·스프링·JPA 에 의존하는지 검사하면") {
            then("가볍게 유지되어 어떤 계층도 알지 못한다") {
                noClasses().that().resideInAPackage("com.meogo.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        anyDomain,
                        "com.meogo.application..",
                        "com.meogo.infra..",
                        "com.meogo.app..",
                        spring,
                        jpa,
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("영속 엔티티 위치") {
        `when`("@Entity 가 붙은 클래스의 패키지를 검사하면") {
            then("모든 JPA 엔티티는 infra:persistence 에만 존재한다") {
                classes().that().areAnnotatedWith("jakarta.persistence.Entity")
                    .should().resideInAPackage("com.meogo.infra.persistence..")
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }
})
