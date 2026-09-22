# Quickstart: 검증 절차

## 구현 순서 (Test-First)

1. `PushMessageRendererTest` 를 `PushMessageRenderer(PushMessageSourceConfig().messageSource())` 구성으로 바꾸고 아래 검증을 추가 → **Red**(컴파일 실패/키 없음).
   - 누락 검증: 유형 5 × 언어 10 의 title·body, `SCAN_SUGGESTION` × 슬롯 2 × 언어 10, `push.opt-out` × 언어 10 — 실패 메시지에 키·언어가 드러나게.
   - zh 구분: `ZH_HANS`·`ZH_HANT` 렌더 결과가 각각 간체·번체 원문과 정확 일치하고 서로 다르다.
   - 폴백 없음: 지원 밖 로케일(예: `fr`)로 MessageSource 를 직접 조회하면 `NoSuchMessageException`.
   - UTF-8: 운영 문구(ko·th 후보 1 제목)의 👀 가 렌더 결과에 그대로 남는다.
   - 기존 동작 고정(치환·빈 인자·광고 접두·수신거부·절단·슬롯 폴백)은 기존 then 을 유지하되 `PushTemplates.optOutNotice` 참조를 리터럴로 교체.
2. `LanguageCode.locale`, `PushMessageSourceConfig`, 메시지 파일 10개, 렌더러 수정 → **Green**.
3. 문구는 새 후보로 교체됐으므로 구 상수 대조 없음 — `PushTemplates.kt` 삭제.
4. `batch` `PushConfig` `@Import` 에 `PushMessageSourceConfig` 추가, `ScanSuggestionPushJobTest` 의 `PushTemplates` 참조를 리터럴로 교체.

## 실행

```bash
./gradlew :common:test     # 렌더러·디스패치(컨텍스트 조립) 검증
./gradlew :batch:test      # batch 컨텍스트에서 messageSource 빈 조립·스캔 제안 잡 문구
./gradlew build            # api 포함 전체 (ArchUnit 포함)
```

## 완료 확인

- `grep -rn PushTemplates --include='*.kt' .` → 0건 (SC-006)
- 메시지 파일에서 키 하나를 지우고 `:common:test` → 누락 검증이 해당 키·언어를 짚어 실패 (SC-003), 원복
- `file common/src/main/resources/messages/*.properties` → 전부 UTF-8
