# 음식 콘텐츠 아웃박스 발행 설계

**작성일:** 2026-08-12

**상태:** 설계 승인 완료

## 목표

대기 중인 음식 콘텐츠 아웃박스 요청을 실행 후 종료되는 Spring Batch 애플리케이션에서 SQS로 발행하고, 랭체인 콜백이 같은 아웃박스 요청을 정확히 한 번만 완료하도록 만든다.

## 범위

이 변경은 두 저장소를 다룬다.

- `kbap`: SQS 발행 인프라, 발행 배치 잡, `COMPLETE` 아웃박스 상태, 콜백 완료 게이트, 테스트, 저장소 내부 계약 문서
- `kbap-agenthub`: 공식 랭체인 음식 적재 계약과 파이프라인 문서

`kbap-langchain` 구현은 사용자가 별도로 담당한다. 이 설계는 랭체인이 따라야 할 계약을 정의하지만 해당 저장소는 수정하지 않는다.

## 범위 밖

- 랭체인 그래프 중복 실행이나 중복 LLM 비용 방지
- FIFO 큐 또는 SQS 중복 제거 ID 도입
- 랭체인 처리 후 DLQ에 도달한 메시지 자동 재발행
- 발행 최대 시도 횟수 또는 발행 측 최종 실패 상태 추가
- 그래프 프롬프트, 생성 콘텐츠, 음식 검수 규칙, 이미지 처리 변경

## 채택 아키텍처

`common` 발행 포트, 신규 `infra:mq` SQS 어댑터, `batch`의 Spring Batch 잡으로 구성한다.

의존 방향은 다음과 같다.

```text
batch -> common port <- infra:mq
batch -> common outbox repository
api   -> common outbox repository and food domain
```

다른 접근을 채택하지 않은 이유는 다음과 같다.

1. `batch`에서 AWS SDK를 직접 호출하면 파일 수는 줄지만 잡 흐름과 SQS 세부 구현이 결합되고 기존 포트·어댑터 경계를 위반한다.
2. API 스케줄러에서 발행하면 다중 API 인스턴스를 위한 분산 잠금이 필요하고, 실행 후 종료되는 배치가 발행을 소유한다는 기존 결정과 충돌한다.

## 아웃박스 상태 모델

상태는 다음과 같이 확장한다.

```text
PENDING -> SENT -> COMPLETE
    \----------------^
```

- `PENDING`: SQS 수락을 아직 확인하지 못한 상태
- `SENT`: SQS가 메시지를 수락한 상태
- `COMPLETE`: 콜백 결과를 수락해 음식에 반영한 상태

`PENDING -> COMPLETE` 전이도 허용한다. SQS가 메시지를 전달하고 랭체인이 콜백하는 속도가 발행 배치의 `SENT` 커밋보다 빠를 수 있기 때문이다. 발행자는 `PENDING -> SENT` 조건부 갱신만 수행하므로 `COMPLETE`를 덮어쓰지 않는다.

`attempts`는 SQS 발행을 시도할 때마다 성공·실패와 관계없이 1 증가한다. 실패한 행은 `PENDING`을 유지해 다음 예약 실행에서 다시 시도한다. 성공한 행은 `SENT`가 된다. `sentAt`은 최초 SQS 수락 시각을 기록한다. 빠른 콜백이 이미 행을 `COMPLETE`로 바꿨다면 발행 결과 저장은 완료 상태를 유지하면서 `attempts`와 `sentAt`만 기록한다.

## SQS 메시지 계약

표준 큐에 다음 본문을 발행한다.

```json
{
  "outboxId": 100,
  "foodId": 1234,
  "scannedName": "들깨 칼국수"
}
```

세 필드는 모두 필수다.

- `outboxId`: 아웃박스 행 식별자이자 콜백 멱등 키
- `foodId`: 콜백 결과를 반영할 음식 식별자
- `scannedName`: `FoodContentOutbox.displayName`에 저장된 스캔 이름 스냅샷. 랭체인 정제 결과인 콜백 `displayName`과 구분한다.

SQS `SendMessageBatch` 엔트리 ID에도 문자열로 변환한 `outboxId`를 쓴다. 어댑터는 입력을 최대 10건씩 나누고 AWS 부분 성공·실패 결과를 아웃박스 ID 집합으로 돌려준다.

