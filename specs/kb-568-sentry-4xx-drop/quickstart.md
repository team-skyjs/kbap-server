# Quickstart: 검증 절차

## 1. 기존 테스트 회귀

자동 테스트 없음(plan Complexity Tracking, research R8). 기존 스위트만 그대로 통과하면 된다:

```bash
./gradlew :api:test
```

arch 태그 포함. 테스트 컨텍스트는 DSN 부재로 Sentry 미기동이라 컨텍스트 수·결과 무변화.

## 2. 로컬 — SDK 가 실제로 버리는지 (가짜 DSN + debug)

```bash
docker compose up -d mysql redis
set -a; source ../../.env; set +a          # 워크트리엔 .env 없음
API_SENTRY_DSN='https://k@localhost.invalid/0' SPRING_PROFILES_ACTIVE=local \
  ./gradlew :api:bootRun --no-daemon --args='--sentry.debug=true'
```

다른 터미널:

```bash
H='X-API-Version: 1.0'
curl -s -o /dev/null -w '%{http_code}\n' -H "$H" http://localhost:8080/api/foods/999999999?lang=ko   # 4xx 앱 에러 코드
curl -s -o /dev/null -w '%{http_code}\n'          http://localhost:8080/api/foods/1?lang=ko          # 400 버전 헤더 누락
curl -s -o /dev/null -w '%{http_code}\n' -H "$H" -H 'X-API-Version: 9.9' http://localhost:8080/api/foods/1?lang=ko  # 400 미지원 버전
curl -s -o /dev/null -w '%{http_code}\n' -H "$H" http://localhost:8080/api/foods/abc?lang=ko         # 400 타입 불일치
curl -s -o /dev/null -w '%{http_code}\n' -H "$H" -X DELETE http://localhost:8080/api/app-version     # 405
curl -s -o /dev/null -w '%{http_code}\n' -H "$H" --max-time 0.05 http://localhost:8080/v3/api-docs   # 끊김 유발
```

기대 로그: 위 요청마다 `Event was dropped by a processor` (또는 404 는 `... is ignored`), `Sending the event` 없음. 비교로 5xx 를 내는 경로(있으면)는 `Capturing event` 후 전송 시도 로그(가짜 호스트라 실패는 정상).

## 3. dev — Sentry 콘솔 (SC-001~004)

api dev 배포 후 `kbap-server-dev` 프로젝트를 열어 둔 채:

| # | 요청 | 기대 |
|---|---|---|
| 1 | 4xx 앱 에러 코드(없는 음식 id·게스트의 회원 전용 호출) 각 ×3 | 응답 종전, 새 이벤트 **0건** |
| 2 | 버전 헤더 누락·미지원·타입 불일치·잘못된 본문·405 각 ×3 | 응답 종전, 새 이벤트 0건 |
| 3 | 큰 응답 요청 중 연결 끊기 ×5 | 새 이벤트 0건 |
| 4 | 500 유발 요청 또는 기존 5xx 이슈 | 종전대로 이벤트, 태그 6종 동일 |
| 5 | 서버 로그(CloudWatch) | 1·2 의 `request failed ... 4xx` WARN 라인이 종전대로 남음 |
| 6 | 7일 후 이슈 목록 | `http.status` 4로 시작하는 이벤트 0건 |

결과를 PR 본문 "검증" 에 적는다.

## 4. 회귀 방어

- 판정 경계를 고정하는 테스트가 없다 — 프로세서 diff 는 리뷰에서 contracts §1 표와 대조하고, 배포 후 §3 의 4xx 0건·5xx 1건으로 양방향 회귀를 본다.
- `exception-resolver-order` 줄 삭제는 5xx 수집을 조용히 없앤다 — `sentry:` 블록 diff 는 리뷰에서 본다.
