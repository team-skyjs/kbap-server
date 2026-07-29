# Research: 관리자 페이지 — 음식 데이터 적재 현황·회원 관리 화면

**Date**: 2026-07-29 | **Plan**: [plan.md](plan.md)

Technical Context 의 미확정 사항을 전부 결정한다. NEEDS CLARIFICATION 잔여 0건.

## R1. UI 렌더링 스택 — 타임리프 SSR (:api 내장)

- **Decision**: `spring-boot-starter-thymeleaf` 를 `:api` 에 추가하고 `templates/admin/` 에 서버 렌더링 화면을 둔다. 별도 프론트 스택·빌드 도구·CDN 의존 없음. CSS 는 `static/admin/admin.css` 단일 파일(디자인 토큰: 색·타이포·간격 변수) + 최소한의 바닐라 JS(폼 제출 확인 정도).
- **Rationale**: KB-245 가 명시한 방향. 추가 배포 인프라·CORS·토큰 크로스오리진 없이 기존 :api 배포 파이프라인 그대로 운영(사전 논의에서 사용자 확정). 어드민 규모(화면 5개)에 SPA·빌드 파이프라인은 과투자.
- **Alternatives considered**: ① 정적 HTML + fetch(REST 재사용) — 로그인 상태 관리를 JS 로 직접 해야 하고 KB-245 결정(타임리프)과 어긋나 기각. ② S3+CloudFront 분리 — 배포처·CORS 추가, 초반 속도 우선으로 기각. ③ `:admin` 별도 bootJar — ECS 서비스 +1, 사용자가 기각.

## R2. 관리자 인증 — `admin_account` 테이블 자체 로그인 + ADMIN JWT HttpOnly 쿠키 (무상태) *(2026-07-29 clarify 개정)*

- **Decision**:
  - 자격 증명: 신규 **`admin_account` 테이블**(`com.kbap.common.domain.admin` — `AdminAccount` 엔티티 + `AdminAccountJpaRepository`). 컬럼: `login_id`(unique) + `password`(BCrypt 해시) + BaseEntity 공통. 최초 계정은 운영자가 DB 에 직접 INSERT(1회성) — 계정 등록·비밀번호 변경 화면은 범위 밖. 검증은 `spring-security-crypto` 의 `BCryptPasswordEncoder.matches`(Spring Security 풀스택 미도입).
  - Flyway 마이그레이션 1건 추가(`Vyyyy.MM.dd.HH.mm.ss__create_admin_account_table.sql` — timestamp 규칙).
  - 로그인 성공 시 기존 `TokenIssuer.issueAccessToken(memberId = adminAccount.id, role = ADMIN)` 으로 JWT 발급 → `HttpOnly + Secure + SameSite=Strict + Path=/admin` 쿠키(Max-Age 미지정 세션 쿠키 — 만료는 토큰 자체 만료를 인터셉터가 검증)로 저장. 토큰의 id claim 이 admin_account id 라 member id 와 충돌 가능(주체 혼동) — 쿠키 Path 만으론 Authorization 헤더 재사용을 못 막으므로 **`@AuthMemberId`/`@AuthMemberIdOrNull` 리졸버가 role=ADMIN 토큰의 회원 신원 해석을 거절**한다(회원 신원의 유일한 관문 — Codex 리뷰 2026-07-29 Critical 반영).
  - `/admin/**` 뷰 경로는 신규 `AdminPageAuthInterceptor` 가 쿠키를 `TokenParser` 로 파싱해 `role == ADMIN` 검사, 실패 시 `/admin/login` 리다이렉트. 기존 REST(`/api/v1/admin/**`)의 `JwtAuthenticationFilter`+`AdminAuthorizationInterceptor` 는 무변경.
