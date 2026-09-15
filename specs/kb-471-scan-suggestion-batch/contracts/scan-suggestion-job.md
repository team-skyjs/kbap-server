# Contract: `scanSuggestionLunchPushJob` · `scanSuggestionDinnerPushJob`

## 1. 잡 이름·트리거

- 잡 이름 `scanSuggestionLunchPushJob`(문구 슬롯 LUNCH)·`scanSuggestionDinnerPushJob`(DINNER). 스텝 `scanSuggestionTargetStep`(공유) → `scanSuggestionLunchSendStep` / `scanSuggestionDinnerSendStep`.
- 스케줄: `BatchJobScheduler.pushLunchScanSuggestions()` 12:00 KST → 점심 잡, `pushDinnerScanSuggestions()` 18:00 KST → 저녁 잡(코드 상수). 환경변수 없음. 분산 락 없음(배치 1대). 같은 인스턴스 내 중복은 launcher 의 AlreadyRunning 가드.
- 수동: `POST /internal/batch/jobs?jobName=scanSuggestionLunchPushJob`(또는 `...DinnerPushJob`) → 202 `{executionId}`; `GET /internal/batch/executions/{id}` 로 상태(기존 계약 그대로). 문구는 잡 이름이, 슬롯 상한은 현재 시각이 정한다.
- 부팅 자동 실행 없음(`spring.batch.job.enabled=false`).

## 2. 종료 코드

| 상황 | status | exitCode |
|------|--------|----------|
| 발송 수행(0건 포함) | COMPLETED | COMPLETED |
| Expo 청크 실패 포함 | COMPLETED | COMPLETED (dispatch 에 FAILED, 해당 notification 은 소프트 삭제) |
| DB 오류 등 예외 | FAILED | FAILED |

## 3. 공용 발송 부품 (port `PushHandler`) — 호출자 계약

```kotlin
interface PushHandler { fun send(request: PushRequest): PushDispatchResult }
// PushRequest(type: NotificationType, memberIds: Collection<Long>, args: Map<String,String> = {}, data: Map<String,Any> = {}, ttlSeconds: Int? = null)
// PushDispatchResult(sent: Int, failed: Int)
```

- 호출자(관리자 테스트 발송·스캔 제안 배치·식사시간 잡·리뷰 리마인더·도움돼요)는 **유형·대상 회원·유형별 인자**만 넘긴다. 기기 판정(토글·동의·토큰)·언어별 렌더·광고 표기·채널·알림함/발송 이력 저장·청크·동시성·페이싱·재시도·결과 기록은 부품 안이다.
- 동기 호출이며 Expo 실패로는 예외를 던지지 않는다(`failed` 로 센다). DB 오류는 전파된다. 대상 0 이면 `(0, 0)` 이고 Expo 를 부르지 않는다.
- 조립: api `api.core.config.PushConfig`·batch `batch.config.PushConfig` 가 `ExpoPushSender`(어댑터) + `ExpoPushHandler`(구현) 빈을 만든다. 기능 코드는 port 만 참조(ArchUnit).

## 4. Expo 메시지 (어댑터 ↔ Expo) — KB-468 계약 개정

항목 `{ to, title, body, data, sound: "default", priority: "high", channelId, ttl? }`.

| 필드 | 값 |
|------|-----|
| `channelId` | 광고성 유형(SCAN_SUGGESTION·NEWS·MEAL_TIME) `"news"`, 그 외 `"default"` — `NotificationType.channelId` |
| `ttl` | 초 단위 정수, 트리거가 지정한 경우에만 직렬화(스캔 제안 배치 10800). 미지정 시 필드 생략(Expo 기본 4주) |
| `data` | `{ type: "SCAN_SUGGESTION", notificationId: <number> }` (KB-468 FE 계약 그대로 — 점심/저녁은 문구만 다르고 type 은 하나) |
| `title`/`body` | 슬롯별 템플릿(`PushTemplates.bySlot`) + `(광고) ` 접두 + 수신거부 안내 "프로필 > 알림 설정" |

요청당 ≤100 항목(기존). 청크 요청은 동시 최대 `concurrency`(기본 6)개, 요청 **시작** 간격 ≥ `min-request-interval`(기본 170ms → ≈590건/s). 응답 티켓은 입력 순서대로 합쳐 돌려준다.

### 재시도 (어댑터 — api·batch 공통)

| 실패 | 분류 | 동작 |
|------|------|------|
| 네트워크 오류·타임아웃(`ResourceAccessException`) | 일시 | 지수 백오프 재시도 — 대기 1s → 2s → 4s, 최대 3회 재시도 |
| HTTP 429 | 일시 | 동일 |
| HTTP 5xx | 일시 | 동일 |
| HTTP 400·401·403 등 그 밖의 4xx | 영구 | 재시도 없이 그 청크 전부 error 티켓(`HttpClientErrorException...: <status>`) |
| 응답 파싱 실패 | 영구 | 동일 |
| 200 안의 건별 `status=error` 티켓 | 메시지 단위 | 재시도 없음, `details.error`/`message` 를 그대로 |

재시도 소진 시 그 청크 전부 error 티켓(마지막 오류 `<ExceptionSimpleName>: <message>`, 255자 절단). 어댑터는 예외를 던지지 않는다(기존 불변).

## 5. 로그·메트릭

- `스캔 제안 대상 확정 candidates={} excludedThisSlot={} targets={}` (tasklet — 전부 회원 수)
- `스캔 제안 발송 slot={} members={} sent={} failed={}` (writer, 회원 묶음마다)
- Micrometer 카운터 `kbap.push.dispatch{type="SCAN_SUGGESTION", result="sent"|"failed"}` + 기존 `spring.batch.job`·`spring.batch.step`(status·duration·write count).

## 6. 설정 (환경별 조정 지점)

| 키 | 환경변수 | 기본 |
|----|----------|------|
| `kbap.batch.scan-suggestion.member-chunk-size` | — | `500` |
| `kbap.batch.scan-suggestion.ttl` | — | `3h` |
| `kbap.push.expo.concurrency` (api·batch) | — | `6` |
| `kbap.push.expo.min-request-interval` (api·batch) | — | `170ms` |
| `kbap.push.expo.retry.max-retries` (api·batch) | — | `3` |
| `kbap.push.expo.retry.initial-delay` (api·batch) | — | `1s` |
| `kbap.push.expo.retry.multiplier` (api·batch) | — | `2.0` |
