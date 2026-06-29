// ───────── 도메인 컨텍스트 모듈 공통 ─────────
// food/member/scan/assessment/research/review 가 동일하게 갖는 설정을 한곳에 모은다.
// 클린아키텍처: 도메인은 모델 + port(인터페이스) + 도메인 서비스/정책만 갖는다.
// 영속 기술(JPA/Mongo)은 도메인에 두지 않고 바깥 :infra:persistence 가 도메인을 의존해 구현한다.
// - 도메인은 순수 Spring-free 로 둔다. 빈 등록이 필요한 서비스/정책(@Service/@Component)은
//   도메인이 아니라 조립·유스케이스 계층(:application)에 둔다.
// - core 는 도메인 공개 API 에 드러나므로 api() 로 전이 노출한다.
plugins {
    id("meogo.kotlin-common")
}

dependencies {
    "api"(project(":core:kernel"))
}
