# Implementation Plan: Firebase 토큰 검증 소셜 로그인 — 자체 JWT 쿠키 발급·재발급·로그아웃

**Branch**: `kb-118-firebase-jwt-auth` | **Date**: 2026-07-10 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-118-firebase-jwt-auth/spec.md` (Jira KB-118)

## Summary

클라이언트가 제출한 Firebase ID 토큰을 firebase-admin `verifyIdToken` 으로 검증(서명·iss·aud·exp)하고, 클레임에서 (provider, **플랫폼 원본 sub**, email) 을 추출해 로그인/가입 처리한다 — 신원 해소(조회→가입→중복 폴백)는 `LoginUseCase` 가 직접 수행(도메인 `MemberIdentityResolver` 는 소비자가 로그인뿐이라 인라인 후 삭제, 사용자 결정 2026-07-11). 성공 시 자체 JWT 2종을 발급해 **응답 본문**으로 내려준다(쿠키 결정은 모바일 클라이언트 확인 후 개정) — access(30분, stateless, 클레임은 회원 PK 하나뿐)·refresh(14일, `jti` 를 **Redis 에 TTL 저장**해 폐기 가능). 재발급은 **rotation** — access·refresh 둘 다 갱신해 refresh 유효기간을 연장하고 구 refresh 는 즉시 폐기하며, refresh 만료 시엔 **강제 로그아웃**(세션 폐기 + 쿠키 제거, 재로그인 필수). `POST /api/v1/auth/{login,refresh,logout}` 3개 엔드포인트. 모듈 배치는 사용자 결정(속도 우선): firebase-admin·jjwt·유스케이스는 `:application:client` 의 `auth` 패키지(헌법 III 의식적 완화 — 아래 Complexity Tracking), `RefreshTokenStore` port 는 `:core:member`, Redis 어댑터·의존성은 `:infra:persistence`(기존 패턴 그대로). Redis 인프라 도입(docker-compose·프로필 설정) 포함, Flyway 마이그레이션 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: 신규 — `firebase-admin`(application:client), `jjwt` api/impl/jackson(application:client), `spring-boot-starter-data-redis`(infra:persistence). jjwt·firebase-admin 은 Boot BOM 미관리라 카탈로그에 버전 명시

**Storage**: Redis(신규 — refresh 토큰 jti, TTL) + MySQL(회원 — 변경 없음, 마이그레이션 없음)

**Testing**: Kotest BehaviorSpec. application 단위(페이크 verifier·인메모리 store), persistence 통합(Redis Testcontainers `@ServiceConnection`), app:api MockMvc(`@TestConfiguration` 페이크 verifier 빈 + MySQL·Redis Testcontainers). Firebase 실호출 0

**Target Platform**: `:app:api` bootJar. `:app:batch` 는 무접촉(application:client 미의존)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 변경 모듈: `:core:member`(port 1개 추가)·`:application:client`(auth 패키지 신설)·`:infra:persistence`(Redis 어댑터)·`:app:api`(컨트롤러·설정)·루트(docker-compose·카탈로그)

**Performance Goals**: 로그인 시 Firebase 원격 검증 1회(공개키는 firebase-admin 이 캐싱). 이후 요청 인증은 서명 검증만(무 DB·무 Redis). 재발급만 Redis 1회 조회

**Constraints**: 자체 토큰 사용자 클레임 = 회원 PK 단 하나(개인정보 금지 — KB-102 강화). 서비스 계정 키·JWT 시크릿 커밋 금지. Firebase 검증은 로그인 시 1회만. access 즉시 무효화 없음(짧은 수명으로 수용). Spring Security 미도입 — 보호 API 필터는 후속(이번엔 보호 대상 API 자체가 없음)

**Scale/Scope**: core:member port 1 + application auth 패키지(~9 클래스) + persistence 어댑터 1 + api 컨트롤러·DTO·설정 + docker/카탈로그/yml. 테스트 4벌(유스케이스·클레임 매핑·Redis 어댑터·MockMvc)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | ✅ PASS | 전 유스케이스·매핑·어댑터·엔드포인트가 실패 테스트 선행(R8). Firebase 실호출 없이 전부 페이크/Testcontainers |
| II. Bounded Contexts | ✅ PASS | auth 는 member 컨텍스트 조합 유스케이스로 application 에 위치(조합은 application 에서만). 도메인 간 신규 의존 없음 |
| III. Layered Dependency Direction | ⚠️ **위반 — 정당화 수용** | firebase-admin 을 `:application:client` 가 직접 의존(port-only 원칙 위반). 사용자 의식적 결정 — 아래 Complexity Tracking. 나머지는 준수: `RefreshTokenStore` port 는 core, Redis 구현은 persistence, app:api runtimeOnly 조립 |
| IV. Persistence Encapsulation | ✅ PASS | Redis(저장 기술)는 persistence 안에 격리, 상위는 port 만 본다. spring-data-redis 는 implementation 스코프 |
| V. Domain Content Language Policy | ✅ N/A | 음식 콘텐츠 아님 |
| 추가 제약 | ✅ PASS | Firebase 원격 검증은 DB 트랜잭션 밖(verify → resolve 순). 도메인 모델 API 미노출(LoginResponse DTO) |

**Post-Design Re-check**: 위반 1건(III) — Complexity Tracking 에 정당화 기록됨, 그 외 없음.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| firebase-admin 을 `:application:client` 직접 의존 (헌법 III — application 은 port 로만 외부 기술 사용) | 사용자 결정(2026-07-10): "비즈니스 레벨에서 빠르게 구현하기 위해 눈감아준다" — 인증 1개 기능에 infra 모듈 신설·port 승격·runtimeOnly 조립 배선은 속도 대비 과함 | 이슈 원안(infra:firebase 신설 + core port) — 소비자가 로그인 하나뿐인 시점에 모듈 1개·조립 배선을 선투자. 재사용·교체 필요가 생기면 그때 infra 모듈로 추출(승격 경로 기록). 테스트 seam(`SocialTokenVerifier` 인터페이스)은 유지해 단위 테스트 격리는 보존 |

## Project Structure

### Documentation (this feature)

```text
specs/kb-118-firebase-jwt-auth/
├── plan.md              # This file
├── spec.md
├── research.md          # R1~R8
├── data-model.md        # port·유스케이스·토큰 명세·설정·의존성
├── contracts/auth-api.md  # login/refresh/logout 계약
├── quickstart.md        # 테스트·로컬 실행·키 주입 문서화(DoD)
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks)
```

### Source Code (repository root)

```text
gradle/libs.versions.toml                  # 수정 — firebase-admin·jjwt 버전·좌표 추가
docker-compose.yml                         # 수정 — meogo-redis 서비스 추가

