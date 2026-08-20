package com.kbap.api.architecture

import com.kbap.common.domain.ingredient.model.IngredientCode
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import io.kotest.core.annotation.Tags
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.lang.reflect.Modifier

@Tags("arch")
class ModuleBoundaryTest : BehaviorSpec({

    val imported: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TEST_FIXTURES)
            .importPackages("com.kbap")

    val core = "com.kbap.common.core.."
    val sharedDomain = "com.kbap.common.domain.."
    val spring = "org.springframework.."
    val jpa = "jakarta.persistence.."

    given("API 모듈 프로덕션 패키지 경계") {
        `when`("구 도메인·application 패키지의 클래스 존재 여부를 검사하면") {
            then("com.kbap.domain 과 com.kbap.application 패키지에는 클래스가 존재하지 않는다") {
                val legacySourceFiles = mutableSetOf<String>()
                val notExist =
                    object : ArchCondition<JavaClass>("구 패키지에 존재하지 않는다") {
                        override fun check(item: JavaClass, events: ConditionEvents) {
                            val sourceFile = "${item.packageName}/${item.sourceCodeLocation.sourceFileName}"
                            if (legacySourceFiles.add(sourceFile)) {
                                events.add(SimpleConditionEvent.violated(item, "$sourceFile 이 구 패키지에 존재한다"))
                            }
                        }
                    }

                classes().that().resideInAnyPackage("com.kbap.domain..", "com.kbap.application..")
                    .should(notExist)
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("커널 패키지(common.core) 경계") {
        `when`("커널이 의존하는 패키지를 검사하면") {
            then("스프링·도메인·포트·상위 계층에 의존하지 않는다") {
                noClasses().that().resideInAPackage(core)
                    .should().dependOnClassesThat().resideInAnyPackage(
                        spring,
                        sharedDomain,
                        "com.kbap.common.port..",
                        "com.kbap.common.infra..",
                        "com.kbap.api..",
                        "com.kbap.batch..",
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("도메인 패키지 경계") {
        `when`("도메인이 포트·인프라·부트앱에 의존하는지 검사하면") {
            then("도메인은 계약(port)과 그 구현을 알지 못한다 — 포트가 도메인 타입을 반환하는 방향만 허용") {
                noClasses().that().resideInAPackage(sharedDomain)
                    .should().dependOnClassesThat().resideInAnyPackage(
                        "com.kbap.common.port..",
                        "com.kbap.common.infra..",
                        "com.kbap.api..",
                        "com.kbap.batch..",
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }

        `when`("영속 애너테이션 없는 도메인 클래스(모델·정책·값 객체)가 jakarta.persistence 에 의존하는지 검사하면") {
            then("도메인 모델은 ORM-free 다 — 도메인 안에서 jakarta.persistence 는 엔티티·리포지토리(커스텀 구현 포함)·도메인 서비스(@Service)만 만진다") {
                noClasses().that().resideInAPackage(sharedDomain)
                    .and().areNotAnnotatedWith("jakarta.persistence.Entity")
                    .and().areNotAnnotatedWith("jakarta.persistence.MappedSuperclass")
                    .and().areNotAnnotatedWith("jakarta.persistence.Embeddable")
                    .and().areNotAnnotatedWith("jakarta.persistence.Converter")
                    .and().areNotAnnotatedWith("org.springframework.stereotype.Service")
                    .and().areNotAssignableTo("org.springframework.data.repository.Repository")
                    .and().haveSimpleNameNotEndingWith("RepositoryCustomImpl")
                    .should().dependOnClassesThat().resideInAPackage(jpa)
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("도메인 간 의존 방향") {
        fun packageOf(context: String) = "com.kbap.common.domain.$context.."

        val allowedDomainDeps = mapOf(
            "admin" to emptySet(),
            "appversion" to emptySet(),
            "block" to emptySet(),
            "scan" to setOf("food", "member", "image", "ingredient"),
            "food" to emptySet(),
            "bookmark" to setOf("food", "member", "ingredient"),
            "member" to setOf("ingredient"),
            "image" to emptySet(),
            "metering" to emptySet(),
            "order" to emptySet(),
            "ingredient" to emptySet(),
            "review" to emptySet(),
            "report" to emptySet(),
            "community" to emptySet(),
        )

        `when`("발견된 도메인 컨텍스트 집합을 허용 맵과 대조하면") {
            then("정확히 일치한다 — 맵에 없는 컨텍스트는 방향 검사를 우회할 수 없다") {
                val foundContexts = imported
                    .filter { it.packageName.startsWith("com.kbap.common.domain.") }
                    .map {
                        it.packageName
                            .removePrefix("com.kbap.common.domain.")
                            .substringBefore(".")
                    }
                    .toSet()

                foundContexts shouldBe allowedDomainDeps.keys
            }
        }

        allowedDomainDeps.forEach { (context, allowed) ->
            val forbidden = (allowedDomainDeps.keys - allowed - context).sorted()
            `when`("$context 컨텍스트가 허용 목록 $allowed 밖 도메인에 의존하는지 검사하면") {
                then("$forbidden 을 알지 못한다") {
                    noClasses().that().resideInAPackage(packageOf(context))
                        .should().dependOnClassesThat().resideInAnyPackage(
                            *forbidden.map { packageOf(it) }.toTypedArray(),
                        )
                        .allowEmptyShould(true)
                        .check(imported)
                }
            }
        }
    }

    given("JPA 연관관계 금지") {
        `when`("엔티티 필드의 연관 애너테이션을 검사하면") {
            then("@OneToMany·@ManyToOne·@OneToOne·@ManyToMany 를 쓰지 않는다 — 참조는 id 값 컬럼") {
                noFields().that().areDeclaredInClassesThat().resideInAPackage("com.kbap..")
                    .should().beAnnotatedWith("jakarta.persistence.OneToMany")
                    .orShould().beAnnotatedWith("jakarta.persistence.ManyToOne")
                    .orShould().beAnnotatedWith("jakarta.persistence.OneToOne")
                    .orShould().beAnnotatedWith("jakarta.persistence.ManyToMany")
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("영속 소유 위치") {
        `when`("@Entity 가 붙은 클래스의 패키지를 검사하면") {
            then("모든 JPA 엔티티는 도메인 패키지에만 존재한다 — 하나도 안 잡히면 스캔 자체가 깨진 것") {
                classes().that().areAnnotatedWith("jakarta.persistence.Entity")
                    .should().resideInAPackage(sharedDomain)
                    .check(imported)
            }
        }

        `when`("Spring Data Repository 인터페이스의 패키지를 검사하면") {
            then("모든 리포지토리는 도메인 패키지에만 존재한다 — 영속은 컨텍스트 불문 :common 소속") {
                classes().that().areAssignableTo("org.springframework.data.repository.Repository")
                    .and().resideInAPackage("com.kbap..")
                    .should().resideInAPackage(sharedDomain)
                    .check(imported)
            }
        }
    }

    given("유틸 패키지(common.util) 경계") {
        `when`("유틸이 의존하는 패키지를 검사하면") {
            then("상태 없는 순수 헬퍼다 — 스프링·JPA·도메인·포트·상위 계층을 모른다") {
                noClasses().that().resideInAPackage("com.kbap.common.util..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        spring,
                        jpa,
                        sharedDomain,
                        "com.kbap.common.port..",
                        "com.kbap.common.infra..",
                        "com.kbap.api..",
                        "com.kbap.batch..",
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("어댑터 구현 조립 창구") {
        `when`("api·batch 기능 코드가 어댑터 구현 패키지를 참조하는 위치를 검사하면") {
            then("어댑터 직접 참조는 조립 config 패키지와 어댑터 자신 안에서만 한다 — 기능 코드는 common.port 계약만 본다") {
                noClasses().that().resideInAnyPackage("com.kbap.api..", "com.kbap.batch..")
                    .and().resideOutsideOfPackages(
                        "com.kbap.api.core.config..",
                        "com.kbap.api.infra..",
                        "com.kbap.batch.config..",
                        "com.kbap.batch.outbox..",
                    )
                    .should().dependOnClassesThat().resideInAnyPackage(
                        "com.kbap.common.infra..",
                        "com.kbap.api.infra..",
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("포트 패키지(common.port) 경계") {
        `when`("포트가 스프링·인프라·부트앱에 의존하는지 검사하면") {
            then("포트는 순수 계약이다 — 구현 기술을 알지 못한다") {
                noClasses().that().resideInAPackage("com.kbap.common.port..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        spring,
                        jpa,
                        "com.kbap.common.infra..",
                        "com.kbap.api..",
                        "com.kbap.batch..",
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("성분 식별자 enum 콘텐츠 데이터 없음 회귀") {
        `when`("IngredientCode 의 선언 필드를 리플렉션으로 확인하면") {
            then("개발 가독성 label 만 허용하고 콘텐츠 데이터(번역·분류 등)는 갖지 않는다") {
                val instanceFieldNames = IngredientCode::class.java.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .map { it.name }

                instanceFieldNames shouldBe listOf("label")
            }
        }
    }
})
