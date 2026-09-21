# Quickstart: KB-614 검증

## 자동 검증

```bash
./gradlew :common:test     # 리포지토리 쿼리, 발송기 순차, 영수증 어댑터, PushReceiptService, 유형 기록
./gradlew :batch:test      # 발송 잡, 리더, 영수증 잡 2개
./gradlew :api:test        # 마이그레이션 + 엔티티 정합(validate), api 발송 시나리오, ArchUnit
./gradlew build            # 머지 전 전체
```

Kotest 는 Gradle `--tests` 필터를 무시하므로 모듈 단위로 돌린다.

## 확인할 것

**발송 잡**

1. 실행 기록에 스텝이 `scanSuggestion{Lunch|Dinner}SendStep` 하나뿐이다.
2. 대상 250명 → `FakePushClient` 호출이 100·100·50.
3. 같은 슬롯 재실행 → 발송 0건, `filterCount` = 대상 수.
4. 점심·저녁 잡의 슬롯 문구.
5. `kbap.push.dispatch` 이름·태그 불변.
6. 슬롯 경계가 11:00·17:00.

**발송기**

7. 250건 발송 중 동시에 진행 중인 요청 최대 1.

**영수증 잡**

8. ok → `DELIVERED`. `DeviceNotRegistered` → `FAILED` + 기기 `token_invalid_at` + 알림함 소프트 삭제.
9. `MessageRateExceeded` 3연속 → 발송 이력 3건 전부 `FAILED`, 네 번째 발송 없음, 알림함 소프트 삭제.
10. 재전송은 그 기기 토큰으로만 나가고 같은 `notification_id` 를 쓴다.
11. 광고성 잡은 광고성 유형만, 활동 잡은 활동 유형만 바꾼다. 유형 NULL 행은 둘 다 건드리지 않는다.
12. 15분 미만·24시간 초과 `SENT` 는 그대로다.
13. 영수증 조회 예외 → 그 묶음 `SENT` 유지, 잡 `COMPLETED`.

**삭제 확인**

```bash
grep -rnE "ScanSuggestionCandidateDto|ScanSuggestionTargetTasklet|findMemberIdsByNewsTrue\(\)|min-request-interval|member-chunk-size|awaitSlot|newFixedThreadPool" --include="*.kt" --include="*.yml" api/src batch/src common/src
```

결과가 비어야 한다.

## 로컬 수동 실행 (선택)

배치를 띄우고 HTTP 트리거로 `scanSuggestionLunchPushJob`, `marketingPushReceiptSyncJob`, `activityPushReceiptSyncJob` 을 부른다. 기동 로그에서 스케줄 등록(11:00·17:00, 10분·15분 주기)을 확인한다. 워크트리에서는 메인 체크아웃의 `.env` 를 `set -a; source` 로 읽고 포트·스키마를 분리한다. 실제 Expo 로 나가므로 `kbap.push.expo.base-url` 을 가짜 서버로 돌리거나 테스트 기기만 시드한다.

## 배포 시 주의

- **순서: api 먼저, batch 나중.** 배치의 새 코드는 `notification_type` 컬럼에 쓴다. 스키마 owner 는 api 다.
- 배포 중 구 api 가 만든 도움돼요 발송 이력은 유형이 NULL 이다 — 영수증 확인 없이 24시간 뒤 미확인 종결된다(수 분치, 의도된 동작).
- 제거·추가되는 설정 키는 전부 yml 기본값으로 동작한다. 환경변수·IaC 매핑 없음(저장소 확인) — 태스크 정의 수정 불필요.
- 배포 당일 슬롯 경계가 12:00·18:00 에서 11:00·17:00 으로 바뀐다. 11:00~12:00 사이 배포 시 그날 점심 발송이 한 번 빠질 수 있다 — 발송 시각 밖(예: 14시~16시)에 배포한다.
- 다음 릴리스 후속: `notification_type` NOT NULL 전환.
