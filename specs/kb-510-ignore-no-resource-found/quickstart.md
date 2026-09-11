# Quickstart: 검증 절차

자동 테스트 없음(plan Complexity Tracking). 아래 두 단계로 확인한다.

## 1. 로컬 — SDK 가 실제로 버리는지 (가짜 DSN + debug)

DSN 이 있어야 자동구성이 뜬다. 존재하지 않는 호스트의 DSN 을 주면 전송만 실패하고 drop 판정 로그는 그대로 찍힌다.

```bash
docker compose up -d mysql redis
set -a; source ../../.env; set +a          # 워크트리엔 .env 없음 — 메인 체크아웃 것을 읽는다
API_SENTRY_DSN='https://k@localhost.invalid/0' SPRING_PROFILES_ACTIVE=local \
  ./gradlew :api:bootRun --no-daemon --args='--sentry.debug=true'
```

다른 터미널:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-API-Version: 1.0' http://localhost:8080/api/no-such-path   # 404
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/                                         # 404
```

기대 로그: `Event was dropped as the exception org.springframework.web.servlet.resource.NoResourceFoundException is ignored`. 응답 본문은 종전과 같은 `COMMON-002` 봉투. 비교로 `GET /api/foods/999999999`(존재하는 경로·없는 id, 헤더 포함) 는 drop 로그 없이 전송 시도 로그가 찍혀야 한다.

## 2. dev — Sentry 콘솔 (SC-001·002)

api dev 배포 후(`deploy-dev` 워크플로), Sentry `kbap-server-dev` 프로젝트를 열어 둔 채:

| # | 요청 | 기대 |
|---|---|---|
| 1 | `GET https://dev.kbap.site/api/no-such-path` ×10 (`X-API-Version: 1.0`) | 404 응답, 새 이벤트 **0건** |
| 2 | `GET https://dev.kbap.site/` ×10 | 404 응답, 새 이벤트 0건 |
| 3 | 앱 에러 코드 4xx(예: 없는 음식 id) | 1분 안에 이벤트, `http.status`·`error.code` 태그 |
| 4 | 5xx 유발 가능한 요청(있으면) 또는 기존 5xx 이슈 확인 | 종전대로 수집 |

결과를 PR 본문 "검증" 에 적는다.

## 3. 회귀 방어

- 클래스명 오타: api 가 부팅하지 않는다(바인딩 실패) — dev 배포 즉시 드러남.
- 설정 줄 삭제: 조용히 종전(404 수집)으로 돌아간다 — PR 리뷰에서 `sentry:` 블록 diff 를 본다.
