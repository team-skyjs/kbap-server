# 0017. API 모듈 기능 패키지 평탄화

- **상태**: Accepted
- **날짜**: 2026-07-28
- **관련**: ADR-0016, 헌법 v7.0.0

## Context

ADR-0016은 애플리케이션 모듈을 `:common`·`:api`·`:batch`로 통합하면서도 API 모듈 안에는
`com.kbap.api`·`com.kbap.domain`·`com.kbap.application` 계층 패키지를 유지했다. 그 결과 한 기능을
파악하려면 같은 모듈 안의 세 패키지를 오가야 했고, API 전용 서비스가 어느 계층인지 판단하는 비용이
모듈 통합 뒤에도 남았다.

`com.kbap.domain`의 `ScanService`·`BookmarkService`·`ImageUploadService`·`LlmCallCostService`는
API 요청, 외부 seam, 여러 공유 도메인과 영속을 조합하는 API 전용 유스케이스다.
`com.kbap.application`의 Home·Auth·Upload·FoodImage 서비스도 같은 API 앱만 소비한다.
두 분류를 별도 최상위 패키지로 유지해 얻는 컴파일 격리는 없고 탐색 경로만 늘어난다.

## Decision

`:api`의 프로덕션 코드는 진입점 `com.kbap.KbapApiApplication`을 제외하고
`com.kbap.api.<feature>` 기능 패키지에 둔다.

- `auth`·`home`·`upload`·`foodimage`의 application 서비스와 결과 타입을 대응 기능 패키지로 옮긴다.
- `scan`·`bookmark`·`image`·`metering`의 API 전용 서비스와 결과 타입을 대응 기능 패키지로 옮긴다.
- 기능당 타입 수가 적으므로 `dto`·`service` 같은 하위 계층 패키지를 만들지 않는다.
- `com.kbap.domain`과 `com.kbap.application`은 API 모듈에서 사용하지 않는다.
- `:common`의 `com.kbap.common.domain`과 `com.kbap.common.application`은 유지한다. 전자는 영속과
  공유 도메인을, 후자는 인프라 어댑터가 구현하는 seam 계약을 소유한다.
- 도메인 간 의존 방향 ArchUnit 검사는 실제 도메인 코드가 있는 `com.kbap.common.domain.<context>`만
  대상으로 한다. API 기능 패키지는 컨트롤러와 유스케이스 조합을 함께 가지므로 도메인 의존 맵에 넣지 않는다.

## Alternatives Considered

- **계층 패키지 유지** — 역할 분리는 드러나지만 같은 기능의 컨트롤러·서비스·결과 타입이 계속 흩어진다.
- **`com.kbap.api.service`·`com.kbap.api.dto`로 이동** — 최상위 이름만 바뀌고 계층별 탐색 비용은 그대로다.
- **기능 패키지 아래 `service`·`dto` 하위 패키지 추가** — 현재 기능당 파일 수에는 불필요한 깊이다.
- **API 전용 서비스도 `:common`으로 이동** — batch와 infra가 쓰지 않는 API 유스케이스가 공유 모듈을
  비대하게 만들어 ADR-0016의 배치 기준을 훼손한다.

## Consequences

- 한 기능의 HTTP 경계·유스케이스·결과 타입을 `com.kbap.api.<feature>` 한 곳에서 찾을 수 있다.
- API 전용 서비스는 도메인 패키지로 오인되지 않고 부트앱 소유 조합 코드임이 분명해진다.
- `:common`의 도메인·seam 경계와 모듈 의존 방향은 바뀌지 않는다.
- API 기능 간 참조는 ArchUnit 도메인 의존 맵이 아니라 코드 리뷰와 서비스 책임으로 관리한다.
- API 계약, DB 스키마, 트랜잭션 경계와 런타임 동작은 변경하지 않는다.
