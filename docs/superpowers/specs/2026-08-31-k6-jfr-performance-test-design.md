# k6·JFR 성능 테스트 체계 설계

**작성일:** 2026-08-31

**상태:** 설계 승인 완료, 로컬 HTML 대시보드 요구사항 반영

## 목표

dev API 서버의 모든 사용자용 엔드포인트를 하나의 로컬 HTML 대시보드에서 선택·실행하고, 실행마다 HTML·JSON 결과와 두 API 태스크의 JFR을 같은 실행 식별자로 묶는다. 실제 부하 생성은 k6가 담당하고 대시보드는 실행 제어, 진행 조회, 결과 비교, artifact 다운로드만 담당한다. 결과는 애플리케이션 CPU·할당·잠금·GC, Tomcat 스레드, HikariCP, RDS SQL, 외부 HTTP 대기를 교차해 병목과 코드 설계 문제를 재현 가능하게 판정할 수 있어야 한다.

## 현재 기준선

- 대상 환경은 `https://dev.kbap.site`다.
- 인증이 필요한 요청은 기존 `k6/mint-token.py`로 `memberId=35` access token을 만들어 사용한다.
- dev API 서비스는 `kbap-dev-ecs-cluster`의 `kbap-dev-ecs-api`이며 desired/running count는 2다.
- 현재 태스크 정의 기준 CPU는 512, 메모리는 1536MiB, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70`이다.
- API 서비스의 ECS Exec은 현재 비활성화돼 있다.
- 운영 런타임은 `eclipse-temurin:21-jre`다. 이 이미지는 시작 옵션 JFR과 `jfr` 분석 도구를 제공하지만 `jcmd`는 제공하지 않는다.
- 기존 `k6/scan-burst.js`는 스캔 티켓과 v2 스캔만 측정하고 나머지 엔드포인트를 포함하지 않는다.
- 현재 `application-dev.yml`은 `spring.jpa.show-sql=true`이고 HTTP 요청 히스토그램·Tomcat MBean registry 설정은 없다.

## 범위

### 포함

- 기존 `dev` Spring profile을 그대로 사용하는 profiling task definition 환경변수 overlay
- HTTP 히스토그램, Tomcat, JVM, HikariCP 지표 노출
- dev 전용 ECS Exec과 비공개 JFR 보관소
- 동일 애플리케이션 JAR을 JDK 21 런타임으로 실행하는 profiling 이미지 target
- 두 API 태스크의 JFR 시작·중지·수집 자동화
- 엔드포인트 레지스트리 기반 k6 하네스
- 엔드포인트별 HTML·JSON·실행 manifest 산출
- 전체 엔드포인트 목록, 선택 실행, 안전한 전체 실행, 진행 상태, 과거 결과를 제공하는 localhost 전용 HTML 대시보드
- 대시보드에서 task별 JFR과 실행 artifact 묶음 다운로드
- 읽기, 가역 쓰기, fixture 쓰기, 외부 비용형 시나리오
- 스캔 부하 중 일반 API 영향 측정
- Prometheus, RDS SQL 통계, JFR을 함께 사용하는 병목 판정 런북

### 제외

- 이 작업에서 실제 운영 코드를 최적화하거나 DB 인덱스를 추가하는 일
- prod 환경 부하 테스트
- Firebase 로그인 처리량 측정
- 35번 회원의 탈퇴·온보딩 반복 실행
- 관리자 음식 시드·삭제·재수집·이미지 배치 제출 부하
- batch 내부 잡 트리거 API 부하
- Grafana 대시보드의 전면 재설계
- JFR 파일이나 토큰·시크릿을 Git에 저장하는 일

## 채택 아키텍처

```text
local HTML dashboard (127.0.0.1 only)
  └─ local control server
      └─ k6 campaign runner
          └─ dev ALB
              ├─ API task A ─ JFR A
              └─ API task B ─ JFR B
                   ├─ RDS / Redis
                   └─ S3 / Google / OpenAI

