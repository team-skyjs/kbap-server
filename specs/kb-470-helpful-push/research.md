# Research: HELPFUL 발송 — 리뷰 좋아요 시 작성자에게 알림

Technical Context 에 NEEDS CLARIFICATION 은 없다. 아래는 설계 결정과 기각한 대안이다.

## 1. 발송 주체 — api 커밋 이후 비동기 (Jira 의 아웃박스+batch 기각)

- **Decision**: `ReviewService.likeReview` 가 트랜잭션 안에서 `ReviewLiked` 이벤트를 발행하고, api 의 리스너가 `@Async` + `@TransactionalEventListener(phase = AFTER_COMMIT)` 로 받아 공용 `PushHandler.send(PushRequest(HELPFUL, …))` 를 부른다. 새 테이블·새 잡 없음.
- **Rationale**: `BackgroundConfig` 가 이미 `@EnableAsync`, `LlmCallCostEventListener` 가 `@Async @EventListener` 선례. 활동 알림은 유실을 감수해도 되고(데이터 정합 무관) 즉시성이 가치다. 아웃박스+잡은 테이블·스케줄·api 2대 대비 중복 실행 방어(ShedLock)까지 요구한다.
- **Alternatives**: (a) Jira 원안 아웃박스 row + batch 폴링 — 유실 방지 대가가 큼, 기각. (b) `@EventListener`(커밋 전) — 롤백된 좋아요에 알림이 감, 기각. (c) 컨트롤러가 서비스 응답 뒤 직접 send — 트랜잭션 밖이긴 하나 요청 스레드를 Expo 왕복만큼 잡음, 기각.

## 2. `@Async` 와 `AFTER_COMMIT` 의 결합이 필요한 이유 (테스트 동기 실행 기각)

- **Decision**: 리스너는 반드시 별도 스레드(`@Async`)에서 돈다. 테스트에서 `SyncTaskExecutor` 로 동기화하지 않는다.
- **Rationale**: 동기 `AFTER_COMMIT` 리스너는 커밋 직후 **원 트랜잭션의 리소스가 아직 바인딩된 상태**에서 실행된다 — 그 안에서 `PushDispatchService.prepare/record` 의 `@Transactional(REQUIRED)` 는 원 트랜잭션에 참여하고 **커밋되지 않는다**(Spring 문서: afterCommit 안의 데이터 접근은 커밋되지 않음). `@Async` 로 스레드를 바꾸면 리스너 스레드에 트랜잭션이 없어 `prepare`·`record` 가 각자 새 트랜잭션을 연다. 리스너 메서드 자체에는 `@Transactional` 을 달지 않는다(readOnly 로 감싸면 안의 save 가 flush 되지 않는다).
- **Alternatives**: 테스트용 `AsyncConfigurer(SyncTaskExecutor)` — 위 이유로 알림 행이 조용히 저장되지 않는 함정, 기각. 테스트는 `LlmCallCostEventListenerTest` 와 같이 Kotest `eventually`(긍정)·`continually`(부정) 로 검증한다.

## 3. 트리거 판정 위치 — 발행 시점에 걸러서 이벤트 자체를 안 낸다

- **Decision**: `likeReview` 가 (a) 리뷰를 `findById` 로 읽어 작성자·음식 id 를 얻고, (b) `findByReviewIdAndMemberId` 가 null 일 때만 "새 좋아요" 로 보며, (c) 작성자 ≠ 좋아요 회원일 때만 `ReviewLiked(reviewId, authorMemberId, foodId)` 를 발행한다. 취소(`unlikeReview`)는 발행하지 않는다.
- **Rationale**: 셋 다 요청 트랜잭션 안에서 이미 아는 값이라 리스너에서 다시 조회할 이유가 없다. `@SQLRestriction` 이 삭제 행을 숨기므로 취소 후 재등록은 "새 좋아요" 로 보인다 — 도배 방어는 §4 묶음 창이 맡는다. `existsById` → `findById` 로 바뀌지만 쿼리 수는 같다.
- **Alternatives**: `upsertActive` 의 affected rows 로 신규/부활/중복을 구분 — MySQL `ON DUPLICATE KEY UPDATE` 는 변경 없으면 0·갱신이면 2·삽입이면 1 을 주지만 `updated_at = NOW(6)` 때문에 항상 갱신되어 중복 재호출도 2 → 구분 불가, 기각.

## 4. 묶음 정책 — 리뷰당 1시간 창, 알림함 행 기준

