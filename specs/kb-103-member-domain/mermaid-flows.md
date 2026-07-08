# API Flows: 회원 온보딩 라우팅

> 엔드포인트 경로(`/api/v1/auth/login`·`/api/v1/members/me`)는 예시다 — 요청·응답 계약은 프론트와 협의해 KB-102/KB-104 에서 확정한다. KB-103 범위는 도메인(`:core:member`)+영속(`:infra:persistence`)이며, 로그인·`/me` 엔드포인트 조립은 KB-102/104 몫이다. 온보딩 상태의 단일 진실은 `members.onboarding_status`(DB)이고 **JWT 에는 담지 않는다**.

## UC-1. 소셜 로그인 — 신원 해소 + JWT 발급

- **트리거**: 사용자가 구글/애플 로그인 탭 (최초 가입 또는 재로그인 공통)
- **사용 컨텍스트**: `member`(신원 해소), 인증(KB-102)

```mermaid
sequenceDiagram
    actor User
    participant Client
    participant API as app:api (KB-102)
    participant App as application (login)
    participant Member as core:member (Resolver)
    participant DB as infra:persistence

    User->>Client: 소셜 로그인 탭
    Client->>Client: 네이티브 SDK로 ID token 획득
    Client->>API: POST /api/v1/auth/login {provider, idToken}
    API->>App: 로그인 유스케이스
    App->>App: ID token 검증 (JWKS·aud·iss·exp) → sub·email 추출
    App->>Member: resolve(SocialIdentity(provider, sub, email?))
    Member->>DB: findByIdentity(provider, sub)
    alt 기존 신원 존재
        DB-->>Member: Member
        Member-->>App: (member, isNewMember=false)
    else 신원 없음
        Member->>DB: saveNew(Member.signUp) — PENDING
        alt 유니크 위반 (동시 가입 race)
            DB-->>Member: DUPLICATE_SOCIAL_IDENTITY
            Member->>DB: findByIdentity(provider, sub) 재조회
            DB-->>Member: Member
            Member-->>App: (member, isNewMember=false)
        else 정상 생성
            DB-->>Member: 저장된 Member
            Member-->>App: (member, isNewMember=true)
        end
    end
    App->>App: JWT 발급 (클레임=회원 id만, email·onboarding 미포함)
    API-->>Client: {accessToken, refreshToken, isNewUser, onboardingStatus}
    alt onboardingStatus == PENDING
        Client->>User: 온보딩 화면
    else COMPLETED
        Client->>User: 홈 화면
    end
```

> **정책**: 신원 해소 키는 (provider, sub) 단독 — email 은 참고 정보로 저장만(자동 통합 없음). 동시 최초 로그인 race 는 DB 유니크 + 재조회 1회로 한 회원 수렴. JWT 는 회원 id 만.

## UC-2. 앱 재진입 — 저장된 JWT 로 온보딩 상태 hydration

- **트리거**: 사용자가 앱 재실행 (JWT 로컬 보관 상태). 소셜 로그인 재수행 없음.
- **사용 컨텍스트**: `member`(조회), 인증 필터(KB-102) + `/me`(KB-102/104)

```mermaid
sequenceDiagram
    actor User
    participant Client
    participant Filter as app:api JWT 필터
    participant API as app:api (/me)
    participant App as application (me)
    participant Member as core:member
    participant DB as infra:persistence

    User->>Client: 앱 실행
    Client->>Filter: GET /api/v1/members/me (Authorization: Bearer JWT)
    Filter->>Filter: 서명·만료·alg 검증 → 회원 id 추출
    alt 토큰 무효·만료
        Filter-->>Client: 401 (BaseResponse) → refresh 또는 재로그인
    else 유효
        Filter->>API: 현재 회원 id 주입
        API->>App: 현재 회원 조회 유스케이스
        App->>Member: findById(memberId)
        Member->>DB: SELECT (soft delete 제외)
        DB-->>Member: Member (onboardingStatus·프로필)
        Member-->>App: Member
        API-->>Client: {onboardingStatus, nickname, 기피성분, 국가, 언어, ...}
        alt onboardingStatus == PENDING
            Client->>User: 온보딩 화면 (이어서 유도)
        else COMPLETED
            Client->>User: 홈 화면 (프로필로 렌더)
        end
    end
```

> **정책**: 온보딩 여부는 JWT 가 아니라 DB 최신값(`/me`)으로 판단 — 완료 후 토큰 재발급 불필요(R11-a). 재진입 시 어차피 프로필(기피성분·언어)로 화면을 그리므로 `/me` hydration 이 자연스럽다. 온보딩 완료(KB-104 제출) 응답이 COMPLETED 를 돌려주면 클라이언트는 로컬 상태만 갱신.