- **Rationale**: ① 소셜 로그인 미사용·계정 테이블 자체 로그인은 사용자 결정(clarify 2026-07-29 — env 프로퍼티 방식 폐기). 계정별 추가/회수·감사 확장의 기반이 된다. ② **JWT 쿠키(무상태)를 HttpSession 대신 선택한 결정적 이유: prod api 가 2대**라 서버 세션은 스티키 세션(ALB 설정) 또는 Spring Session Redis 없이는 동작하지 않는다. 기존 jjwt 인프라 재사용이 가장 싸고 다중 인스턴스에 안전. ③ 현재 `AuthService` 는 USER 토큰만 발급하므로 ADMIN 토큰 발급 경로 신설이 실제로 필요한 조각.
- **CSRF**: Spring Security 미도입이라 토큰형 CSRF 방어가 없다 — `SameSite=Strict` 쿠키 + **`AdminPageAuthInterceptor` 의 POST Origin 헤더 검사**(불일치 거절, 무상태 수 줄짜리)로 내부 도구 수준 방어. CSRF 토큰 체계는 최소 구현 기조로 미도입(Codex 지적 축소 수용).
- **경계 확인 필요**: `ModuleBoundaryTest` 의 `common.domain` 허용 방향 맵에 `admin` 컨텍스트 등록이 필요한지 확인(admin 은 타 도메인 의존 0 — 등록만으로 충분할 것).
- **Alternatives considered**: ① env 프로퍼티 자격 증명 — 초기 plan 결정이었으나 사용자가 계정 테이블로 개정, 폐기. ② HttpSession + 폼 로그인 — 2대 인스턴스에서 스티키/세션 공유 인프라 필요, 기각. ③ Spring Security form login — 의존·설정 footprint 가 화면 5개 도구에 과함, 기각. ④ Authorization 헤더 JWT — SSR 페이지 네비게이션이 헤더를 실을 수 없어 기각. ⑤ Flyway 시드로 초기 계정 등록 — 해시가 git 에 남고 교체 절차 필요, 수동 INSERT 로 기각(clarify).

## R3. 뷰 컨트롤러와 규약(ArchUnit·BaseResponse) 관계

- **Decision**: 뷰 컨트롤러는 `@Controller`(비 `@RestController`)로 작성하고 `/admin/**` 경로를 쓴다. `ApiPaths` 에 `PAGE = "/admin"` 류 상수를 추가하지 않고 admin 패키지 내 상수로 관리한다.
- **Rationale**: `ModuleBoundaryTest` 의 `/api/v` 경로 규칙은 **`@RestController` 만 검사**함을 확인(라인 234) — `@Controller` 뷰는 규약 대상이 아니어서 ArchUnit 예외 규칙 수정이 불필요하다. `BaseResponse` 봉투 규약도 REST 응답 규약이므로 뷰(템플릿 이름 반환)에는 적용되지 않는다. `ApiPaths` 는 "비즈니스 API 버전 경로" 단일 출처라 뷰 경로를 섞지 않는다.
- **Alternatives considered**: `/api/v1/admin/pages/**` 로 REST 규약 안에 넣기 — JWT 헤더 필터에 걸려 브라우저 네비게이션 불가, 기각.

## R4. 음식 적재 현황 집계 쿼리

- **Decision**: `FoodJpaRepository` 에 JPQL group-by 집계 1개 추가 — `select f.contentStatus as status, count(f) as count from Food f group by f.contentStatus` (projection interface 또는 data class). `AdminFoodDashboardService` 가 4개 상태(INCOMPLETE·PENDING_IMAGE·PENDING_REVIEW·READY) 전부를 0 채움 포함해 뷰 모델로 조립하고 READY 비율을 계산한다.
- **Rationale**: 상태 수 × count 쿼리 4번 대신 단일 쿼리. `BaseEntity` 의 `@SQLRestriction("status = 'ACTIVE'")` 이 소프트삭제 행을 자동 제외하므로 별도 조건 불필요. 집계 쿼리는 소유 도메인 패키지의 리포지토리에 두는 것이 원칙 IV 정합.
- **Alternatives considered**: `countByContentStatus` 파생 쿼리 4회 호출 — 쿼리 4번, 기각. 네이티브 쿼리 — JPQL 로 충분, 기각.

## R5. 회원 목록 페이징·상세

