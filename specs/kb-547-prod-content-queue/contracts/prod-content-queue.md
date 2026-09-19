# Contract: prod 콘텐츠 생성 요청 큐

KB-549(prod Lambda 생성)·KB-551(prod 트리거 연결)이 이 계약을 전제로 한다.

## 큐

- 요청 큐: `kbap-prod-generate-content-queue` (표준, `ap-northeast-2`, 계정 `118178010621`)
- DLQ: `kbap-prod-generate-content-dlq`
- 보관 14일(둘 다), 가시성 타임아웃 1800초·maxReceiveCount 2 — dev 공유 큐 `kbap-generate-content-queue` 와 같다(2026-09-12 실측).
- 암호화 SSE-SQS — 소비자 롤에 KMS 권한이 필요 없다.

## 발행자

- prod 배치(`kbap-prod-ecs-batch`)만 발행한다. 배치 롤의 `sqs:SendMessage` 대상은 이 큐 하나다.
- 발행 시각: 매시 정각(`foodContentOutboxPublishJob`) + 수동 트리거.

## 메시지 본문 (변경 없음)

dev 공유 큐와 같은 본문이다 — 발행 코드는 바뀌지 않는다.

```json
{ "outboxId": 123, "foodId": 456, "scannedName": "김치찌개" }
```

- `outboxId`·`foodId` 는 **prod DB** 의 값이다. 결과는 반드시 prod 적재 API 로 보내야 한다(dev 로 보내면 id 충돌로 dev 음식에 잘못 기록될 수 있음).
- 적재 계약(본문·멱등·outboxId 게이트)은 지식 위키 `langchain-food-ingest-contract` 그대로.

## 소비자 (이 작업 범위 밖)

- KB-551 전까지 소비자 없음 — 메시지는 최대 14일 대기 후 만료된다.
- prod Lambda 트리거를 연결할 때 SQS 는 가시성 타임아웃 ≥ 함수 타임아웃을 요구한다(공유 큐와 같은 값이라 dev 함수 설정이 그대로 통한다).
