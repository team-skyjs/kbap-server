# Implementation Plan: 이미지 업로드 객체 키 환경 접두(key-prefix) 지원

**Branch**: `kb-171-storage-key-prefix` | **Date**: 2026-07-20 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-171-storage-key-prefix/spec.md`

## Summary

prod S3 버킷 하나를 전 환경이 공유하므로, 업로드 API 를 경유하는 이미지(메뉴판 스캔·프로필)의 객체 키에 환경별 최상위 폴더 접두(`dev/`·`staging/`, prod 는 빈 값)를 붙인다. 구현은 설정 1개 + 필드 1개 + 결합 1줄 수준: `kbap.storage.key-prefix` 프로퍼티(기본 빈 값)를 `ImageUploadProperties.keyPrefix` 로 주입받아 `ImageUploadApplicationService.objectKey()` 가 기존 키 앞에 슬래시 정규화 결합한다. 빈 값이면 기존 키 구조 그대로(prod·local 무영향). 접두 포함 키가 그대로 DB ref 로 저장되므로 URL 조립(`ImageUrls.resolve`)·API 계약·DB 스키마는 무변경.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택 그대로)

**Primary Dependencies**: Spring Boot 4.1 — 신규 의존성 0 (`@Value` 프로퍼티 주입만)

**Storage**: 변경 없음 — S3 객체 키 문자열 구조만 변경(접두 결합). DB 스키마·Flyway 0

**Testing**: Kotest BehaviorSpec — 기존 `ImageUploadApplicationServiceTest`(:application, 페이크 port) 확장

**Target Platform**: `:app:api` web bootJar (`:app:batch` 는 업로드 미사용 — 범위 밖)

**Project Type**: 기존 모듈러 모놀리스 내 설정+순수 로직 소폭 변경

**Performance Goals**: 해당 없음 — 문자열 결합 1회 추가

**Constraints**: prod·local 회귀 0 (접두 미설정 시 기존 키 구조 바이트 동일). 기존 테스트 무수정 통과

**Scale/Scope**: 프로덕션 코드 3파일(`ImageUploadProperties`·`ImageUploadConfig`·`ImageUploadApplicationService`) + yml 3파일(base·dev·staging) + 테스트 1파일

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ PASS | 접두 유무·슬래시 변형별 객체 키 생성 테스트를 `ImageUploadApplicationServiceTest` 에 먼저 추가해 Red 확인 후 구현 |
| II. Bounded Contexts | ✅ PASS | 도메인 모듈 0 변경 — `:application`(무소속 유스케이스)·`:app:api`(config·yml)만. 도메인 간 의존 변화 없음 |
| III. Layered Dependency Direction | ✅ PASS | 모듈 그래프 무변경. 설정 주입은 기존 패턴(부트앱 config → `ImageUploadProperties`) 그대로 |
| IV. Persistence Encapsulation | ✅ PASS | 엔티티·리포지토리·영속 코드 0 변경 |
| V. Domain Content Language Policy | ✅ PASS | 해당 없음(콘텐츠·번역 무관) |

**Post-Phase 1 재평가**: 위반 없음 — Complexity Tracking 불요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-171-storage-key-prefix/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── quickstart.md        # Phase 1 output (배포 후 검증 런북)
└── tasks.md             # Phase 2 output (/speckit-tasks — 이 커맨드가 만들지 않음)
```

- **data-model.md 없음**: 엔티티·스키마 변경 0 — 객체 키는 기존 문자열 ref 그대로 저장.
- **contracts/ 없음**: API 요청·응답·에러코드 무변경(`objectKey` 값의 내용만 환경에 따라 접두 포함).

### Source Code (repository root)

```text
application/src/main/kotlin/com/kbap/application/upload/
├── ImageUploadProperties.kt          # [수정] keyPrefix: String 필드 추가
└── ImageUploadApplicationService.kt  # [수정] objectKey() 에 접두 정규화 결합

application/src/test/kotlin/com/kbap/application/upload/
└── ImageUploadApplicationServiceTest.kt  # [수정] 접두 유무·슬래시 변형 시나리오 추가 (Red 진입점)

app/api/src/main/kotlin/com/kbap/app/api/config/
└── ImageUploadConfig.kt              # [수정] @Value("${kbap.storage.key-prefix:}") 주입

app/api/src/main/resources/
├── application.yml                   # [수정] kbap.storage.key-prefix: ${STORAGE_KEY_PREFIX:local}
├── application-dev.yml               # [수정] key-prefix: ${STORAGE_KEY_PREFIX:dev}
├── application-staging.yml           # [수정] key-prefix: ${STORAGE_KEY_PREFIX:staging}
└── application-prod.yml              # [수정] key-prefix: ${STORAGE_KEY_PREFIX:prod}

app/api/src/test/resources/
└── application.yml                   # [수정] key-prefix: local (테스트 환경도 local/ 접두)
```

**Structure Decision**: 신규 파일 0, 신규 모듈 0. 기존 KB-145 업로드 경로의 세 파일과 yml 만 만진다. prod·local yml 은 선언하지 않아 base 기본(빈 값)으로 동작 — 기존 키 구조 유지.

## 구현 설계 핵심 (research.md 요약)

1. **프로퍼티 위치 = `kbap.storage.key-prefix`** (Jira DoD 명시) — 버킷·public-base-url 과 같은 `kbap.storage.*` 군. 값은 env `STORAGE_KEY_PREFIX` 주입, 기본 빈 값(미설정 시 기동 실패 없음).
2. **접두 결합은 `objectKey()` 안에서 1회 정규화** — `keyPrefix.trim('/')` 후 빈 값이면 기존 키 그대로, 값이 있으면 `"$prefix/$key"`. `dev`·`dev/`·`/dev` 모두 동일 결과, 선행 슬래시·중복 슬래시 구조적 불가.
3. **URL 조립 무변경** — 접두 포함 키가 DB ref 로 저장되고 `ImageUrls.resolve(base, ref)` 는 ref 를 경로로 그대로 접합하므로 접두는 경로의 일부일 뿐.
4. **yml 선언 전략 (2026-07-20 개정 — 사용자 결정)** — 전 환경 환경명 기본값: base `${STORAGE_KEY_PREFIX:local}`·테스트 yml `local`·프로필 `${STORAGE_KEY_PREFIX:dev|staging|prod}`. env 는 커밋 없는 오버라이드(빈 값 반전 포함) — KB-169 관례. 음식 사진은 업로드 API 미경유라 접두와 무관하게 `images/menus/…` 공용 경로 유지(향후 배치 음식 사진 제작도 `images/` 직접 기록).
5. **테스트 전략** — `:application` 단위 테스트만으로 완결(페이크 port 가 키를 기록). 통합·컨트롤러 테스트는 키 구조를 검증하지 않으므로 무수정 통과.

## Complexity Tracking

> 위반 없음 — 해당 없음.
