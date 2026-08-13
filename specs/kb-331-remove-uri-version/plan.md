# Implementation Plan: 전 API URI 버전 제거 + 버전 헤더 필수화

**Branch**: `kb-331-remove-uri-version` | **Date**: 2026-08-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-331-remove-uri-version/spec.md`

## Summary

레거시 `/api/v1` 경로를 전부 `/api/<리소스>` 로 이동하고(`ApiPaths.V1` 삭제, 컨트롤러 12개), `X-API-Version` 헤더의 암묵 기본값(1.0)을 폐지해 `/api/**` 요청에서 필수로 만든다. 예외는 `/api/app-version`(강제 업데이트 복구 경로)과 비-`/api` 경로(관리자 콘솔 페이지·swagger·actuator) — 커스텀 버전 리졸버 폴백으로 처리한다. 헤더 누락·미지원 버전은 `BaseResponse` 봉투의 400 으로 응답한다(전용 예외 핸들러 신설).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21, Spring Boot 4.1 (Spring Framework 7.0.8 네이티브 API 버저닝)

**Primary Dependencies**: `ApiVersionConfigurer`(spring-webmvc) — `useRequestHeader`·`useVersionResolver`·`setDefaultVersion`·`setVersionRequired` 확인 완료(javap). 누락 시 `MissingApiVersionException`, 미지원 시 `InvalidApiVersionException`(둘 다 `ResponseStatusException` 계열)

**Storage**: 변경 없음

**Testing**: 기존 통합 테스트 33개 파일이 `/api/v1` 경로 사용 — 경로 치환 + MockMvc **기본 헤더 주입**(`MockMvcBuilderCustomizer` 테스트 빈, defaultRequest 에 `X-API-Version: 1.0`)으로 전면 헤더 추가를 회피. 무헤더 거절 전용 테스트는 customizer 를 우회한 raw MockMvc 로 작성

**Target Platform**: `:api` 단일 모듈 (+ 문서·외부 소비자 후속)

**Project Type**: 경로·설정 개정 스윕 — main 14파일 + 테스트 33파일

**Performance Goals**: 해당 없음

**Constraints**: 과도기 이중 매핑 없음(스펙 전제). 외부 소비자 kbap-langchain 이 admin API 를 무헤더로 호출 중 — **kbap 배포 전에 langchain 헤더 추가 배포가 선행**되어야 한다

**Scale/Scope**: 컨트롤러 12 + WebConfig(버저닝·JWT 패턴·게스트 예외) + 예외 핸들러 + swagger 문구 + 테스트 33파일 + 문서(CLAUDE.md 경로 규약·conventions)

## Constitution Check

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 신규 동작(무헤더 400·app-version 예외·구 경로 404)은 실패 테스트 선행. 경로 치환은 기존 테스트가 회귀 그물 — 치환 후 전체 그린이 검증 |
| II. Bounded Contexts | PASS | 도메인 무접촉 — api 웹 계층·설정만 |
| III. Layered Dependency Direction | PASS | 의존 방향 무변경 |
| IV. Persistence Ownership | PASS | 영속 무접촉 |
| V. Domain Content Language Policy | N/A | lang 정책 무관 |

**Post-Phase-1 재평가**: 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-331-remove-uri-version/
├── plan.md · research.md · data-model.md · quickstart.md
├── contracts/path-migration.md
└── tasks.md (/speckit-tasks)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/
├── core/ApiPaths.kt                    # V1 상수 삭제
├── core/GlobalExceptionHandler.kt      # Missing/InvalidApiVersionException → 400 COMMON-002 봉투
├── core/config/WebConfig.kt            # 버저닝(기본값 폐지+필수화+예외 리졸버)·JWT 패턴·게스트 예외 V1→API
├── {home,bookmark,auth,member,scan,image×2,report,food,community,block}/*Controller.kt  # V1→API (12개)
└── */*Api.kt                           # swagger 경로·버전 설명 문구 갱신

api/src/test/kotlin/com/kbap/api/
├── core/config/ApiVersionRequiredTest.kt   # 신규 — 무헤더 400·app-version 예외·구 경로 404
├── (테스트 33파일)                          # /api/v1 → /api 치환
└── 테스트 공통 config                        # MockMvcBuilderCustomizer 로 defaultRequest 헤더 주입

docs/ + CLAUDE.md                       # 경로 규약 절 개정(레거시 V1 서술 제거) — 구현 마지막 task
```

**Structure Decision**: 버저닝 예외는 경로별 설정이 없으므로 **리졸버 폴백**으로 구현 — 헤더 리졸버 뒤에 "경로가 `/api/` 로 시작하지 않거나 `/api/app-version` 이면 1.0 반환" 폴백 리졸버를 둔다. 이 규칙 하나로 관리자 콘솔 페이지(`/admin/**`)·swagger·actuator 가 자동 면제되고, `/api/**` 만 필수가 된다.

## 배포 순서 (하위 호환 파괴 — 순서가 안전의 전부)

1. **kbap-langchain**: `KbapClient` 에 `X-API-Version: 1.0` 헤더 추가 배포 (현재 미전송 — 필수화 시 콘텐츠 적재 401 아닌 400 으로 전멸)
2. **iOS 새 릴리스**: 새 경로(`/api/**`) + 전 요청 헤더 첨부 + KB-329 버전체크 탑재 → 스토어 배포·전환 기간
3. **kbap 서버**: 본 개정 배포 — 잔존 구 앱은 이 시점부터 깨진다(구 앱엔 버전체크가 없어 안내 불가 — 전환 기간·시점은 운영 판단)
4. KB-329 admin API 로 minSupportedVersion 상향 — 이후 설치·복귀 사용자 강제 업데이트 유도

## Complexity Tracking

위반 없음 — 해당 없음.
