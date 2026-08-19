# Research: 스캔 2.0 similarFood 제거

## R1. 제거 범위 — 소비 코드만, 벡터 인프라 존치

- **Decision**: 삭제는 `SimilarFoodResolver` 와 스캔 응답·조립의 similarFood 흔적 전부. `common.port.llm.TextEmbeddingClient`·`common.domain.food.vector.FoodVectorSearcher`(seam·구현·조립 config)와 KB-328 적재 파이프라인(아웃박스·배치·DocumentDB 동기화)은 **무변경 존치**.
- **Rationale**: 명확화 확정 — 향후 검색 기능 재사용 여지. 스캔이 유일한 검색 소비자라 resolver 삭제 후 검색 seam 은 미사용 상태가 되지만, 미사용 빈은 무해하고 운영 정리(DocumentDB 축소)는 별도 이슈.
- **Alternatives considered**: 파이프라인 동반 제거 — 범위·운영 영향 커져 기각(별도 이슈 권장).

## R2. `similarFoodFallback` 플래그의 이중 역할 분리

- **Decision**: `ScanService.scan` 의 `similarFoodFallback` 플래그는 (1) 유사 음식 검색 게이트, (2) **v2 의 빈 추출 400(MENU_BOARD_NOT_DETECTED) 게이트** 두 역할을 겸한다. (1)은 제거하고 (2)는 **유지** — 플래그를 `requireDetectedMenu`(v2=true) 로 개명해 남긴다.
- **Rationale**: 빈 추출 400 은 similarFood 와 무관한 v2 계약(비메뉴판 사진 거절, KB-330)이다 — 이름만 유사 음식에 묶여 있었다.
- **Alternatives considered**: 플래그 통째 제거 — v2 의 400 계약이 사라져 회귀. 기각.

## R3. 응답·문서 정리 지점

- **Decision**: `ScanResult.SimilarFood`·`ItemRiskResult.similarFood`, `ScanV2Response.SimilarFoodResponse`·`ItemRiskResponse.similarFood`·`from` 조립, `ScanService.resolveSimilarFoods`/`toSimilarFood`, `SimilarFoodResolver` 를 삭제. swagger 문구(`ScanV2Api`·`ItemRiskResponse.avoidances` description 의 "similarFood 성분으로 대체 판정하지 않음" 구절 포함)를 함께 정리.
- **Rationale**: JSON 직렬화에서 필드가 사라지므로(SC-001) 문서에 잔존 언급이 남으면 거짓 문서가 된다.

## R4. 계약 적용 방식

- **Decision**: 2.0 매핑(`version = "2.0+"`) 안에서 즉시 적용 — 버전 증가·경로 변경 없음. 파괴적 필드 제거지만 스캔 2.0 클라이언트와 조율된 변경(스펙 가정).
- **Rationale**: KB-334 선례(조율된 계약 변경은 무버전 즉시 적용). similarFood 는 nullable 필드라 필드 소멸이 파싱을 깨지 않는 클라이언트가 대부분이기도 하다.

## R5. 테스트 전략

- **Decision**: 기존 `ScanControllerTest` 의 similarFood 시나리오를 "필드 부재" 검증으로 전환(Red: `similarFood` 가 응답에 존재하지 않음 + 비매칭 항목의 이름·가격·UNKNOWN 유지 + 검색 미호출). v2 빈 추출 400·v1 무변경·매칭 항목 회귀는 기존 테스트 유지로 커버.
- **Rationale**: 제거 기능의 Red 는 "없어야 할 것이 아직 있음"으로 표현된다.
