# Quickstart: 스캔 응답 DB 매칭 음식명 번역 (KB-189)

## 1. 자동 검증 (핵심)

```bash
./gradlew :app:api:test --tests "com.kbap.app.api.scan.ScanControllerTest"
```

커버 시나리오:

- 앱 언어 `en` 회원 + `name_translations.en` 보유 READY 음식 → `results[].name` = 영어 번역명
- 미매칭(DB 부재·INCOMPLETE) 항목 → `name` = 비전 추출 이름 그대로
- 앱 언어 `en` 회원 + 번역 부재 READY 음식 → `name` = 한국어 이름(폴백)
- 앱 언어 미설정 회원(profile `{}`) + READY 음식 → `name` = 한국어 이름(V-1 ko 기본)

## 2. 전체 회귀

```bash
./gradlew test
```

## 3. 수동 확인 (선택 — dev 배포 후)

1. 앱 언어를 영어로 설정한 계정으로 로그인.
2. DB 에 READY 로 등록된 음식(예: 김치찌개, `name_translations.en` 존재)이 있는 메뉴판 스캔.
3. `POST /api/v1/scans` 응답 `payload.results[].name` 이 영어 번역명인지, 미등록 메뉴는 사진 표기 그대로인지 확인.
