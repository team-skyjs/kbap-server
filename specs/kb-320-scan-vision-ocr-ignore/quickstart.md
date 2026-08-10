# Quickstart: kb-320 스캔 비전 모델 교체 및 사진 단독 판독

## 1. 결정적 검증 (CI — 반드시 통과)

```bash
# 프롬프트 계약 + 파서 + 비용 이벤트
./gradlew :infra:llm:test --tests "com.kbap.infra.llm.menu.*"

# 스캔 HTTP 계약 회귀 (기존 테스트 수정 없이 통과해야 한다 — contracts/scan-api.md)
./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"

# 전체
./gradlew build
```

`./gradlew build` 는 MySQL Testcontainers 를 띄운다(Docker 필요).

## 2. 로컬 실행

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

vision 빈은 `kbap.llm.vision.enabled=true` 가 기본이라 **`VISION_API_KEY` 가 없으면 부팅에 실패한다**(의도된 fail-fast). 스캔을 쓰지 않을 때는 `VISION_ENABLED=false`.

필요 환경변수: `VISION_API_KEY`(OpenAI 키), `CDN_BASE_URL`(모델이 사진을 fetch 할 도메인).

## 3. 실 API 스모크 — 파라미터 호환·토큰·지연 실측 (배포 전 1회, 수동)

research R2·R3 를 닫는 단계다. **이걸 건너뛰고 배포하면 첫 스캔에서 죽을 수 있다.**

```bash
VISION_API_KEY=<real> CDN_BASE_URL=<real> SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
# 다른 터미널에서 실제 메뉴판 사진 경로로 1회 호출
curl -X POST "http://localhost:8080/api/v1/scans?lang=ko" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"imagePath":"<업로드된 오브젝트 path>","items":[{"idx":0,"rawMenuName":"김치찌개"}]}'
```

확인 항목:

| 항목 | 어디서 | 통과 기준 |
|------|--------|-----------|
| `temperature` 미전송으로 400 이 안 남 | 응답 상태 | 200 (503 SCAN-002 면 서버 로그의 원인 확인) |
| `response_format=json_object` 호환 | 응답 상태 + 파싱 성공 | 200 + `results` 채워짐. 실패 시 research R2 의 폴백(responseFormat 조건부 해제) |
| 출력 토큰 증가폭 | 앱 로그 `vision 토큰 사용량 ... completionTokens=` | 추론 토큰 포함값 기록 |
| 지연 | 호출 왕복 시간 | p50 8초 이내 |
| 비용 | 로그 `costUsd=` / `costKrw=` | 현행(gpt-4o-mini) 대비 5배 이내 |
| 모델명 기록 | `llm_call_cost.model_name` | `gpt-5.6-luna` 계열 |

지연·비용이 기준을 넘으면 **머지하지 말고** `VisionProps.reasoningEffort` 추가를 후속 작업으로 연다(research R3).

## 4. 수동 정확도 대조 (SC-001~SC-003, 배포 전 1회)

검증용 메뉴판 사진 3~5장에 대해 같은 사진을 **두 번** 호출한다.

- **A: 정확한 OCR** — 사진 그대로의 메뉴명
- **B: 오염된 OCR** — 오탈자 주입(`돼지불고기`→`되지불고기`, `삼겹살`→`삼겹사`) + 사진에 없는 항목 1개 추가 + 실제 메뉴 1개 누락

| 확인 | 통과 기준 | 대응 |
|------|-----------|------|
| A/B 의 `name`·`koreanName`·`price` 집합 | **동일** | SC-001 |
| B 에서 누락시킨 메뉴 | 결과에 **포함**(`idx` 는 null) | SC-002, FR-002 |
| B 에 주입한 가짜 항목 | 결과에 **없음** | FR-003 |
| B 의 오탈자 | 사진 기준으로 교정됨 | SC-003 |
| 응답 내 `idx` 중복 | 0건 | SC-004, FR-005 |

이 표를 채운 결과를 PR 본문에 붙인다 — 자동화 회귀 스위트가 없어 이것이 유일한 정확도 근거다.

## 5. 롤백

`api/src/main/resources/application.yml` 의 vision 블록만 되돌리면 모델·단가가 즉시 복구된다(재배포 필요). 프롬프트 변경은 코드라 되돌리려면 revert 가 필요하지만, 옛 모델 + 새 프롬프트 조합도 동작은 한다 — 모델 문제와 프롬프트 문제를 분리해 진단할 수 있다.
