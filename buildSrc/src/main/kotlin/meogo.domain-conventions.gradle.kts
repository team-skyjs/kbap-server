import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.artifacts.VersionCatalogsExtension

// ───────── 도메인 컨텍스트 모듈 공통 ─────────
// food/member/scan/assessment/research/review 가 동일하게 갖는 설정을 한곳에 모은다.
// 클린아키텍처: 도메인은 모델 + port(인터페이스) + 도메인 서비스/정책만 갖는다.
// 영속 기술(JPA/Mongo)은 도메인에 두지 않고 바깥 :meogo-api:persistence 가 도메인을 의존해 구현한다.
// - core 는 도메인 공개 API 에 드러나므로 api() 로 전이 노출
// - Spring 결합은 stereotype(@Component 계열) 한정으로만 연다: 도메인 서비스/정책 빈 표시를 위해
//   spring-context 를 compileOnly 로만 의존한다(런타임 제공은 조립 모듈 :meogo-api:presentation).
//   web/jpa/tx 스타터는 끌어오지 않으며, 버전은 Boot BOM 으로만 관리한다.
// - kotlin-spring(all-open) 컴파일러 플러그인: @Component 메타(@DomainService 포함) 클래스를 open 으로.
//   순수 컴파일타임 플러그인(런타임 의존 0) — IDE 경고 제거 + 향후 프록시 안전.
plugins {
    id("meogo.kotlin-common")
    id("io.spring.dependency-management")
    id("org.jetbrains.kotlin.plugin.spring")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
val springBootVersion = libs.findVersion("spring-boot").get().requiredVersion

configure<DependencyManagementExtension> {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
    }
}

dependencies {
    "api"(project(":meogo-api:core"))
    "compileOnly"(libs.findLibrary("spring-context").get())
}
