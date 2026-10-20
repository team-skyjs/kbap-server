# 홈 선정 음식 prod 최소 backport

## 기준과 범위

기준 main: b740af199472735b1870fa028ce9966f6613941f. 원본 develop Draft PR #411: 17e795b76767cfe390808aa73875f2b15ff977e9(CI·독립검토 PASS). 이 backport의 결과는 별도 검증하며 원본 검증으로 대체하지 않는다.

API 구현/테스트 9파일만 반영한다. 기존 main 인기 음식 조회와 버전 설정을 보존하고 FoodService에는 getReadyFoodSummariesInOrder 메서드 4줄만 추가한다. 마케팅 인기 캐시, 1.2/1.3/3.0 전역 버전 확장, #404 업로드/주문 이름 변경, 사진 B, 엔티티·마이그레이션·batch·workflow 변경은 포함하지 않는다.

## FE 연결 계약

GET `/api/foods/selected?ids=<고정순서CSV>&lang=en`, `X-API-Version: 1.1`.

| 요청 버전 | 이 main 기반 후보 |
|---|---|
| 1.0, 1.1, 2.0 | 동일 선정 요약 계약 |
| 누락, 1.2, 1.3, 3.0, 99.0 | 400 COMMON-002 |

FE 전역 버전을 올리지 않는다. 기존 /api/home의 응답·인기 조회는 변경하지 않는다. 이 표는 로컬 후보 계약이며 배포 전 prod에 selected 경로가 제공된다는 뜻이 아니다. 원본 #411의 develop 지원 버전 표와 혼용하지 않는다.

ids는 단일 query parameter에 양수 Long 1~10개 CSV. 공백/선행 영 정규화 후 중복, 빈 토큰, 0·음수·overflow·11개 이상·반복 ids는 400 COMMON-002. lang 필수, 미지원 언어는 en. 선택적 Bearer 인증, 잘못된 토큰은 401. memberId 파라미터로 다른 회원의 요약을 요청할 수 없다.

응답 payload.items는 기존 FoodSummaryResponse(foodId/name/koreanName/imageRef/spiciness/overallRiskStatus/publishedAt/bookmarked/review). ACTIVE·READY만 포함하며 입력 상대 순서를 유지한다. payload.unavailableFoodIds도 상대 순서대로 미존재·삭제·비공개를 묶어 반환한다. 모두 누락돼도 200. Cache-Control은 private, no-store. 조회 이벤트/스캔 이력·LLM·데이터 변경 없음.

고정 후보 10개와 순서는 FE manifest 소유다. 개인 위험도 필터는 반환된 같은 후보 안에서 수행하고 다른 음식으로 보충하지 않는다. 예시의 ID는 실제 선정 manifest를 뜻하지 않는다.

```json
{"success":true,"payload":{"items":[],"unavailableFoodIds":[999,998]},"message":null,"code":null}
```

```json
{"success":false,"payload":null,"message":"잘못된 요청입니다","code":"COMMON-002"}
```

## 상태와 다음 게이트

로컬 구현·검증·Draft PR까지 준비한다. main 병합, AWS 변경, 실제 dev/prod 배포, FE 실제 연동 완료, 봇 활성화는 수행하지 않는다. 사용자는 현재 CloudShell 실행 불가이므로 반복 실행 요청 없이 대기한다. 실제 배포 후 해당 환경 Swagger·HTTP·출시앱 동작을 확인해야 완료다.

이 PR을 prod에 먼저 반영하면 #404 CloudShell 패키지의 main=b740af/revision14 고정 기준이 달라지므로 #404 실행 전에 재기준화·검증해야 한다. 이미 준비한 패키지의 기준값만 바꿔 강행하지 않는다.

## 로컬 검증 결과

`./gradlew build` 성공(4분 7초). API 2,045개(2,043 통과·2 스킵), common 642개, batch 93개: 총 2,780개/실패 0. API 테스트는 이번 main 기반에서 실행했고 common·batch는 Gradle FROM-CACHE 결과를 사용했다. 신규 선정 API 31개에는 prod 지원 3버전 성공 및 미지원 3버전 거절이 포함된다. 기존 main 음식 목록·검색·상세·홈·Swagger·아키텍처 회귀를 포함한다.

별도 main-backport bootJar + 전용 MySQL8.4/Redis에서 실제 HTTP 29개 통과. 입력 순서, 두 회원 위험도·북마크 격리, 입력/인증/버전 오류, 1.1 Swagger, 조회/스캔 이력 0을 확인했다. `http-responses.json`은 합성 fixture의 실제 응답이며 prod 선정 10개 데이터는 아니다. 첫 기동은 DB 초기화 완료 전 연결 실패, readiness 확인 뒤 재기동 성공했으며 제품 코드 변경은 없었다. 검증 후 전용 API·JWT·컨테이너를 정리했다.

PR CI와 독립 검토는 제출 후 정확 HEAD로 별도 확인한다. 로컬 검증 결과를 실제 prod 배포 성공으로 표현하지 않는다.