- **Decision**: `MemberJpaRepository` 는 `JpaRepository` 상속으로 `findAll(Pageable)` 을 이미 제공 — 리포지토리 수정 없음. `AdminMemberQueryService` 가 `PageRequest.of(page, size, Sort.by(desc("id")))` 로 조회해 뷰 모델 페이지로 변환. 상세는 `findById` + 없으면 안내 화면. 페이지 범위 초과는 JPA Page 가 빈 페이지를 반환하므로 그대로 "빈 목록" 표시.
- **Rationale**: 관리 화면은 오프셋 페이징이 자연스럽다(페이지 번호 UI). 서비스 API 의 커서 페이징 규약(KB-63)은 사용자향 무한 스크롤용 — 관리 화면에 강제하지 않는다. `@SQLRestriction` 때문에 탈퇴(DELETED) 회원은 자동 제외 — 스펙 Assumptions 와 일치. SUSPENDED 는 `member_status` 컬럼이라 노출된다(상태 뱃지로 표시).
- **Alternatives considered**: 탈퇴 회원 포함 조회(native query 로 @SQLRestriction 우회) — 스펙 범위 밖, 기각.

## R6. 시드 등록·이미지 배치 제출의 화면 연결

- **Decision**: 뷰 폼 POST(`/admin/foods/seed`·`/admin/foods/images`)는 기존 REST 컨트롤러가 쓰는 **동일 서비스 빈을 직접 호출**하고(HTTP 루프백 아님), 처리 결과를 **redirect query parameter**(`?seeded=N`·`?error=코드`)로 대시보드에 표시한다(PRG — 새로고침 중복 제출 방지). **flash attribute 는 쓰지 않는다** — Spring 기본 flash 저장소가 HttpSession 이라 prod api 2대에서 유실된다(Codex 리뷰 Critical — 세션 배제 결정과 모순). 시드 입력은 textarea 줄 단위 파싱(공백 줄 무시)·빈 입력 사전 거절, 처리 예외는 뷰 컨트롤러가 잡아 오류 파라미터로 리다이렉트(전역 JSON 핸들러 노출 금지). 기존 REST 엔드포인트는 그대로 유지(Swagger 호출 병행 가능 — spec Assumptions 와 일치).
- **Rationale**: 같은 프로세스에서 자기 REST 를 HTTP 로 호출하는 것은 인증·직렬화 비용만 추가. 서비스 계층 재사용이 처리 규칙 단일 출처를 보장(스펙 Assumptions — 처리 규칙 무변경).

## R7. 디자인·레이아웃 (768px 고정형)

- **Decision**: 타임리프 fragment 로 공통 레이아웃(사이드바 + 콘텐츠 영역) 구성. 사이드바는 고정폭(~220px), 콘텐츠는 `min-width` 기반 고정형 — 미디어쿼리 분기 없이 768px 뷰포트에서 가로 스크롤 없이 동작하는 치수로 설계. `admin.css` 하나에 CSS 변수 디자인 토큰(색상 팔레트·타이포 스케일·간격 스케일)을 정의하고 전 화면이 공유. 구현 시 frontend-design 스킬로 "깔끔한 관리자 도구" 스타일을 뽑는다.
- **Rationale**: 반응형 미요구 + 최소 폭 768px 만 보장(스펙 FR-011). CSS 프레임워크(CDN)는 외부 의존·스타일 싸움 대비 이득이 없다.
- **Alternatives considered**: Tailwind/Pico CDN — 외부 CDN 의존 추가, 기각.

## R8. 테스트 전략 (헌법 I)

- **Decision**: 전부 Kotest BehaviorSpec, 한국어 given/when/then.
  - **단위**: `AdminLoginService`(페이크 `TokenIssuer` — 자격 증명 일치/불일치/해시 검증), `AdminPageAuthInterceptor`(Mock request — 쿠키 없음/무효/USER role/ADMIN).
  - **통합**(`@SpringBootTest` + Testcontainers + MockMvc): 뷰 라우트 — 미인증 시 302 `/admin/login`, 인증 쿠키 시 200 + 모델 어트리뷰트/뷰 이름 검증. 집계 쿼리 — 상태 분포 시드 후 결과 검증. 회원 페이징 — 페이지 경계·빈 페이지.
  - 템플릿 렌더링 자체(HTML 구조)는 뷰 이름·모델 검증까지만 — 픽셀·마크업 스냅샷 테스트는 두지 않는다(내부 도구, 유지비 > 가치).
- **Rationale**: 기존 테스트 스타일(MockMvc + Testcontainers, BehaviorSpec) 그대로. 로그인·인가·집계·페이징이 회귀 위험 지점.