campaign-id/
  ├─ campaign.json
  └─ target/
      ├─ report.html
      ├─ summary.json
      ├─ manifest.json
      ├─ task-a.jfr
      └─ task-b.jfr
```

프로파일링 이미지는 기존 bootJar를 그대로 복사하되 runtime만 Temurin 21 JDK로 바꾼다. 운영 기본 Docker target은 계속 JRE로 남긴다. JDK target에는 동적 JFR용 `jcmd`, 분석용 `jfr`, 태스크 역할로 비공개 S3에 결과를 올릴 AWS CLI를 넣는다.

대안은 현재 JRE에 `-XX:StartFlightRecording`을 넣는 방식이다. 코드 변경은 적지만 시작·중지 시간을 엔드포인트별로 통제하려면 매번 태스크 정의를 바꾸거나 긴 녹화에서 시간 범위를 수동 분할해야 한다. 전체 엔드포인트 캠페인에서는 동적 `jcmd` 방식이 더 재현 가능하다.

로컬 Docker만 대상으로 하는 방식은 빠르지만 ALB, RDS, Redis TLS, 실제 외부 API 네트워크를 제거하므로 최종 판정 환경으로 사용하지 않는다.

정적 HTML 파일만으로는 로컬 k6와 AWS CLI 프로세스를 안전하게 시작하거나 중지할 수 없다. 따라서 대시보드는 Python 표준 라이브러리 기반의 작은 localhost 제어 서버가 정적 UI와 JSON/SSE API를 함께 제공하는 구조로 고정한다. 이 서버는 dev에 배포하지 않고 분석자의 로컬 머신에서만 `127.0.0.1`에 bind한다.

## prod 영향 격리

- Docker 기본 최종 stage는 계속 JRE runtime이다.
- profiling task definition도 `SPRING_PROFILES_ACTIVE=dev`를 그대로 사용한다.
- 관측·로그 override는 현재 dev task definition을 복제한 profiling revision에만 환경변수로 넣는다.
- ECS Exec과 JFR artifact bucket은 dev에서만 활성화한다.
- profiling 이미지는 별도 태그로 빌드하며 prod 배포 workflow가 참조하지 않는다.
- 성능 캠페인이 끝나면 일반 dev 이미지로 되돌리고 ECS 서비스 steady state를 확인한다.

## 관측 설정

새 Spring profile이나 설정 파일을 만들지 않는다. 현재 dev task definition을 복제해 profiling image를 지정할 때 다음 환경변수만 덮어쓴다.

```text
SPRING_PROFILES_ACTIVE=dev
SPRING_JPA_SHOW_SQL=false
LOGGING_LEVEL_ROOT=WARN
MANAGEMENT_METRICS_DISTRIBUTION_PERCENTILES_HISTOGRAM_HTTP_SERVER_REQUESTS=true
SERVER_TOMCAT_MBEANREGISTRY_ENABLED=true
```

Spring Boot relaxed binding으로 위 값은 각각 기존 `spring.jpa.show-sql`, `logging.level.root`, HTTP histogram, Tomcat MBean registry 설정을 override한다. dev의 DB·Redis·S3·secret·health check·CloudWatch 설정은 현재 task definition에서 그대로 복제한다.

HTTP 히스토그램은 Prometheus에서 `histogram_quantile`로 p95·p99를 계산하기 위한 전제다. Tomcat MBean registry는 busy/current/max thread를 직접 확인하기 위한 전제다. HikariCP와 JVM 메트릭은 기존 actuator 자동 구성을 유지한다.

주 캠페인은 `show-sql=false`, root `WARN`으로 실행해 SQL 출력과 요청당 INFO 두 건을 제거한다. 에러와 경고는 유지한다. 로깅 자체의 비용은 상위 엔드포인트 하나를 골라 profiling revision에 `LOGGING_LEVEL_COM_KBAP_API_CORE_LOGGING_REQUESTLOGGINGFILTER=INFO`를 추가한 별도 A/B 회차에서만 측정한다. 저장소의 `application-dev.yml`, 일반 dev task definition, prod 설정은 바꾸지 않는다.

## JFR 설정과 보안

기본 `profile.jfc`에서 다음 초기 정보 이벤트를 비활성화한 `ops/jfr/kbap-profile.jfc`를 사용한다.

- `jdk.InitialEnvironmentVariable`
- `jdk.InitialSystemProperty`

JFR은 환경변수, 클래스명, 네트워크 주소 등 운영 내부 정보를 포함할 수 있다. artifact bucket은 다음 조건을 만족해야 한다.

- public access block 전체 활성화
- S3 관리형 암호화 활성화
- 7일 lifecycle 삭제
- dev profiling 태스크 역할은 정해진 prefix에 `PutObject`만 허용
- 로컬 분석자는 별도 IAM 권한으로 `GetObject` 수행

녹화는 각 API 태스크에서 다음 형태로 시작한다.

```bash
jcmd 1 JFR.start \
  name=<run-id> \
  settings=/app/kbap-profile.jfc \
  filename=/tmp/<run-id>.jfr \
  maxsize=256m
