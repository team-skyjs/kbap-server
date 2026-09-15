# Contract: HELPFUL 발송

HTTP 계약 변경 없음. `POST /api/reviews/{reviewId}/like?liked=true|false` 의 요청·응답·상태 코드는 그대로다(KB-… 리뷰 좋아요). 이 문서는 **부수 효과**(푸시·알림함)의 계약이다.

## 1. 트리거 → 알림 발생 조건

| 상황 | HELPFUL 알림 |
|------|-------------|
| 다른 회원이 새로 좋아요(liked=true, 직전 활성 좋아요 없음) | 생성(아래 수신 조건·묶음 창 통과 시) |
| 작성자 본인 좋아요 | 없음 |
| liked=false(취소) | 없음 |
| 이미 좋아요 상태에서 liked=true 재호출 | 없음 |
| 요청 실패(REVIEW-001 등, 롤백) | 없음 |
| 취소 후 재등록 | 새 좋아요로 취급 — 묶음 창 안이면 없음, 밖이면 생성 |
| 같은 리뷰에 최근 1시간 안에 HELPFUL 알림함 행(활성)이 있음 | 없음 |

수신 조건(파이프라인 기존 규칙): 작성자에 연결된 유효 토큰 기기 중 `(회원, 기기)` 설정의 `activity = true` 인 기기. 기기마다 알림함 행 1·발송 이력 1·Expo 메시지 1.

## 2. Expo 메시지 (HELPFUL)

```json
{
  "to": "ExponentPushToken[...]",
  "title": "리뷰가 도움이 됐어요",
  "body": "김치찌개 리뷰에 누군가 ‘도움돼요’를 눌렀어요.",
  "data": { "type": "HELPFUL", "notificationId": 123, "reviewId": 9001 },
  "channelId": "activity",
  "sound": "default",
  "priority": "high"
}
```

- `title`·`body`: 기기 `lang` 의 `PushTemplates.byType[HELPFUL]`, `{food}` 는 그 언어의 음식 표시 이름(번역 없으면 ko 폴백 — `Food.displayName`).
- `ttl` 없음(활동 알림은 만료 없음).
- 채널 매핑(단일 출처 `NotificationType.channelId`): HELPFUL·REVIEW_REMINDER → `activity`, SCAN_SUGGESTION·NEWS·MEAL_TIME → `news`.

## 3. 알림함 (`GET /api/notifications` 기존 계약)

HELPFUL 행의 `data` 에 `reviewId` 가 추가된다. FE 는 `type=HELPFUL` 이면 내 리뷰 화면으로 이동하고, `reviewId` 는 향후 상세 진입용(이번 FE 범위 밖).

## 4. 타이밍·실패

- 좋아요 응답은 발송을 기다리지 않는다. 정상 상황에서 알림은 수 초 안(SC-001 10초 이내).
- 발송 실패: `notification_dispatch.dispatch_status = FAILED` + `error`, 알림함 행 소프트 삭제(기존 `record` 규칙). 재시도 없음(어댑터의 Expo 일시 실패 백오프는 별개).
- 로그: 리스너 예외 `log.error("HELPFUL 발송 실패 reviewId={} authorMemberId={}")`, 정상은 `log.info`(sent/failed). 메트릭 카운터 없음 — 건별 결과는 `notification_dispatch`.

## 5. 변경되는 내부 계약

| 대상 | 변경 |
|------|------|
| `PushRequest`(common.domain.notification) | `+ argsByLang` — 언어별 인자, 없으면 `args` |
| `NotificationType.channelId` | `"default"` 폐지 → 활동 유형 `"activity"` |
| `NotificationJpaRepository` | `+ findByMemberIdAndTypeAndCreatedAtAfter` |
| `ReviewService.likeReview` | 새 좋아요·비작성자면 `ReviewLiked` 발행(HTTP 결과 불변) |