core/member/src/main/kotlin/com/meogo/core/member/
└── RefreshTokenStore.kt                   # 신규 — port (Spring-free)

application/client/build.gradle.kts        # 수정 — firebase-admin·jjwt 의존성
application/client/src/main/kotlin/com/meogo/application/client/auth/
├── SocialTokenVerifier.kt                 # 신규 — interface (테스트 seam)
├── FirebaseTokenVerifier.kt               # 신규 — firebase-admin 구현, @ConditionalOnProperty
├── FirebaseClaimMapper.kt                 # 신규 — 클레임 Map → SocialIdentity 순수 함수
├── FirebaseAppConfig.kt                   # 신규 — FirebaseApp 초기화(credentials-path)
├── TokenIssuer.kt / TokenParser.kt        # 신규 — jjwt HS256, 클레임 sub=PK(+refresh jti)
├── AuthTokenProperties.kt                 # 신규 — secret·access-ttl·refresh-ttl
├── LoginUseCase.kt / RefreshUseCase.kt / LogoutUseCase.kt   # 신규
└── AuthErrorCode.kt / AuthException.kt    # 신규 — 401 계열, MeogoException 계층

infra/persistence/build.gradle.kts         # 수정 — spring-boot-starter-data-redis
infra/persistence/src/main/kotlin/com/meogo/infra/persistence/auth/
└── RefreshTokenRedisAdapter.kt            # 신규 — auth:refresh:{jti} → memberId, TTL
infra/persistence/src/testFixtures/.../RedisContainerConfig.kt  # 신규 — @ServiceConnection