```

k6 측정이 끝나면 같은 이름으로 중지한다.

```bash
jcmd 1 JFR.stop name=<run-id>
jfr summary /tmp/<run-id>.jfr
aws s3 cp /tmp/<run-id>.jfr s3://<artifact-bucket>/<run-id>/<task-id>.jfr --sse AES256
```

한 태스크의 녹화만으로는 ALB가 다른 태스크에 보낸 요청이 빠지므로 두 태스크 모두 성공적으로 녹화를 시작하지 못하면 본 측정을 시작하지 않는다. JFR 파일은 서로 합치지 않고 태스크별로 분석한다.

첫 캠페인 전 대표 읽기 엔드포인트를 JFR off/on으로 각각 실행한다. p95 차이가 5%를 넘으면 JFR 설정을 `default`로 낮추거나 샘플링 이벤트를 줄인 뒤 다시 비교한다.

## k6 하네스

단일 `k6/endpoint.js`가 `TARGET` 환경변수로 엔드포인트 정의를 선택한다. 도메인별 모듈은 요청 생성만 담당하고 공통 하네스가 실행 모델, 헤더, 검사, 메트릭, summary를 소유한다.

```javascript
export const endpoint = {
  key: 'foods-search-ko-hit',
  method: 'GET',
  route: '/api/foods/search',
  kind: 'read',
  request(context) {
    return {
      url: `${context.baseUrl}/api/foods/search?keyword=${encodeURIComponent(context.fixtures.foodKeyword)}&lang=ko`,
      body: null,
      params: context.authenticatedParams('1.0'),
    };
  },
};
```

모든 요청은 다음 태그를 가진다.

- `run_id`
- `target`
- `route`
- `method`
- `phase`

성공 검사는 HTTP 200만 확인하지 않고 JSON `success=true`까지 확인한다. 오류 응답은 상태와 business code를 별도 Counter로 집계한다. 앞 단계 실패로 뒤 단계 요청을 생략하더라도 생략된 단계의 실패 Counter를 반드시 증가시켜 기존 스캔 테스트에서 발견된 checks 착시를 반복하지 않는다.

## 로컬 HTML 실행·결과 대시보드

대시보드는 k6를 대체하지 않는다. 브라우저 요청을 받은 localhost 제어 서버가 검증된 인자 배열로 campaign runner를 실행하고, runner가 endpoint별 k6와 두 태스크 JFR 수집을 순서대로 수행한다. 브라우저에는 JWT, AWS credential, secret 환경변수, S3 URL을 전달하지 않는다.

### 실행 단위와 안전 경계

- `단일 실행`: endpoint target 하나를 선택한다.
- `선택 실행`: 체크한 target을 등록 순서대로 실행한다.
- `suite 실행`: read, reversible-write, fixture-write, external 중 하나를 실행한다.
- `안전한 전체 실행`: `risk=safe`인 읽기와 가역 쓰기 target만 직렬 실행한다.
- fixture 생성·정리 target과 외부 비용 target은 기본 선택에서 제외하고 명시적 opt-in, 총 요청 수, 비용 상한을 화면에 함께 표시한다.
- 한 번에 campaign 하나만 실행한다. endpoint를 병렬 실행하면 JFR 시간 구간의 원인 귀속이 불가능해지므로 전체·선택 실행도 직렬 처리한다.
- `QUEUED`, `RUNNING`, `CANCELLING`, `PASSED`, `FAILED`, `CANCELLED` 상태를 사용한다. 새 campaign 요청은 활성 campaign이 있으면 HTTP 409로 거절한다.
- 취소는 현재 k6 프로세스 그룹에 SIGINT를 보내 정상 summary 생성을 먼저 시도하고, 10초 안에 종료하지 않으면 SIGTERM을 보낸다. 어떤 종료 경로에서도 JFR stop·수집 trap을 실행한다.

`k6/endpoints/targets.json`은 k6 catalog와 대시보드가 함께 읽는 단일 출처다.

```json
{
  "targets": [
    {
      "key": "app-version",
      "label": "앱 버전",
      "method": "GET",
      "route": "/api/app-version",
      "suite": "read",
      "risk": "safe",
      "defaultProfile": "read",
      "defaultEnabled": true
    }
  ]
}
```

제어 서버는 target key, profile, rate/VU, duration/iteration을 manifest allowlist와 상한으로 검증한다. subprocess는 `shell=False`와 argv 배열만 사용한다. path와 command 문자열을 브라우저 입력에서 직접 조합하지 않는다.

### localhost API

| Method | Path | 역할 |
|---|---|---|
| GET | `/api/targets` | endpoint·suite·risk·기본 profile 목록 |
| GET | `/api/runs` | `artifacts/performance`에서 읽은 최근 campaign 목록 |
| POST | `/api/runs` | 단일·선택·suite·안전한 전체 campaign 생성 |
| GET | `/api/runs/{campaignId}` | target별 상태, k6 핵심 지표, artifact 목록 |
| GET | `/api/runs/{campaignId}/events` | 진행 로그와 상태 변경을 보내는 SSE |
| POST | `/api/runs/{campaignId}/cancel` | 활성 campaign 취소 |
| GET | `/api/runs/{campaignId}/artifacts/{artifactId}` | manifest에 등록된 로컬 artifact 다운로드 |
| GET | `/api/runs/{campaignId}/bundle` | manifest, HTML, JSON, task별 JFR을 ZIP으로 스트리밍 |

artifact route는 run manifest에서 artifact ID를 역조회하고, `Path.resolve()` 결과가 해당 run directory 내부인지 확인한다. 임의 경로나 `..`를 받지 않는다. `report.html`은 새 localhost 탭에서 볼 수 있도록 `Content-Disposition: inline`과 sandbox CSP로 제공한다. JFR은 `application/octet-stream`, ZIP은 `application/zip`, JSON·manifest는 실제 media type과 `Content-Disposition: attachment`로 스트리밍하며 전체 파일을 메모리에 올리지 않는다.

SSE는 target 시작·종료, 단계, sanitized console line, 최종 상태만 보낸다. `Authorization`, `Bearer`, token·secret 이름을 포함한 환경변수 값은 저장하거나 전송하지 않는다. 새로고침 후에는 `GET /api/runs/{campaignId}`로 현재 상태를 복구하고 SSE를 다시 연결한다.

### 화면 구조와 사용성

이 화면은 마케팅 페이지가 아니라 로컬 운영 command surface다. 구현 전에 `tools/perf_dashboard/DESIGN.md`에 색·타입·간격·상태·접근성 token, scroll ownership, 반응형 규칙을 고정한다.

- 고정 header: 대상 환경 `dev`, 연결 상태, 활성 campaign 상태
- endpoint catalog: 검색, suite·risk filter, 전체/개별 선택, method·route·비용 경고
- 실행 설정: profile, rate/VU, duration/iterations, JFR on/off, 실행·취소
- 실행 진행: target별 단계, 경과 시간, sanitized live log
- 결과: p95, p99, 실패율, dropped iteration, threshold 판정, 이전 실행 비교
- artifact: endpoint HTML report 보기, summary·manifest·task별 JFR·전체 bundle 다운로드

desktop은 고정 header와 catalog/main list-detail shell을 사용하고 main panel만 세로 scroll한다. 375px에서는 한 열로 reflow하며 가로 scroll을 만들지 않는다. 모든 작업은 키보드로 가능해야 하고 focus ring, 명시적 label, 상태 텍스트를 제공한다. 색만으로 성공·실패를 표현하지 않는다. loading, empty, error, cancel, 긴 endpoint·run ID, 끊긴 SSE 상태를 실제 브라우저에서 검증한다. 자동 motion은 사용하지 않고 상태 변경 feedback만 제공하며 `prefers-reduced-motion`을 존중한다.

## 결과 산출물

k6 core의 JSON summary와 저장소 내부 HTML renderer를 사용한다. 테스트 중 원격 JavaScript를 내려받지 않는다.

```text
artifacts/performance/<campaign-id>/
├── campaign.json
├── campaign.log
└── <target>/
    ├── report.html
    ├── summary.json
    ├── manifest.json
    ├── console.log
    ├── task-<short-id-a>.jfr
    └── task-<short-id-b>.jfr
