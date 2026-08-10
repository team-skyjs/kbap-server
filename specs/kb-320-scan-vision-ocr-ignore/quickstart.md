# Quickstart: kb-320 스캔 v2 경로 분리 · 비전 모델 교체 · LLM 정리

## 1. 결정적 검증 (CI — 반드시 통과)

```bash
# 스캔 v1 회귀 + v2 신규 시나리오
./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"

# vision 프롬프트 분기 · 파서 · 비용 이벤트
./gradlew :infra:llm:test

# 전체
./gradlew build
```

`./gradlew build` 는 MySQL Testcontainers 를 띄운다(Docker 필요).

**v1 회귀 판정**: `git diff origin/develop...HEAD -- api/src/test/.../ScanControllerTest.kt` 에서 기존 v1 블록이 **추가만 있고 기대값 수정이 없어야** 한다.

## 2. 로컬 실행

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

스캔은 필수 기능이라 `kbap.llm.vision.enabled: true` 고정 — **`OPENAI_API_KEY` 가 없으면 부팅에 실패한다**(의도된 fail-fast).

필요 환경변수: `OPENAI_API_KEY`, `IMAGE_PUBLIC_BASE_URL`(vision 이 사진을 fetch 할 CDN 베이스 — `kbap.storage.public-base-url` 을 그대로 참조하므로 vision 전용 변수는 없다).

v2 의 유사 음식 폴백까지 보려면 `EMBEDDING_ENABLED=true`, `VECTOR_ENABLED=true` + `VECTOR_DB_URI` 가 추가로 필요하다(미설정이면 폴백만 조용히 생략되고 스캔은 정상).

## 3. 실 API 스모크 — 배포 전 1회, 수동

**이걸 건너뛰고 배포하면 첫 스캔에서 죽을 수 있다.**

```bash
OPENAI_API_KEY=<real> IMAGE_PUBLIC_BASE_URL=<real> SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun

# v2 — 사진 경로만
curl -X POST "http://localhost:8080/api/v2/scans?lang=ko" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"imagePath":"<업로드된 오브젝트 path>"}'

# v1 — 종전 계약 회귀
curl -X POST "http://localhost:8080/api/v1/scans?lang=ko" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"imagePath":"<path>","items":[{"idx":0,"rawMenuName":"김치찌개"}]}'
```

확인 항목:

| 항목 | 어디서 | 통과 기준 |
|------|--------|-----------|
| `image-base-url` 플레이스홀더 해석 | 부팅 로그·요청 URL | `${kbap.storage.public-base-url}` 참조는 **api 테스트가 이 yml 을 안 읽어 CI 로 검증되지 않는다** — 부팅 성공 + 이미지 URL 이 CDN 도메인으로 나가는지 확인 |
| `temperature: 1.0` 수용 | 응답 상태 | 200 (503 SCAN-002 면 서버 로그의 원인 확인) |
| `response_format=json_object` 호환 | 파싱 성공 | `results` 채워짐. 실패 시 research R2 폴백 |
| 출력 토큰 증가폭 | 로그 `completionTokens=` | 추론 토큰 포함값 기록 |
| 지연 | 왕복 시간 | p50 8초 이내 |
| 비용 | 로그 `costUsd=` / `costKrw=` | 현행(gpt-4o-mini) 대비 5배 이내 |
| 모델명 기록 | `llm_call_cost.model_name` | `gpt-5.6-luna` 계열 |
| v1 회귀 | v1 응답 | 필드 구성 종전과 동일, `similarFood` 없음 |

지연·비용이 기준을 넘으면 **머지하지 말고** `VisionProps.reasoningEffort` 추가를 후속 작업으로 연다(research R3).

## 4. 수동 정확도 대조 (배포 전 1회)

메뉴판 사진 3~5장으로 **v1 과 v2 를 나란히** 호출해 비교한다.

| 확인 | 통과 기준 |
|------|-----------|
| v2 결과가 v1 대비 메뉴를 덜 놓치는가 | 커버리지 하락 없음 |
| v1 에서 OCR 오탈자가 결과에 남는 사례가 v2 에서 교정되는가 | 개선 확인(SC-001 의 정성 근거) |
| v2 응답에 `idx` 필드가 없는가 | 없음 |
| v1 응답에 `similarFood` 필드가 없는가 | 없음 |
| v1 응답 내 `idx` 중복 | 0건 |

기기 OCR 품질 영향은 v2 에서 구조적으로 0 이다(`items` 를 안 받는다) — 별도 오염 실험이 필요 없다.

이 표를 채운 결과를 PR 본문에 붙인다.

## 5. 롤백

- **모델만 되돌리기**: `application.yml` 의 `kbap.llm.vision.model`·`pricing`·`temperature` 복원(재배포).
- **v2 만 내리기**: `ScanV2Controller` 를 제거해도 v1 은 영향받지 않는다 — 두 경로가 서비스 진입점만 공유하고 DTO·컨트롤러가 분리돼 있다.
- **전체 되돌리기**: 브랜치 revert. v1 은 develop(KB-319) 이전 상태와 동일하므로 클라이언트 영향이 없다.