app/api/src/main/kotlin/com/meogo/app/api/auth/
├── AuthController.kt / AuthApi.kt         # 신규 — /api/v1/auth/{login,refresh,logout}
├── LoginRequest.kt / LoginResponse.kt     # 신규 — {idToken} / {memberId, newMember}
└── AuthCookieFactory.kt                   # 신규 — ResponseCookie 생성(HttpOnly·SameSite·Path·Secure 프로필별)
app/api/src/main/resources/application*.yml  # 수정 — meogo.auth.*·spring.data.redis.*
```

**Structure Decision**: 신규 모듈 없음(사용자 결정 R1). auth 유스케이스는 `:application:client`(컨텍스트 조합 위치 — 헌법 II 부합), 저장 기술은 `:infra:persistence`, web 표면은 `:app:api` — firebase-admin 위치 한 곳만 완화.

## Phase 0: Research 결과 요약

전 항목 [research.md](research.md) — NEEDS CLARIFICATION 0건.

| # | 결정 |
|---|------|
| R1 | 모듈 배치: firebase-admin+jjwt = application:client(완화 1건), RefreshTokenStore port = core:member, Redis 어댑터 = persistence |
| R2 | verifyIdToken 전항목 검증. 원본 sub(firebase.identities) 저장 — Firebase uid 금지. 클레임 매핑 순수 함수 분리 |
| R3 | jjwt HS256, 클레임 sub=회원 PK 단 하나. access 30m / refresh 14d(+jti) |
| R4 | Redis `auth:refresh:{jti}`→memberId, TTL=수명. 다중 기기 자연 허용. **rotation 포함** — 재발급 시 둘 다 갱신·구 jti 삭제·유효기간 연장(사용자 결정). 재사용 탐지는 범위 밖 |
| R5 | (개정) 토큰 = 응답 본문, 클라이언트가 Authorization Bearer 로 제시. 예외 4종(INVALID_SOCIAL/UNSUPPORTED_PROVIDER/INVALID_REFRESH/**EXPIRED_REFRESH=강제 로그아웃**) 전부 401 |
| R6 | 정지 회원: 기존 메커니즘(유니크 충돌→DUPLICATE)이 차단·회원 미생성 보장 — 신규 코드 없음, 전용 에러는 후속 |
| R7 | 이메일: 가입 시 1회 저장, 재로그인 갱신 없음 |
| R8 | 테스트 4벌 — 유스케이스(페이크)·클레임 매핑(순수)·Redis 어댑터(Testcontainers)·MockMvc(페이크 빈) |

## Phase 1: Design 산출물

- [data-model.md](data-model.md) — port·auth 패키지 구성·토큰/쿠키 명세·설정·신규 의존성.
- [contracts/auth-api.md](contracts/auth-api.md) — 3개 엔드포인트 요청/응답/쿠키/오류 계약.
- [quickstart.md](quickstart.md) — 테스트 실행·로컬 Redis·서비스 계정 키 주입 경로 문서화(DoD 충족).

## Phase 2 준비 (참고 — /speckit-tasks 입력)

권장 흐름: ① 인프라 준비(카탈로그·docker-compose·yml — 테스트 전제) ② 토큰 발급/파싱 Red→Green ③ 클레임 매핑 Red→Green ④ RefreshTokenStore port + Redis 어댑터 Red(Testcontainers)→Green ⑤ 유스케이스 3종 Red(페이크)→Green ⑥ 컨트롤러·쿠키 MockMvc Red→Green ⑦ 정지 회원 통합 검증 ⑧ 전체 회귀.
