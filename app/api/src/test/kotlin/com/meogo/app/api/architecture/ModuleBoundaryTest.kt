package com.meogo.app.api.architecture

import com.meogo.domain.avoidance.AvoidanceSubstanceCode
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMembers
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
            .importPackages("com.meogo")

    val core = "com.meogo.core.."
    val anyDomain = "com.meogo.domain.."
    val domains =
        listOf(
            "com.meogo.domain.food..",
            "com.meogo.domain.member..",
            "com.meogo.domain.avoidance..",
            "com.meogo.domain.scan..",
            "com.meogo.domain.research..",
            "com.meogo.domain.review..",
        )
    val spring = "org.springframework.."
    val jpa = "jakarta.persistence.."
    val associationAnnotations =
        listOf(
            "jakarta.persistence.OneToMany",
            "jakarta.persistence.ManyToOne",
            "jakarta.persistence.OneToOne",
            "jakarta.persistence.ManyToMany",
        )

    given("커널 모듈(:core) 경계") {
        `when`("커널이 의존하는 패키지를 검사하면") {
            then("스프링·도메인·상위 계층에 의존하지 않는다") {
                noClasses().that().resideInAPackage(core)
                    .should().dependOnClassesThat().resideInAnyPackage(
                        spring,
                        anyDomain,
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

    given("도메인 모듈(:domain:*) 경계") {
        `when`("도메인이 상위 계층(application·infra·app·common)에 의존하는지 검사하면") {
            then("상위 계층을 알지 못한다") {
                noClasses().that().resideInAPackage(anyDomain)
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

        `when`("영속 애너테이션 없는 도메인 클래스(모델·정책·값 객체)가 jakarta.persistence 에 의존하는지 검사하면") {
            then("도메인 모델은 ORM-free 다 — 영속 접근은 엔티티·리포지토리·도메인 서비스(@Service)만 허용") {
                noClasses().that().resideInAPackage(anyDomain)
                    .and().areNotAnnotatedWith("jakarta.persistence.Entity")
                    .and().areNotAnnotatedWith("jakarta.persistence.MappedSuperclass")
                    .and().areNotAnnotatedWith("jakarta.persistence.Embeddable")
                    .and().areNotAnnotatedWith("jakarta.persistence.Converter")
                    .and().areNotAnnotatedWith("org.springframework.stereotype.Service")
                    .and().areNotAssignableTo("org.springframework.data.repository.Repository")
                    .should().dependOnClassesThat().resideInAPackage(jpa)
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("영속 엔티티 위치") {
        `when`("@Entity 가 붙은 클래스의 패키지를 검사하면") {
            then("모든 JPA 엔티티는 도메인 모듈에만 존재한다") {
                classes().that().areAnnotatedWith("jakarta.persistence.Entity")
                    .should().resideInAPackage(anyDomain)
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("JPA 연관관계 금지") {
        associationAnnotations.forEach { annotation ->
            `when`("$annotation 사용을 검사하면") {
                then("엔티티 간 연관관계 애너테이션은 전면 금지다 — 참조는 id 값으로만 든다") {
                    noMembers().should().beAnnotatedWith(annotation)
                        .allowEmptyShould(true)
                        .check(imported)
                }
            }
        }
    }

    given("컨트롤러 경로 규약") {
        `when`("@RestController 클래스의 @RequestMapping 을 검사하면") {
            then("모든 컨트롤러 매핑은 /api/v 로 시작한다") {
                val declareApiVersionedMapping =
                    object : ArchCondition<JavaClass>("클래스 레벨 @RequestMapping 이 /api/v 로 시작한다") {
                        override fun check(item: JavaClass, events: ConditionEvents) {
                            val mapping = item.annotations.firstOrNull {
                                it.rawType.name == "org.springframework.web.bind.annotation.RequestMapping"
                            }
                            if (mapping == null) {
                                events.add(SimpleConditionEvent.violated(item, "${item.name} 에 클래스 레벨 @RequestMapping 이 없다"))
                                return
                            }
                            val paths = listOf("value", "path")
                                .mapNotNull { mapping.get(it).orElse(null) }
                                .flatMap { raw -> (raw as? Array<*>)?.filterIsInstance<String>() ?: emptyList() }
                            if (paths.isEmpty() || paths.any { !it.startsWith("/api/v") }) {
                                events.add(SimpleConditionEvent.violated(item, "${item.name} 매핑 $paths 가 /api/v 로 시작하지 않는다"))
                            }
                        }
                    }

                classes().that().resideInAPackage("com.meogo.app.api..")
                    .and().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .should(declareApiVersionedMapping)
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("부트앱 모듈(app:api) 경계") {
        `when`("app:api 가 도메인 내부에 의존하는지 검사하면") {
            then("도메인 모듈을 직접 import 하지 않는다") {
                noClasses().that().resideInAPackage("com.meogo.app.api..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        anyDomain,
                        "com.meogo.infra.persistence..",
                    )
                    .allowEmptyShould(true)
                    .check(imported)
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

    given("공유 모듈(common) 경계") {
        `when`("common 이 도메인·상위 계층·스프링·JPA 에 의존하는지 검사하면") {
            then("가볍게 유지되어 어떤 계층도 알지 못한다") {
                noClasses().that().resideInAPackage("com.meogo.common..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        core,
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

    given("성분 식별자 enum 콘텐츠 데이터 없음 회귀") {
        `when`("AvoidanceSubstanceCode 의 선언 필드를 리플렉션으로 확인하면") {
            then("개발 가독성 label 만 허용하고 콘텐츠 데이터(번역·분류 등)는 갖지 않는다") {
                val instanceFieldNames = AvoidanceSubstanceCode::class.java.declaredFields
                    .filterNot { Modifier.isStatic(it.modifiers) }
                    .map { it.name }

                instanceFieldNames shouldBe listOf("label")
            }
        }
    }
})
