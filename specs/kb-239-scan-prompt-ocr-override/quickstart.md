# Quickstart: 메뉴판 스캔 인식 지시 개선 (kb-239)

**Feature**: kb-239-scan-prompt-ocr-override

## 변경 파일 (1개)

| 파일 | 변경 |
|------|------|
| `infra/llm/src/main/kotlin/com/kbap/infra/llm/menu/OpenAiMenuBoardVisionExtractor.kt` | `SYSTEM_PROMPT` 에 오타 고지 + 사진 판독 우선 규칙 추가, "결과는 사진 기준" 명시. `userPromptWith` 의 OCR 목록 안내문을 참고용 표현으로 정렬 |

**신규 테스트 없음** — 프롬프트 문자열 상수 변경이라 자동 검증할 로직이 없다(research.md Decision 5).

## 작업 순서

1. `SYSTEM_PROMPT` 규칙 목록에 문장을 추가하고 유저 메시지 안내문을 정렬한다. `matchedIdx`·비메뉴 제외·price 규칙 문장은 건드리지 않는다.

2. 회귀 가드 — 기존 테스트가 무수정 통과하는지 확인한다.

   ```bash
   ./gradlew build
   ```

3. 아래 실사진 수동 검증으로 개선 효과를 확인한다(이 변경의 실질적 검증).

## 실사진 수동 검증 (SC-001~SC-003)

단위 테스트는 지시 전달만 보장한다. 품질은 아래로 확인하고 결과를 KB-239 에 코멘트로 남긴다.

1. `SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun` (OpenAI 키·`kbap.llm.*` 설정 필요)
2. 이미 업로드된 메뉴판 사진 경로로 `POST /api/v1/scans?lang=en` 호출 — 요청 `items` 의 `rawMenuName` 중 일부를 **의도적으로 오타**로 바꿔 보낸다(예: `김치찌개` → `김치피개`).
3. 확인 항목
   - 오타 항목의 응답 `koreanName` 이 사진 표기(`김치찌개`)로 나오는가 (SC-001)
   - 그 항목이 `matched=true` 로 위험도가 판정되는가 (SC-002)
   - 오타 없이 보낸 요청의 결과가 변경 전과 동일한가 (SC-003)
   - 상호·원산지·영업시간 등 노이즈 항목이 결과에 없는가 (SC-004)
   - 응답 필드 구성이 이전과 동일한가 (SC-005)

Swagger UI 의 `ScanApi` 예시("한식마당 메뉴판 (노이즈 포함)")가 오타·노이즈를 이미 포함하고 있어 그대로 재현용으로 쓸 수 있다.

## 검증 포인트

- 전 모듈 테스트가 무수정으로 통과해야 한다 — seam 시그니처·DTO 가 그대로이므로 컴파일·계약 영향이 없다.
- `MenuBoardResultParser` 무접촉 — 응답 JSON 형식이 바뀌지 않는다.
