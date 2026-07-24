# Phase 1 Data Model: kb-239-scan-prompt-ocr-override

**구조 변경 없음.** 신규 엔티티·필드·컬럼·마이그레이션이 없고 기존 타입의 시그니처도 그대로다. 바뀌는 것은 **`ExtractedMenu.name`·`koreanName` 값의 출처(권위)** 뿐이며, 아래는 그 의미 변화를 기록한다.

## 관련 타입 (전부 기존 그대로)

| 타입 | 위치 | 이번 변경 |
|------|------|-----------|
| `OcrItem(idx, rawMenuName)` | `:core` `com.kbap.core.scan` | 구조 무변경. **역할만 축소** — "메뉴명의 근거"가 아니라 "결과를 박스에 잇는 매칭 힌트"(FR-003). |
| `ExtractedMenu(name, koreanName, priceKrw, matchedIdx)` | `:core` `com.kbap.core.scan` | 구조 무변경. `name`·`koreanName` 의 출처가 **사진 판독으로 확정**된다(FR-002). |
| `MenuBoardVisionExtractor.extract(imagePath, ocrItems)` | `:core` seam | 시그니처 무변경. |
| `ScanResult.ItemRiskResult(idx, riskLevel, matched, foodId, name, koreanName, price)` | `:domain:scan` dto | 무변경. |
| `ScanRequest` / `ScanResponse` | `:app:api` | 무변경(FR-005 — 필드 변경 0건). |
| `ScanHistory` | `:domain:scan` model | 무변경. 저장되는 `menuName`·`koreanName` 값의 품질만 개선된다. |

## 값 의미 변화

- **변경 전**: `ExtractedMenu.name` 은 사진 표기를 따르되, 프롬프트가 OCR 텍스트를 신뢰 가능한 참고로 제시해 오타가 그대로 실릴 수 있었다.
- **변경 후**: `ExtractedMenu.name` 은 **사진 판독 결과**다. OCR 텍스트와 다르면 사진 판독이 이긴다.
- **파급**: `koreanName` 이 정확해지면 `KoreanMenuNameNormalizer.matchKey` 기준 조회가 기존 음식과 더 자주 매칭돼, 조사 대기(INCOMPLETE) 신규 등록이 줄고 위험도 판정 비율이 오른다(SC-002). 매칭 로직 자체는 손대지 않는다.

## 검증 규칙

기존 불변식 그대로다 — `ExtractedMenu` 의 `name`·`koreanName` blank 금지, `priceKrw >= 0` 또는 null. `MenuBoardResultParser` 는 이름이 빈 항목을 건너뛰고, 구조가 깨진 응답은 예외로 올린다(FR-008). 이번 변경으로 추가·완화되는 규칙은 없다.
