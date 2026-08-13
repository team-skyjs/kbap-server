# Quickstart: 스캔 2.0 — 메뉴판 아닌 사진의 빈 결과 처리

## 자동 검증

```bash
./gradlew :infra:llm:test --tests "com.kbap.infra.llm.menu.MenuBoardResultParserTest"   # 빈 배열 → 빈 목록 계약
./gradlew :api:test --tests "com.kbap.api.scan.*"                                        # Fake 기반 기존 스캔 경로
./gradlew build                                                                          # 전체 회귀
```

## 프롬프트 실효성 수동 검증 (dev)

비메뉴판 표본(풍경·셀카·영수증·간판) + 정상 메뉴판 표본으로 2.0 스캔을 호출한다:

- 비메뉴판 → `payload.items` 가 항상 `[]` (지어낸 메뉴 0건, 반복 호출에도 일관)
- 정상 메뉴판 → 변경 전과 동등한 메뉴·가격 추출

## 구현 시 주의

- 변경 파일은 `OpenAiMenuBoardVisionExtractor.kt` 의 `SERVER_OCR_SYSTEM_PROMPT` 하나다 — 1.0 `SYSTEM_PROMPT`·파서·`ScanService` 무변경.
- 규칙 문안은 위임 수준으로 최소화(research R2) — 과도한 판정 기준 열거는 정상 메뉴판 과잉 거부 위험.
- 프롬프트 문구 포함 여부를 검사하는 단위 테스트는 만들지 않는다(R3).
