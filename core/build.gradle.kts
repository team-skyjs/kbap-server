// :core — 공통 타입·예외·유틸·도메인 stereotype(@AggregateRoot 마커)·외부 client port 인터페이스(seam)
// + 전 도메인이 공유하는 영속 공통(BaseEntity·EntityStatus·id 값 클래스와 AttributeConverter).
// 애플리케이션 코드는 Spring-free 다. 영속 공통만 jakarta/hibernate 애너테이션을 compileOnly 로 참조하며,
// 런타임 제공은 도메인 모듈의 data-jpa 스타터가 담당한다(ADR-0012).
// testFixtures: MySQL/Redis Testcontainers 공통 설정 — 도메인 모듈·부트앱 테스트가 공유한다.
import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

plugins {
    id("kbap.kotlin-common")
    id("io.spring.dependency-management")
    `java-test-fixtures`
}

configure<DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    }
}

dependencies {
    "compileOnly"(libs.jakarta.persistence.api)
    "compileOnly"(libs.hibernate.core)

    // dependency-management 가 testFixtures 구성엔 자동 적용되지 않아 Boot BOM 을 platform 으로 직접 얹는다.
    "testFixturesApi"(platform(libs.spring.boot.dependencies))
    "testFixturesApi"(libs.spring.boot.testcontainers)
    "testFixturesApi"(libs.testcontainers.mysql)
    "testFixturesApi"(libs.testcontainers.core)
    "testFixturesApi"(libs.spring.boot.starter.test)
    "testFixturesApi"(libs.kotest.extensions.spring)
    "testFixturesApi"(libs.kotest.runner.junit5)
}