- **Decision**: 리스너가 `NotificationJpaRepository.findByMemberIdAndTypeAndCreatedAtAfter(authorId, HELPFUL, now - 1h)` 로 작성자의 최근 HELPFUL 행을 읽고 `data["reviewId"]` 가 같은 행이 하나라도 있으면 건너뛴다. 창 길이는 리스너의 상수(`Duration.ofHours(1)`).
- **Rationale**: 작성자의 최근 1시간 HELPFUL 행은 손에 꼽는 수라 메모리 필터로 충분하고, JSON 컬럼 네이티브 쿼리(`JSON_EXTRACT`)나 새 컬럼이 필요 없다. 전송 실패 행은 `record` 가 소프트 삭제하므로 자동 제외(spec FR-005). 값은 환경별로 바뀌지 않으므로 프로퍼티로 빼지 않는다(ponytail — 바뀌면 상수 한 줄).
- **Alternatives**: (a) `review_like.updated_at` 기반 판정 — 취소·재등록도 갱신되어 부적합. (b) 읽지 않은 HELPFUL 행이 있으면 억제 — 기기 두 대의 읽음 상태가 갈리고 알림함을 안 여는 작성자는 영영 안 받음, 기각. (c) `notification` 에 `review_id` 컬럼 추가 — 마이그레이션·리비전 공존 비용 대비 이득 없음, 기각.

## 5. 음식 이름 — 기기 언어별 인자 `argsByLang`

- **Decision**: `PushRequest` 에 `argsByLang: Map<LanguageCode, Map<String, String>> = emptyMap()` 을 추가하고 `PushDispatchService.prepare` 가 기기마다 `argsByLang[lang] ?: args` 로 렌더링한다. 리스너는 `Food.displayName(lang)` 을 10개 언어 전부에 대해 계산해 넘긴다(메모리 계산, 쿼리 1개).
- **Rationale**: 대상 기기는 파이프라인 안에서 확정되므로 트리거는 기기 언어를 모른다. 회원 엔티티에는 언어가 없다. 언어별 인자 맵이 가장 작은 변경(prepare 1줄 + 필드 1개)으로 기기 언어와 음식 이름 언어를 일치시킨다. `LanguageCode.from(lang)` 이 미지원 코드를 `EN` 으로 접으므로 맵 키는 항상 맞는다.
- **Alternatives**: (a) 한국어 이름 하나 — 외국인 대상 앱에서 영어 푸시에 한글 음식명, 기각. (b) `args: (LanguageCode) -> Map` 함수 타입 — 기존 `PushRequest(args = mapOf(...))` 호출 전부 깨짐, 기각.

## 6. Android 채널 — `NotificationType.channelId` 를 3분기로

- **Decision**: `channelId = when { marketing -> "news"; else -> "activity" }` 가 아니라 명시 매핑: HELPFUL·REVIEW_REMINDER → `"activity"`, 광고성 → `"news"`. `"default"` 는 사라진다(FE 가 default 채널을 만들지 않으므로 어떤 유형도 default 로 가면 안 된다 — KB-498).
- **Rationale**: 유형→채널 매핑은 KB-471 이 `NotificationType` 한 곳에 뒀다(Jira 권고와 일치). `PushDispatchServiceTest` 의 "비광고성은 default" 단언을 `activity` 로 바꾼다.

## 7. 관측 — 로그 + 발송 이력, 카운터·재시도 없음

- **Decision**: 리스너는 `try/catch` 로 예외를 삼키고 `log.error`, 정상 결과는 `log.info`(sent/failed). 메트릭 카운터는 올리지 않는다.
- **Rationale**: 발송 결과는 `notification_dispatch` 가 건별로 남긴다. `kbap.push.dispatch` 상수는 batch `ScanSuggestionPushWriter` 안에 private 이라 api 가 쓰면 문자열 중복이고, api 의 기존 발송자(관리자 테스트 발송)도 카운터를 올리지 않는다(2026-09-16 확인). Expo 일시 실패 재시도는 어댑터(`ExpoPushSender`)가 이미 소유한다.

## 8. 이벤트·리스너 위치

- **Decision**: 이벤트 `com.kbap.api.review.ReviewLiked`(발행자 소속 vocabulary), 리스너 `com.kbap.api.notification.HelpfulPushListener`(알림 기능이 리뷰 사건을 구독). api 기능 패키지 간 참조는 ArchUnit 도메인 맵 대상이 아니다(헌법 II).
- **Alternatives**: `common.domain` 에 이벤트 — batch 소비자가 없으므로 승격 기준 미달(ADR-0016), 기각. 리스너를 `api.review` 에 — 알림 정책(묶음·채널·문구)이 리뷰 패키지로 새어 들어감, 기각.
