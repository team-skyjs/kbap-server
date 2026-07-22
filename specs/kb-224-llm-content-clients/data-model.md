# Data Model: 배치 콘텐츠 4개 작업용 LLM 클라이언트

**Date**: 2026-07-22 | **Plan**: [plan.md](plan.md)

DB 엔티티·스키마 변경은 없다. 이 작업의 데이터 모델은 KB-182 가 `:core` 에 확정한 **계약 DTO**(변경 금지 — FR-008)와, 구현체 내부의 **응답 JSON 형태**다.

## 계약 DTO (`:core` — 기존, 무수정)

| 타입 | 필드 | 불변식(init 강제) |
|------|------|------------------|
| `TargetLanguageTexts` | `texts: Map<LanguageCode, String>` | 키 = 9개 대상 언어 전수(KO 제외), 값 blank 금지 |
| `FoodDescriptionContent` | `description`, `translations: TargetLanguageTexts`, `spiciness` | description ≤255자·blank 금지·플레이스홀더("설명 준비 중") 금지, spiciness 0..10 |
| `FoodAvoidanceAssessment` | `code`, `inclusionPercent` | code blank 금지, percent 0..100. **code ∈ candidateCodes 는 DTO 가 모름 — 구현체가 검증** |
| 사진 결과 | `String`(storageKey) | 절대 URL 금지 — 입력 storageKey 를 저장 완료 후 그대로 반환 |

## 스토리지 seam 확장 (`:core` — 이번 작업)

```kotlin
interface StorageObjectStore {
    fun head(path: String): StorageObjectMetadata?
    fun delete(path: String)
    fun put(path: String, bytes: ByteArray, contentType: String)   // 추가 — 같은 path 덮어쓰기(멱등)
}
```

- 구현: `S3StorageObjectStore`(PutObject). 기존 소비자(head/delete)는 영향 없음.
- `:core` 테스트 등에서 인터페이스를 구현하는 페이크가 있으면 put 구현 추가 필요(컴파일 에러로 전수 발견).

## 설정 프로퍼티 확장 (`kbap.llm.*`)

| 키 | 용도 | 기본 |
|----|------|------|
| `kbap.llm.image.enabled` | 이미지 생성 클라이언트 활성 | `false`(빈 미생성 — 부팅 안전) |
| `kbap.llm.image.api-key` / `model` / `base-url` / `timeout` / `size` 등 | OpenAI 이미지 모델 구성(vision 선례의 독립 프로퍼티) | vision 준용 |
| (기존) `kbap.llm.openai.enabled` | 번역·설명 클라이언트의 전제(OpenAI caller 빈) | 기존 그대로 |
| (기존) `kbap.llm.{openai,upstage,gemini}.enabled` | 기피성분 fan-out 대상 | 기존 그대로 |

## 응답 JSON 계약 (구현체 내부 — 프롬프트가 지시, jackson 파싱)

상세는 [contracts/food-content-clients.md](contracts/food-content-clients.md).

- 번역: `{"translations": {"en": "...", "ja": "...", ...}}` — 9개 언어 키 전수
- 설명: `{"description": "...", "spiciness": 3, "translations": {...}}`
- 기피성분: `{"assessments": [{"code": "PORK", "inclusionPercent": 80}, ...]}` — 모든 candidateCodes 에 대해 판단(미포함 = 0)

## 상태 전이

없음 — 구현체는 무상태다. 음식(`Food`)의 콘텐츠 채움·READY 전이는 배치 파이프라인(KB-182/183/184/209) 소유.
