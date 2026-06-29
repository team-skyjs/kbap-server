// core:kernel — 공통 타입·예외·이벤트 계약·유틸·도메인 stereotype(@AggregateRoot 마커)·외부 client port 인터페이스.
// 순수 Spring-free 커널이다. stereotype 은 @Component 메타가 아닌 순수 마커라 spring-context 가 필요 없다(kotlin-common 만).
// 빈으로 쓸 서비스/정책은 조립·유스케이스 계층(:application:*)에 둔다.
plugins {
    id("meogo.kotlin-common")
}
