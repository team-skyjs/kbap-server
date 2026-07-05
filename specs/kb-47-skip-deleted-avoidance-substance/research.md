# Phase 0 Research: 삭제된 기피 성분 skip 처리

명세에 `[NEEDS CLARIFICATION]` 는 없었다. 아래는 구현 방향을 고정하기 위한 설계 결정 기록이다.

## R1. skip vs 예외 — 조립 루프 처리 방식

- **Decision**: `map { … ?: throw }` 를 `partition { code in catalog }` 으로 바꿔 참조 성분을 존재/부재로 가른다. 부재(삭제) 성분은 skip(+WARN 로그)하고, 존재 성분만 `map { catalog.getValue(code) … }` 으로 조립한다. **`null` 을 반환하지 않는다** — `mapNotNull { … null }` 대신 partition 을 택해 "skip+로그"와 "조립"의 관심사를 분리하고 non-null `getValue` 로 조립한다.
- **Rationale**: 예외를 던지면 상세조회 전체가 500 으로 깨진다(현재 취약점). partition 은 한 번 순회로 부재 목록(로그 대상)과 존재 목록(조립 대상)을 동시에 확보해, 삭제 항목만 빠뜨리면서 KB-47 "장애 내성"을 달성한다. 정렬(확률 내림차순)은 상위에서 이미 정렬된 순서를 각 그룹 안에서 유지하므로 skip 후에도 보존된다. `mapNotNull` 도 동작은 같지만 루프 안 `null` 반환을 피하고 의도를 더 드러내려 partition 을 선택했다.
- **Alternatives considered**:
  - *`mapNotNull { catalog miss → null }`* — 동작 동일·간결하나 루프 안에서 `null` 을 반환해 skip 을 표현. 관심사 분리·가독성 측면에서 partition 이 우수해 채택하지 않음(사용자 선호 반영).
  - *`filter { }.map { }` 2-pass* — null 은 없으나 skip 로그를 위한 별도 순회가 필요. partition 이 상위호환.
  - *리포지토리 port 에서 DELETED 포함 조회 후 상태로 필터* — 카탈로그가 이미 ACTIVE 만 보므로 삭제 성분의 표시명·위험도를 알 수 없다. 삭제된 성분은 애초에 표시 불가이므로 "표시하지 않는다(skip)"가 옳다.
  - *도메인에서 참조 정리* — 조회 시점 정합성 흡수는 조합 계층(application) 책임(원칙 II). 도메인/DB 수정은 범위 밖(근본 차단은 별도 태스크).

## R2. skip 관측성 — WARN 로그

- **Decision**: skip 발생마다 slf4j **WARN** 로그로 `foodId` 와 `substanceCode` 를 남긴다. 예: `avoidance substance skipped (catalog missing / soft-deleted): foodId={} substanceCode={}`.
- **Rationale**: 기피/알레르기 성분이 사용자에게 조용히 사라지는 것은 안전 민감. 운영이 데이터 정합성 깨짐을 인지·복구하려면 어떤 음식의 어떤 성분이 빠졌는지 식별 가능해야 한다. WARN 은 "정상은 아니나 서비스는 계속됨" 반정합 상황에 맞는 레벨.
- **Alternatives considered**: ERROR(과함 — 조회는 성공), INFO(운영 알림 누락 위험), 메트릭만(도입된 메트릭 파이프라인 부재) → 현 시점 WARN 로그가 적정.

## R3. skip 로그 검증 방법

- **Decision**: Logback `ListAppender<ILoggingEvent>` 를 `GetFoodDetailUseCase` 로거에 부착해 테스트에서 WARN 이벤트·메시지에 `foodId`·`substanceCode` 포함을 검증한다.
- **Rationale**: 유스케이스 단위 테스트(Fake 리포지토리)에서 외부 의존 없이 로그를 결정적으로 캡처하는 표준 기법. Kotest `BehaviorSpec` 의 given/when/then 안에서 appender 부착·해제 가능.
- **Alternatives considered**: mock 로거 주입(로거를 생성자 파라미터로 노출 — 프로덕션 코드 표면 증가), 로그 미검증(DoD 항목 미충족) → ListAppender 채택.

## R4. 위험도(risk) 조립과 skip 상호작용

- **Decision**: `mockAvoidanceRiskMarker.mark(...)` 호출과 code 기준 조회(`risks[substanceCode]`)는 현행 유지. skip 은 최종 조립 단계에서만 일어나고 risk 는 code 로 룩업되므로 재인덱싱이 없다 — 생존 성분의 위험도는 기존과 동일하게 매겨진다.
- **Rationale**: 변경 표면을 조립 루프 한 곳으로 최소화해 회귀를 배제한다. risk 는 현재 mock 이며 KB-47 범위가 아니다.
- **Alternatives considered**: 생존 목록으로 risk 재계산 — 불필요한 동작 변경이며 mock 특성상 의미 없음. 배제.

## R5. 테스트 계층 선택

- **Decision**: 유스케이스 단위 테스트(`:application:client`, Fake 리포지토리)를 1차로 삼는다. 삭제 성분은 Fake `AvoidanceSubstanceRepository` 가 해당 code 를 반환하지 않는 것으로 시뮬레이션한다(실제 `@SQLRestriction` 이 ACTIVE 만 반환하는 것과 동치).
- **Rationale**: 로직 위치가 유스케이스이고, 삭제 성분의 관찰 가능한 효과(카탈로그에 code 없음)를 Fake 로 정확히 재현할 수 있다. 빠르고 결정적. web(MockMvc) 통합 테스트는 응답 계약 불변을 이미 다수 커버하므로 추가 비용 대비 이득이 낮다(필요 시 후속).
- **Alternatives considered**: H2 통합 테스트로 실제 소프트 삭제 재현 — 값지지만 본 변경의 로직은 순수 조립이라 단위 테스트로 충분. 범위 확장 회피.

**Output**: 모든 결정 확정 — 미해결 항목 없음.
