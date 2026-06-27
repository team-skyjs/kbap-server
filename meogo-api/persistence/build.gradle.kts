// 영속 공통 — 모든 도메인 엔티티가 상속하는 BaseEntity(@MappedSuperclass) 를 둔다.
// core/common 은 Spring-free 라 JPA 를 둘 수 없고, 도메인끼리는 서로 의존하지 않으므로 공유 모듈로 분리한다.
plugins {
    id("meogo.spring-conventions")
    id("org.jetbrains.kotlin.plugin.jpa")
}

dependencies {
    "implementation"(libs.spring.boot.starter.data.jpa)
}