```

`manifest.json`은 다음 정보를 기록한다.

```json
{
  "campaignId": "20260831T120000Z",
  "runId": "20260831T120000Z-foods-search-ko-hit",
  "target": "foods-search-ko-hit",
  "baseUrl": "https://dev.kbap.site",
  "gitSha": "40-char-sha",
  "taskDefinition": "kbap-dev-ecs-api:15",
  "image": "repository:tag",
  "taskIds": ["task-a", "task-b"],
  "startedAt": "2026-08-31T12:00:00Z",
  "finishedAt": "2026-08-31T12:07:00Z",
  "jfrEnabled": true
}
```

access token, JWT secret, Firebase credential, 외부 API key, presigned URL은 manifest와 로그에 기록하지 않는다. `artifacts/performance/`와 실제 fixture JSON은 `.gitignore`에 추가한다.

## 부하 모델

### 읽기·로컬 DB API

`constant-arrival-rate`로 coordinated omission을 피한다.

1. 1 VU 1회 contract smoke
2. 2분 warm-up
3. 5 RPS 3분 baseline
4. 10, 20, 40 RPS를 각 3분
5. `dropped_iterations > 0`, 오류율 1% 초과, 또는 p95가 직전 단계의 2배를 넘으면 중단

### 가역 쓰기 API

고정된 35번 회원을 사용하되 대상 ID를 순환한다. 일반 처리량 측정은 서로 다른 대상 키를 사용하고, 동일 키 경합은 별도 target으로 둔다.

1. 1회 smoke와 원상복구 확인
2. 1, 2, 5, 10 RPS를 각 2분
3. 성공 건수와 최종 DB 상태를 함께 검증

### fixture 생성 쓰기 API

리뷰, 신고, 커뮤니티 생성, 이미지 완료, 주문 생성은 `run_id`가 포함된 fixture를 사용한다. soft delete만으로 행이 물리 삭제되지 않는 API는 캠페인 종료 후 명시적 정리 SQL을 실행한다. 정리 대상은 `run_id`로 식별 가능한 행으로 제한한다.

### 외부 비용 API

Places, 역지오코딩, 스캔은 `per-vu-iterations`로 총량을 고정한다. 외부 서비스별 비용 상한과 quota를 manifest에 기록하되 credential 값은 기록하지 않는다.

- Places: 1, 5, 10 동시 요청
- 역지오코딩 주문: 좌표 없음과 좌표 있음 분리
- 스캔: 기존 5, 50, 145 계단을 재사용하되 JFR과 일반 API side load를 추가
- 스캔 환율: `currency=KRW`와 비KRW 분리

## 엔드포인트 카탈로그

### 읽기

- `GET /api/app-version`
- `GET /api/home`: 인증, 비인증
- `GET /api/ingredients`: ko, en
- `GET /api/ingredients/diets`: ko, en
- `GET /api/members/me/profile`
- `GET /api/members/me/ranking`
- `GET /api/members/me/blocks`
- `GET /api/foods`: 인증, 비인증, 첫 cursor, 다음 cursor
- `GET /api/foods/search`: ALL·SCANNED, ko·en, hit·miss, 첫 cursor·다음 cursor
- `GET /api/foods/scanned`: 첫 cursor, 다음 cursor
- `GET /api/foods/{foodId}`: 인증, 비인증
- `GET /api/bookmarks`: 첫 cursor, 다음 cursor
- `GET /api/reviews`: 인증·비인증, latest·rating_high·rating_low·food_review_count·helpful, 첫 cursor·다음 cursor
- `GET /api/reviews/me`: 첫 cursor, 다음 cursor
- `GET /api/community/posts`: 인증·비인증 첫 페이지, 인증 다음 페이지
- `GET /api/community/posts/{postId}`
- `GET /api/community/posts/{postId}/comments`: 첫 cursor, 다음 cursor
- `GET /api/orders`: size 10·30, 첫 cursor·다음 cursor
- `GET /api/orders/{orderId}`

### 가역 쓰기

- `PATCH /api/members/me/profile`: API 1.0, 1.1
- `POST /api/members/me/blocks`
- `DELETE /api/members/me/blocks/{targetMemberId}`
- `POST /api/bookmarks`
- `PATCH /api/bookmarks/{foodId}`
- `PATCH /api/reviews/{reviewId}`
- `POST /api/reviews/{reviewId}/like?liked=true`
- `POST /api/reviews/{reviewId}/like?liked=false`
- `PUT /api/community/posts/{postId}`
- `PUT /api/community/comments/{commentId}`

### fixture 생성·소비

- `POST /api/reviews`
- `DELETE /api/reviews/{reviewId}`
- `POST /api/reports`
- `POST /api/community/posts`
- `DELETE /api/community/posts/{postId}`
- `POST /api/community/posts/{postId}/comments`
- `DELETE /api/community/comments/{commentId}`
- `POST /api/images/upload-url`
- `POST /api/images/complete`
- `POST /api/orders`: 좌표 없음, 좌표 있음

### 외부·비용

- `GET /api/places/nearby`
- `GET /api/places/search`
- `POST /api/scans/tickets`
- `POST /api/scans`: API 1.0
- `POST /api/scans`: API 2.0, KRW
- `POST /api/scans`: API 2.0, 비KRW

### 기존 API 테스트로 contract만 검증하고 k6 처리량 측정에서 제외

- `POST /api/auth/login`
- `POST /api/auth/refresh`
- `POST /api/auth/logout`
- `POST /api/members/me/onboarding`: API 1.0, 1.1
- `PATCH /api/auth/withdraw`
- `/api/admin/**`
- `/admin/**`
- `/internal/batch/**`

인증·인가 준비는 35번 access token 직접 생성으로 끝낸다. 로그인·refresh·logout 자체의 처리량은 이 성능 캠페인의 목적이 아니며, 온보딩·탈퇴·관리자·batch 상태 전이는 공유 dev 데이터 보호를 위해 k6 target으로 만들지 않는다.

## 최우선 병목 가설

1. `/api/home`의 `ORDER BY RAND()`와 회원·최근 스캔·북마크·평점 직렬 조회
2. `/api/foods/search`의 `%keyword%`와 `JSON_EXTRACT` 전체 스캔
3. `/api/reviews`의 `food_review_count` 상관 서브쿼리와 `helpful` group by
4. `/api/foods/{foodId}`의 음식·재료·자격·북마크·최근 리뷰·작성자·좋아요·평점 query fan-out
5. OpenAI, Google Places, 역지오코딩, 환율 호출의 servlet thread 점유
6. 요청당 UUID·MDC·INFO 2건과 CloudWatch stdout 비용
7. `(member_id, food_id)` UNIQUE가 없는 북마크의 check-then-insert 경합
8. 35번 회원 하나로 스캔할 때 `member.scan_count` 단일 행 갱신 경합

이 작업은 위 문제를 수정하지 않는다. 각 가설을 독립 시나리오와 증거로 확인해 후속 최적화 작업의 입력을 만든다.

## 판정 기준

### k6 초기 진단 기준

- 로컬 읽기 API: p95 300ms 미만, p99 750ms 미만
- 로컬 쓰기 API: p95 500ms 미만, p99 1s 미만
- `http_req_failed` 1% 미만
- `dropped_iterations=0`
- BaseResponse `success=true` 비율 99% 이상

이 값은 제품 SLO가 아니라 첫 병목 탐색용 정지선이다. 최종 기준은 baseline과 실제 트래픽 목표를 바탕으로 별도 확정한다.

### 원인 분류

| 관측 | 판정 |
|---|---|
| JFR ExecutionSample이 특정 앱 메서드에 집중하고 task CPU가 높음 | 애플리케이션 CPU 병목 |
| Hikari pending·acquire가 상승하고 JFR이 `HikariPool.getConnection`에서 대기 | DB 풀 고갈 |
| 앱 CPU는 낮고 RDS DB load·특정 SQL digest가 상승 | SQL·인덱스 병목 |
| JFR `SocketRead`가 OpenAI·Google client stack에 집중 | 외부 서비스 대기 |
| Tomcat busy/max 접근과 k6 dropped 동반 | servlet thread 포화 |
| allocation rate와 GC pause 동반 상승 | 객체 할당·GC 병목 |
| FileWrite·Logback·UUID stack 집중 | 요청 로깅 비용 |
| 같은 대상 키에서만 지연·lock wait 증가 | hot-row 또는 fixture 경합 |

JFR은 SQL 실행 계획을 대신하지 않는다. 각 DB 병목 후보는 RDS Performance Insights 또는 Performance Schema digest와 `EXPLAIN ANALYZE`로 확정한다.

## 캠페인 순서

1. 관측·profiling 인프라 적용
2. 대표 GET API로 JFR off/on 오버헤드 확인
3. 로컬 HTML 대시보드에서 안전한 전체 실행으로 읽기 카탈로그 측정
4. 검색·리뷰 정렬·상세·홈 집중 측정
5. 가역 쓰기와 동일 키 경합 측정
6. fixture 생성 쓰기 측정과 정리
7. 외부 비용 API 제한 측정
8. 기존 스캔 burst와 일반 API 10 RPS side load 동시 실행
9. 병목별 증거와 후속 개선 후보 정리

## 완료 조건

- 모든 포함 endpoint target이 smoke를 통과한다.
- 읽기·쓰기·외부 target마다 실행 모델과 상한이 명시돼 있다.
- localhost HTML 대시보드에서 단일·선택·suite·안전한 전체 실행과 취소가 가능하다.
- 새로고침 후 실행 상태와 과거 결과를 다시 조회하고 endpoint HTML·JSON·manifest·task별 JFR·ZIP bundle을 다운로드할 수 있다.
- 브라우저 응답, SSE, log, manifest에 token·secret·AWS credential·S3 URL이 없다.
- 각 본 측정에 HTML, JSON, manifest, 두 태스크 JFR이 존재한다.
- Prometheus에서 endpoint p95/p99와 Tomcat busy thread를 조회할 수 있다.
- JFR 파일에 초기 환경변수·시스템 속성 이벤트가 없다.
- fixture 정리 후 35번 회원과 dev 서비스가 정상 상태다.
- 각 병목 주장은 k6, JFR, Prometheus, RDS 중 최소 두 종류의 증거를 가진다.
- 캠페인 종료 후 일반 dev 이미지와 steady state가 복구된다.
