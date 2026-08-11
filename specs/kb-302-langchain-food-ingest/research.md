# Phase 0 Research — KB-302

명세의 NEEDS CLARIFICATION 은 0개다. 여기서는 설계 시 갈렸던 지점의 **결정·근거·기각 대안**만 남긴다.

**§2·§6·§7 은 발행(후속 티켓) 관련 결정**이다 — 이번 범위에서 구현하지 않고, 그때 다시 꺼내 쓰라고 남긴다.

## 1. 큐 발행 방식 — 아웃박스 테이블 경유

- **Decision**: 요청을 `food_content_outbox` 에 음식 등록과 같은 트랜잭션으로 적재하고, 발행 주체(후속 티켓의 배치)가 PENDING 행을 읽어 SQS 로 보낸다.
- **Rationale**: 스캔 트랜잭션 안에서 SQS 를 직접 호출하면 (a) 외부 호출이 트랜잭션을 잡고(헌법 Additional Constraints 위반), (b) 커밋 실패 시 큐에는 남고 DB 엔 없는 유령 요청이 생긴다. 아웃박스는 "음식 등록과 요청 등록이 함께 성공하거나 함께 실패"를 공짜로 준다. 관리자 일괄 재수집도 같은 경로를 타므로 발행 코드는 하나뿐이다.
- **Alternatives rejected**:
  - *동기 SQS 발행*: 일괄 재수집 500건이 HTTP 요청 하나에 묶여 응답이 길어지고, 부분 실패 복구 수단이 없다.
  - *스프링 이벤트(트랜잭션 커밋 후 발행)*: 커밋 후 프로세스가 죽으면 유실된다. 유실 방지가 이 테이블의 존재 이유다.
  - *발행까지 이번에 구현*: 사용자 결정으로 발행은 **`:batch` 모듈의 잡**이 소유한다(후속 티켓). 소비자가 없는 seam·어댑터를 미리 만들지 않는다.

## 2. 발행 주체 중복 방지 (후속 티켓)

- **Decision**: 발행이 배치 잡이면 인스턴스 중복 실행이 구조적으로 없다. 만약 api 스케줄러로 돌리게 되면 운영 api 가 2대이므로 `@SchedulerLock` 이 **필수**다(잠금 없이 돌리면 같은 요청이 두 번 발행돼 LLM 비용이 두 배).
- **Rationale**: 이미지 회수(`food-image-collect`)에 같은 패턴이 이미 있어 그때 새로 도입할 것은 없다.
- **Alternatives rejected**: 행 단위 비관적 락 — 스케줄이 하나면 과한 수단이다.

## 3. 결과 매칭 키 — `foodId`

- **Decision**: 큐 메시지와 적재 요청 모두 `foodId` 를 싣고, 적재는 `foodId` 로만 대상을 찾는다.
- **Rationale**: 재수집은 **관리자가 이름 오타를 고친 뒤** 돌리는 경우가 있다. 이름(매칭 키) 기반이면 그 순간 매칭이 깨져 결과가 갈 곳을 잃는다. 또 대상 행이 항상 선존재하므로(스캔 미보유 음식도 등록 후 요청을 쌓는다) 이름→upsert 경로 자체가 불필요해진다.
- **따라오는 단순화**: `korean_name` 파생 매칭, `uq_food_korean_name` 동시 삽입 충돌 흡수, 소프트 삭제 동명 충돌 409 분기가 **전부 사라지고** "foodId 조회 실패 → 거절" 한 경로로 통합된다.
- **Alternatives rejected**: `outboxId`(아웃박스 행 id)를 상관관계 키로 왕복 — 큐에 넣는 주체가 우리뿐이라 짝짓기 문제가 없고, 계약에 필드만 늘어난다.

## 4. 이미 READY 인 음식의 재수집 반영 — 즉시 덮어쓰기

