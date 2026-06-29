// meogo-api:core — 공통 타입·예외·이벤트 계약·유틸·도메인 stereotype, 외부 client port 인터페이스.
// 순수 Spring-free 커널이다. 빈 등록용 stereotype(@Component 계열)은 두지 않는다 —
// 빈으로 쓸 서비스/정책은 조립·유스케이스 계층(:application)에 둔다.
plugins {
    id("meogo.kotlin-common")
}
