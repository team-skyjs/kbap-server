---
name: meogo-code-review
description: "meogo-server 코드 리뷰 체크리스트 — 헌법 5원칙 게이트, 멀티모듈 경계·클린아키텍처 의존 방향, 도메인 불변, 응답(BaseResponse)/경로(/api/v) 규약, 테스트 의미·커버리지·BehaviorSpec 스타일, Kotlin 주석 금지 위반을 심각도별로 검출한다. 코드 리뷰·품질 검토·머지 전 점검 시 반드시 사용."
---

# meogo 코드 리뷰

TDD 한 사이클(테스트+구현) 직후, 결과물이 헌법·컨벤션·요구사항을 충족하는지 독립 검증한다. 코드를 고치지 않고 **발견을 심각도·파일:라인·근거·제안**으로 보고한다.

## 절차

1. **그린 검증 먼저** — 변경 모듈 테스트를 직접 돌려 정말 통과하는지 확인한다. 깨져 있으면 그게 1순위 Blocker. (주장만 믿지 않는다 — 증거 우선.)
2. **변경 파일 읽기** — 테스트와 구현을 함께 본다.
3. **체크리스트 적용** — 아래 항목을 순서대로 점검.
4. **분류·보고** — `[Blocker|Major|Minor] 파일:라인 — 문제 — 근거 — 제안`.
5. **게이트 판정** — Blocker 0 이면 통과, 있으면 implementer 에 수정 요청.

## 심각도 기준

- **Blocker**: 빌드/테스트 실패, 헌법 위반, 정확성 버그, 모듈 경계 깨짐. 머지 불가.
- **Major**: 컨벤션 위반, 테스트 커버리지 구멍(경계/예외 누락), 설계 악취. 수정 필요.
- **Minor**: 네이밍·가독성·소소한 개선. 권장.

## 헌법 게이트 (5원칙)

1. **Test-First**: 테스트가 존재하고 행동을 의미 있게 검증하는가? Red→Green 이 형식적이지 않은가? 경계·예외·폴백이 덮였나(blank→400, 미수록→400, lang 미지원→ko 폴백 등)?
2. **Bounded Contexts**: 도메인 모듈이 서로 직접 의존하지 않는가? 컨텍스트 조합이 application 에만 있는가? 다른 애그리거트를 ID/스냅샷이 아닌 객체 통째로 참조하지 않는가?
3. **Layered Dependency**: `presentation→application→도메인` 단방향인가? application 이 infra/JPA 구현체를 직접 의존하지 않는가(port 인터페이스만)? 모듈 의존이 `implementation` 기본인가?
4. **Persistence Encapsulation**: JPA Entity·Spring Data·Adapter 가 `:meogo-api:persistence` 에만 있나? application/presentation 이 이를 import 하지 않는가? 도메인은 model+port 만 노출하나?
5. **Language Policy**: 음식 콘텐츠가 ko 원문 + 대상 언어 모델을 따르나? 응답이 요청 lang(미지원 시 ko 폴백)을 반환하나?

## 컨벤션 체크리스트

- **Kotlin 주석 금지**: `.kt`(main·test)에 `//`·`/* */`·`/** */` 가 하나라도 있으면 위반.
- **응답 봉투**: 컨트롤러 반환이 `ResponseEntity<BaseResponse<T>>` 인가? `ok`/`fail` 사용? raw 노출 없나?
- **경로 규약**: 모든 매핑이 `ApiPaths.V*` 상수 기반 `/api/v{n}` 으로 시작? `/api/v1` 하드코딩 없나?
- **도메인 불변**: 상태가 전부 `val`? 변경이 새 인스턴스 반환? public `copy` 노출 대신 `private fun copy`?
- **변환 위치**: 도메인↔엔티티 변환이 엔티티 안(`toDomain`/`companion from`)? 별도 Mapper 클래스 없나? 도메인이 JPA import 안 하나?
- **네이밍**: application 입출력이 `Input`/`Result`(Command/Query 아님), 도메인 생성입력 `CreationSpec`?
- **테스트 스타일**: 전부 `BehaviorSpec`, given/when/then 한국어? Spring 통합은 클래스 본문 + `SpringExtension`?
- **DTO 노출**: 도메인/영속 모델을 API 응답으로 직접 노출하지 않나(DTO 매핑)?

## 테스트 품질 점검 (Test-First 의 핵심)

- 테스트가 **구현 세부**가 아니라 **관찰 가능한 결과**를 검증하나?
- 한 then 이 한 가지를 검증하나? 행동별로 when/then 분리됐나?
- spec/contracts 의 FR·시나리오가 테스트로 빠짐없이 옮겨졌나(역추적)?
- 단언이 느슨하지 않나(`shouldNotBeNull` 만 있고 값 검증 누락 등)?

## 영역 경계

- DB 스키마·엔티티 매핑·인덱스·N+1·쿼리 성능은 **database-expert** 영역이다. 의심점은 지적 대신 SendMessage 로 공유하고 중복 리뷰하지 않는다.

## 보고 형식

```
[Blocker] presentation/.../FoodDetailController.kt:42 — raw 도메인 반환
  근거: 헌법/규약 — 컨트롤러는 ResponseEntity<BaseResponse<T>> 고정.
  제안: FoodDetailResponse 매핑 후 BaseResponse.ok(...) 로 감싸 반환.

[Major] application/.../GetFoodDetailUseCase.kt:30 — lang 미지원 폴백 테스트 없음
  근거: contracts food-detail-api — 미지원/미지정 lang 은 ko 폴백. 회귀 위험.
  제안: ko 폴백 then 추가를 test-writer 에 요청.
```

마지막에 **요약(Blocker n / Major n / Minor n)과 게이트 판정**을 명시한다.
