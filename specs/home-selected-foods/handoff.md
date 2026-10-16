# 홈 선정 음식 요약 API 인계

## 범위

GET `/api/foods/selected?ids=444,1&lang=en`은 FE가 선택한 기존 음식 ID 최대 10개를 요청 순서대로 반환한다. 예시 ID는 후보 확정 목록이 아니다. 후보 ID·고정 순서·manifest 버전은 FE/운영이 소유하고 서버에는 하드코딩하지 않는다. 상세 조회 이벤트, 스캔 이력, 수집, LLM 호출, 데이터 변경은 없다.

## 버전과 인증

기존 버전 필수 규칙을 그대로 적용한다. 이 경로는 버전별 응답을 분기하지 않으므로 새 버전 마커를 추가하지 않는다. `X-API-Version: 1.1`로 사용할 수 있고 FE 전역 버전 변경은 필요 없다. 전역 지원 버전 중 어떤 버전을 쓰더라도 동일 계약이며, 버전 누락·미지원 값은 400 COMMON-002다. 3.1 정렬 기능은 별도 PR이며 이 변경의 선행 조건이 아니다.

인증은 기존 음식 조회와 같은 선택적 Bearer 인증이다. 인증이 없으면 비회원 요약, 유효한 회원 토큰이면 개인 위험도와 북마크를 제공한다. 잘못된 Bearer 토큰은 401이며 비회원으로 우회하지 않는다. 응답에는 `Cache-Control: private, no-store`가 붙는다.

## 입력과 응답

ids는 단일 query parameter의 CSV로 양수 Long 1~10개다. 토큰 양끝 공백과 선행 영은 정규화한다. 정규화 후 중복, 빈 토큰, 0·음수, Long 범위 초과, 11개 이상, 반복 ids 파라미터는 400 COMMON-002다. lang은 필수, 미지원 값은 기존 LanguageCode 규칙대로 en이다.

성공 봉투 payload는 `items: FoodSummaryResponse[]`, `unavailableFoodIds: Long[]`이다. ACTIVE·READY만 items에 넣는다. 미존재·삭제·비공개를 구분하지 않고 unavailableFoodIds로 반환한다. 두 배열 각각 요청의 상대 순서를 유지하며 합집합은 요청 ID 전체다. 모두 사용할 수 없어도 200과 빈 items를 반환한다.

기존 요약 필드 foodId/name/koreanName/imageRef/spiciness/overallRiskStatus/publishedAt/bookmarked/review를 그대로 사용한다. 홈 필터는 반환된 동일 선정 집합 안에서 수행하며 다른 음식으로 보충하지 않는다.

```json
{"success":true,"payload":{"items":[],"unavailableFoodIds":[999,998]},"message":null,"code":null}
```

```json
{"success":false,"payload":null,"message":"잘못된 요청입니다","code":"COMMON-002"}
```

## 구현과 배포 경계

SelectedFoodsService의 읽기 전용 트랜잭션에서 음식 일괄 조회, 회피 성분 조회, 북마크 일괄 조회, 리뷰 일괄 집계를 수행한다. 기존 상세 조회나 홈 무작위 조회를 호출하지 않는다. 기존 내부 getFoodSummariesInOrder 계약은 변경하지 않는다. DB 스키마·Flyway 변경은 없다.

이 문서는 로컬 구현 계약이다. dev/prod 실제 배포 확인 전에는 연결 완료나 운영 제공으로 표기하지 않는다. 배포 후 dev Swagger 1.1 문서의 selected 경로와 실제 응답을 대조해야 한다. prod는 이 API 변경만 별도 최소 backport 대상으로 검토하며 #404 업로드 브리지나 전체 develop 배포를 전제로 하지 않는다. AWS 변경은 사용자 CloudShell 경유다.

## 검증

2026-10-16 로컬 `./gradlew build` 성공(4분 34초). api 2,125개(2,123 통과·2 스킵), common 646개, batch 93개: 총 2,864개, 실패 0. 새 SelectedFoodsControllerTest 31개 전부 통과. MySQL·Redis Testcontainers와 실제 Spring MVC/필터/Swagger를 사용하는 MockMvc 검증이며 외부 dev/prod HTTP 검증을 뜻하지 않는다.

1·10개 입력, 11개·중복·빈 토큰·범위 초과·반복 파라미터, 공개/삭제/비공개/누락 상대 순서, 두 회원의 위험도·북마크 격리, 리뷰 평균, FoodViewed 이벤트 0·조회/스캔 이력 0, 음식 1개와 10개 조회의 쿼리 수 동일을 검증했다. develop에서 1.0·1.1·1.2·1.3·2.0·3.0, 헤더 누락/99.0의 400, 잘못된 Bearer의 401 및 1.1 생성 Swagger를 검증했다. 기존 목록·검색·상세·홈·OpenAPI·ArchUnit 회귀도 전체 빌드에 포함된다.

기본 테스트 Red(경로 없음)→Green을 확인한 뒤 확장 검증을 진행했다. 배열 assertion과 필수 시드 시각을 보정하여 최종 전체 빌드가 통과했다. 실제 후보 prod 데이터 읽기·실제 배포·FE 연동 QA는 별도 단계다.

### prod backport 주의

현재 origin/main과 develop을 비교하면 main에는 전역 지원 버전 추가(1.2·1.3·3.0)와 마케팅 인기 목록 구현이 없다. 따라서 develop 검증의 지원 버전 목록을 prod에 그대로 적용했다고 주장하면 안 된다. prod 최소 backport는 기존 main의 전역 지원 버전(1.0·1.1·2.0)과 1.1 요청을 유지하고, selected에 필요한 새 요약 메서드만 추가한다. FoodService의 기존 마케팅 조회·캐시 변경이나 WebConfig의 버전 추가를 함께 옮길 이유가 없다. 실제 main 기반 cherry-pick 및 테스트는 별도 검증 대상이다.
