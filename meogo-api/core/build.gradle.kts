import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension

// meogo-api:core — 공통 타입·예외·이벤트 계약·유틸·도메인 stereotype, 외부 client port 인터페이스.
// Spring 결합은 @DomainService 같은 stereotype(@Component 메타) 컴파일에 한해 spring-context 를
// compileOnly 로만 의존한다(런타임 제공은 조립 모듈 presentation, runtime 전이 없음). 버전은 Boot BOM 관리.
plugins {
    id("meogo.kotlin-common")
    id("io.spring.dependency-management")
}

configure<DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}")
    }
}

dependencies {
    "compileOnly"(libs.spring.context)
}
