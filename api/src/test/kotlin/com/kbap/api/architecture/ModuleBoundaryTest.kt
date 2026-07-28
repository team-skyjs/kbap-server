package com.kbap.api.architecture

import com.kbap.common.domain.avoidance.model.AvoidanceSubstanceCode
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
    val apiDomain = "com.kbap.domain.."
    val spring = "org.springframework.."
    val jpa = "jakarta.persistence.."

    given("커널 패키지(common.core) 경계") {
        `when`("커널이 의존하는 패키지를 검사하면") {
            then("스프링·도메인·상위 계층에 의존하지 않는다") {
                noClasses().that().resideInAPackage(core)
                    .should().dependOnClassesThat().resideInAnyPackage(
                        spring,
                        sharedDomain,
                        apiDomain,
                        "com.kbap.common.application..",
                        "com.kbap.application..",
                        "com.kbap.infra..",
                        "com.kbap.api..",
                        "com.kbap.batch..",
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("도메인 패키지 경계") {
        `when`("도메인이 상위 계층(application·infra·부트앱)에 의존하는지 검사하면") {
            then("상위 계층을 알지 못한다") {
                noClasses().that().resideInAnyPackage(sharedDomain, apiDomain)
                    .should().dependOnClassesThat().resideInAnyPackage(
                        "com.kbap.common.application..",
                        "com.kbap.application..",
                        "com.kbap.infra..",
                        "com.kbap.api..",
                        "com.kbap.batch..",
                    )
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }

        `when`("영속 애너테이션 없는 도메인 클래스(모델·정책·값 객체)가 jakarta.persistence 에 의존하는지 검사하면") {
            then("도메인 모델은 ORM-free 다 — 도메인 안에서 jakarta.persistence 는 엔티티·리포지토리·도메인 서비스(@Service)만 만진다") {
                noClasses().that().resideInAnyPackage(sharedDomain, apiDomain)
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

    given("도메인 간 의존 방향") {
        // 한 컨텍스트는 두 모듈에 걸친다 — 엔티티·리포지토리는 common, 서비스는 api
        fun packagesOf(context: String) =
            listOf("com.kbap.common.domain.$context..", "com.kbap.domain.$context..")

        val allowedDomainDeps = mapOf(
            "scan" to setOf("food", "member", "image", "avoidance"),
            "food" to setOf("member", "avoidance"),
            "bookmark" to setOf("food", "member", "avoidance"),
            "member" to setOf("avoidance"),
            "image" to emptySet(),
            "metering" to emptySet(),
            "avoidance" to emptySet(),
        )

        `when`("발견된 도메인 컨텍스트 집합을 허용 맵과 대조하면") {
            then("정확히 일치한다 — 맵에 없는 컨텍스트는 방향 검사를 우회할 수 없다") {
                val foundContexts = imported
                    .filter {
                        it.packageName.startsWith("com.kbap.common.domain.") ||
                            it.packageName.startsWith("com.kbap.domain.")
                    }
                    .map {
                        it.packageName
                            .removePrefix("com.kbap.common.domain.")
                            .removePrefix("com.kbap.domain.")
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
                    noClasses().that().resideInAnyPackage(*packagesOf(context).toTypedArray())
                        .should().dependOnClassesThat().resideInAnyPackage(
                            *forbidden.flatMap { packagesOf(it) }.toTypedArray(),
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

    given("영속 엔티티 위치") {
        `when`("@Entity 가 붙은 클래스의 패키지를 검사하면") {
            then("모든 JPA 엔티티는 도메인 패키지에만 존재한다") {
                classes().that().areAnnotatedWith("jakarta.persistence.Entity")
                    .should().resideInAnyPackage(sharedDomain, apiDomain)
                    .allowEmptyShould(true)
                    .check(imported)
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

                classes().that().resideInAPackage("com.kbap.api..")
                    .and().areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                    .should(declareApiVersionedMapping)
                    .allowEmptyShould(true)
                    .check(imported)
            }
        }
    }

    given("유스케이스·seam 패키지(application) 경계") {
        `when`("application 이 인프라·부트앱에 의존하는지 검사하면") {
            then("infra·부트앱 구현에 직접 의존하지 않는다") {
                noClasses().that().resideInAnyPackage("com.kbap.application..", "com.kbap.common.application..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        "com.kbap.infra..",
                        "com.kbap.api..",
                        "com.kbap.batch..",
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