- **Decision**: 성공 결과는 텍스트 필드만 갱신하고 상태를 바꾸지 않는다. 실패 결과는 상태·콘텐츠를 보존하고 실패 기록만 남긴다.
- **Rationale**: 서비스 노출은 `READY` 뿐이라 상태를 내리면 재수집 도중 앱에서 음식이 사라진다. 오타 수정이라는 목적 대비 부작용이 크다. 승인 대기로 보내는 대안은 검수 이득이 있으나, 수백 건 재수집마다 사람이 승인해야 해 실사용이 불가능하다.
- **계약 변경**: 기존 계약의 "이미 READY 인 음식은 덮지 않고 스킵" 규칙을 폐기한다. 큐 발행 주체가 우리뿐이라 의도치 않은 덮어쓰기가 발생할 수 없다. 지식 위키 `langchain-food-ingest-contract.md` 를 함께 갱신한다.
- **Alternatives rejected**: 새 결과를 별도 스테이징 테이블에 보관하고 승인 시 반영 — 비교·승인 화면이 통째로 필요해 이번 목적(오타 일괄 수정)에 과하다.

## 5. 사진 재활용 — 상태 규칙만으로 해결

- **Decision**: 성공 적용 시 `READY` 면 상태 유지, 그 외에는 `imageRef` 가 있으면 `PENDING_REVIEW`, 없으면 `PENDING_IMAGE`.
- **Rationale**: 이미지 생성 후보 조회(`FoodJpaRepository.findImageCandidates`)가 `PENDING_IMAGE` 만 보므로, 사진 있는 음식이 그 상태로 내려가지 않는 것만으로 재생성이 원천 차단된다. **이미지 파이프라인 코드는 한 줄도 건드리지 않는다.**
- **Alternatives rejected**: 이미지 제출 쪽에 "이미 사진 있으면 제외" 조건 추가 — 원인이 아니라 증상을 막는 자리라 상태 규칙이 바뀔 때마다 같이 틀어진다.

## 6. SQS 어댑터 위치 (후속 티켓) — 신규 `:infra:mq`

- **Decision**: seam 은 `common.port.mq.FoodContentEventPublisher`, 구현은 신규 Gradle 모듈 `:infra:mq`(`SqsFoodContentEventPublisher`), 조립은 부트앱 config. **이번 범위에서는 만들지 않는다.**
- **Rationale**: 헌법 III 이 외부 시스템 클라이언트의 seam·구현·조립 3분할을 강제한다. AWS SDK v2 BOM 은 이미 카탈로그에 있어 `sqs` 아티팩트 한 줄만 추가된다.
- **Alternatives rejected**: `:infra:storage` 에 얹기(AWS SDK 가 이미 있음) — 모듈명이 하는 일을 속이게 된다. 발행 주체에서 SDK 직접 호출 — 헌법 위반.

## 7. `SendMessageBatch` 10건 상한 (후속 티켓)

- **Decision**: 어댑터가 내부에서 10개씩 청크로 나눠 보내고, **성공한 엔트리의 outboxId 집합**을 반환한다. 발행 주체는 반환된 것만 SENT 로 표시한다.
- **Rationale**: 부분 실패(일부 엔트리만 실패)가 SQS 배치 API 의 정상 응답 형태다. 전체 성공/실패로 뭉개면 실패분이 유실되거나 성공분이 재발행된다.
- **Rationale(보강)**: 부분 실패가 SQS 배치 API 의 정상 응답 형태다 — 이 때문에 아웃박스 행에 `attempts` 를 이번 범위에서 미리 둔다(스키마를 나중에 또 바꾸지 않으려고).
- **Alternatives rejected**: 건당 `SendMessage` — 500건이면 왕복 500회.

## 8. 중복 요청 방지 수위

- **Decision**: 삽입 전 `existsByFoodIdAndOutboxStatus(foodId, PENDING)` 로 거른다. DB 유니크 제약·락은 두지 않는다.
- **Rationale**: 중복 큐잉의 피해는 LLM 호출 1회분 비용이고 데이터 정합은 깨지지 않는다(멱등 적재). 프로젝트 규약상 비치명 경합은 감수하고 치명 경로에만 최소 수단을 쓴다.
- **Alternatives rejected**: `UNIQUE(food_id, outbox_status)` — SENT 행이 누적되면 같은 음식의 두 번째 재수집이 영구히 막힌다.

## 9. 발행 완료 행 처리

- **Decision**: SENT 로 표시하고 남긴다(삭제하지 않는다). 조회는 `outbox_status = 'PENDING'` 인덱스로 한다.
- **Rationale**: "이 음식을 언제 수집 요청했나"가 관리자 트리아지에 바로 쓰이는 이력이다. 행 규모는 음식 수 × 재수집 횟수라 작다. 정리가 필요해지면 그때 보존 기간 정책을 추가한다.
