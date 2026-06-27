import org.gradle.api.artifacts.VersionCatalogsExtension

// ───────── 도메인 컨텍스트 모듈 공통 ─────────
// food/member/scan/assessment/review 가 동일하게 갖는 설정을 한곳에 모은다.
// - core 는 도메인 공개 API 에 드러나므로 api() 로 전이 노출
// - 영속 기술(jpa/mongo)은 implementation 으로 숨겨 상위 컴파일 클래스패스로 새지 않게 한다
plugins {
    id("meogo.spring-conventions")
    // JPA no-arg: @Entity/@Embeddable/@MappedSuperclass 에 합성 no-arg 생성자를 부여한다
    // (엔티티 프로퍼티에 기본값을 강제하지 않아도 Hibernate 가 인스턴스화 가능).
    id("org.jetbrains.kotlin.plugin.jpa")
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "api"(project(":meogo-api:core"))

    // BaseEntity(@MappedSuperclass) 공유 — 엔티티가 상속한다. infrastructure 내부에서만 쓰므로 implementation 으로 은닉.
    "implementation"(project(":meogo-api:persistence"))

    "implementation"(libs.findLibrary("spring-boot-starter-data-jpa").get())
    "implementation"(libs.findLibrary("spring-boot-starter-data-mongodb").get())

    "runtimeOnly"(libs.findLibrary("mysql-connector").get())
    "testRuntimeOnly"(libs.findLibrary("h2").get())
}