## 발행 배치 잡

기존 시간별 실행 후 종료되는 배치 배포 방식을 유지한다. 잡 이름은 `foodContentOutboxPublishJob`이며 기존 `spring.batch.job.enabled=true` 방식으로 실행한다. 큐 URL은 설정에서 받고 AWS 자격 증명과 리전은 기본 공급자 체인을 사용한다.

잡 실행마다 첫 아웃박스 ID보다 작은 값으로 ID 커서를 초기화한다. 각 페이지는 다음 순서로 처리한다.

1. 짧은 읽기 전용 트랜잭션에서 커서보다 ID가 크고 상태가 `PENDING`인 행을 설정된 크기만큼 ID 오름차순으로 읽는다.
2. DB 트랜잭션을 종료한다.
3. `FoodContentEventPublisher` 포트로 행을 발행한다. SQS 어댑터가 요청을 최대 10건씩 분리한다.
4. 새 짧은 트랜잭션에서 모든 시도 행의 `attempts`를 증가시킨다. 성공 행은 최초 `sentAt`을 기록하고 `PENDING`인 경우에만 `SENT`로 바꾼다. 실패 행은 `PENDING`을 유지한다. 이미 `COMPLETE`인 행은 완료 상태를 유지한다.
5. 페이지의 가장 큰 ID로 커서를 전진시키고 뒤에 남은 대기 행이 없을 때까지 반복한다.

Spring Batch 스텝은 `ResourcelessTransactionManager`를 사용한다. DB 조회와 상태 변경만 명시적인 `TransactionTemplate` 경계로 감싸 SQS 네트워크 호출이 DB 트랜잭션 안에서 실행되지 않도록 한다.

단조 증가 커서를 사용하면 같은 실행에서 실패 행을 계속 다시 선택하는 무한 루프를 막으면서 뒤쪽 대기 행도 처리할 수 있다. 다음 예약 실행에서는 커서를 초기화하므로 실패 행이 다시 대상이 된다.

## 콜백 계약

`POST /api/admin/foods/contents`와 `X-API-Version: 1.0`을 사용한다. 성공·실패 본문 모두 양수 `outboxId`와 `foodId`를 필수로 받는다.

서비스는 하나의 DB 트랜잭션에서 다음 작업을 수행한다.

1. `outboxId`와 `foodId`가 모두 일치하는 아웃박스 행을 `PENDING` 또는 `SENT`에서 `COMPLETE`로 원자적으로 갱신한다.
2. 한 행이 변경되면 `foodId`로 음식을 읽어 성공 또는 실패 결과를 반영한다.
3. 아웃박스 완료와 음식 변경을 함께 커밋한다.

음식 검증이나 저장이 실패하면 전체 트랜잭션이 롤백되어 아웃박스가 이전 상태로 돌아간다. 따라서 SQS 재시도가 같은 API를 다시 호출할 수 있다.

조건부 갱신 결과가 0건이면 다음처럼 처리한다.

- 같은 `outboxId`, `foodId`가 이미 `COMPLETE`이면 HTTP 409와 `FOOD-004`를 반환한다.
- 아웃박스가 없거나 `outboxId`와 `foodId`가 서로 맞지 않으면 HTTP 400과 `COMMON-002`를 반환한다.

중복 완료 오류 계약은 다음과 같다.

```json
{
  "success": false,
  "payload": null,
  "message": "이미 처리된 음식 콘텐츠 수집 요청입니다",
  "code": "FOOD-004"
}
```

## 소비자 재시도 계약

사용자가 별도로 구현하는 `kbap-langchain`은 콜백 결과를 다음처럼 해석한다.

- HTTP 200: SQS 레코드 성공 처리
- HTTP 409이면서 응답 코드가 `FOOD-004`: 최종 중복으로 기록하고 SQS 레코드를 성공 처리해 재시도와 DLQ 적재를 막음
- 그 밖의 HTTP 오류, 응답 해석 실패, 네트워크 오류: 레코드를 `batchItemFailures`에 넣어 SQS 재시도와 최종 DLQ 적재 유지

소비자는 사람이 읽는 `message`나 HTTP 409만으로 분기하지 않고 안정적인 응답 `code`로 분기해야 한다.

## 동시성 동작

같은 아웃박스 ID의 콜백 두 개가 동시에 도착할 수 있다. 두 요청은 같은 조건부 갱신을 실행한다. 한 트랜잭션만 행을 변경하고 음식 결과를 반영한다. 다른 트랜잭션은 첫 트랜잭션이 끝날 때까지 기다린 뒤 변경 행 0건을 확인한다. 이후 `COMPLETE` 상태를 확인하고 음식을 건드리지 않은 채 `FOOD-004`를 반환한다.

이 방식은 한 아웃박스 요청당 음식 변경을 한 번만 보장한다. 두 소비자가 콜백 전에 랭체인 그래프를 각각 실행하는 것은 막지 않는다.

## 설정

- `kbap.batch.food-content-outbox.queue-url`: 필수 음식 콘텐츠 큐 URL. 잡 활성화 시 비어 있으면 구성 단계에서 실패한다.
- `kbap.batch.food-content-outbox.page-size`: 양수 페이지 크기, 기본값 100
- AWS 자격 증명과 리전: AWS 기본 공급자 체인

어댑터는 페이지 크기와 관계없이 SQS 요청당 최대 10건 제한을 지킨다. 비밀 값과 자격 증명은 저장소 파일에 추가하지 않는다.

## 테스트 전략

### 공통·영속성

- `COMPLETE` 상태 저장
- `PENDING`과 `SENT`에서 조건부 완료 성공
- 이미 `COMPLETE`인 행의 조건부 완료 실패
- `outboxId`와 `foodId`가 모두 맞아야 완료
- 조건부 `PENDING -> SENT`가 `COMPLETE`를 덮어쓰지 않음
- 발행 성공·실패 모두 시도 횟수 증가

### MQ 어댑터

- 메시지에 `outboxId`, `foodId`, `scannedName`만 포함
- 10건 초과 입력을 올바른 SQS 배치로 분할
- AWS 부분 성공·실패를 정확한 아웃박스 ID로 매핑
- 전송 예외 시 어떤 행도 `SENT`로 오인하지 않음

### 배치

- `PENDING` 행만 ID 오름차순으로 선택
- 성공 행은 `SENT`, 실패 행은 `PENDING` 유지
- 한 잡 실행에서 실패 행을 한 번만 시도
- 콜백이 먼저 만든 `COMPLETE`를 발행 결과가 덮어쓰지 않음
- 현재 대상을 모두 시도한 뒤 잡 정상 종료

### API

- 성공·실패 콜백 모두 `outboxId` 필수
- 첫 유효 콜백이 아웃박스 완료와 음식 변경을 원자적으로 처리
- 같은 콜백 반복 시 409 `FOOD-004`, 음식 불변
- 동시 중복 콜백 결과가 음식 변경 1회와 `FOOD-004` 1회
- 아웃박스 없음·식별자 불일치 시 400 `COMMON-002`
- 음식 변경 실패 시 `COMPLETE` 전이 롤백
- 기존 콘텐츠 상태·사진 보존 규칙 유지

### 검증

- common, infra MQ, batch, API 대상 테스트 실행
- 전체 Gradle 테스트와 빌드 실행
- SDK 수준 가짜 SQS 또는 로컬 SQS 호환 표면에서 발행 JSON과 부분 실패 처리 관찰
- 로컬 API에 같은 아웃박스 본문을 두 번 보내 첫 요청 성공, 두 번째 `FOOD-004`, 음식 변경 1회를 관찰

## 문서와 전달

`kbap` PR은 저장소 내부 KB-302 계약에서 메시지 본문의 `outboxId`를 제외했던 기존 결정을 새 계약으로 교체한다.

`kbap-agenthub` 문서 PR은 `wiki/langchain-food-ingest-contract.md`와 파이프라인 문서에 다음 내용을 반영한다.

- 필수 SQS 메시지 필드
- 필수 콜백 `outboxId` 왕복
- `COMPLETE` 상태와 중복 콜백 처리
- `FOOD-004` 최종 중복 규칙
- 저장 멱등성과 중복 LLM 실행의 차이

`kbap-langchain` 코드 변경은 이 계약을 사용해 사용자가 별도로 진행한다.
