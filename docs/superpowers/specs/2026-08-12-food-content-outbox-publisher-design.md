# Food Content Outbox Publisher Design

**Date:** 2026-08-12

**Status:** Approved design pending written-spec review

## Goal

Publish pending food-content outbox requests to SQS from the run-to-completion Spring Batch application and make the LangChain callback idempotent by completing each outbox request exactly once.

## Scope

This change covers two repositories:

- `kbap`: SQS publishing infrastructure, the publisher batch job, the `COMPLETE` outbox state, the callback completion gate, tests, and repository-local contracts.
- `kbap-agenthub`: the canonical LangChain food-ingest contract and pipeline documentation.

The `kbap-langchain` implementation is owned separately. This design defines the contract it must consume but does not modify that repository.

## Non-goals

- Preventing duplicate LangChain graph execution or duplicate LLM cost.
- Introducing a FIFO queue or SQS deduplication IDs.
- Automatically replaying messages whose LangChain processing reaches the DLQ.
- Adding a maximum publisher-attempt count or a publisher-side terminal failure state.
- Changing graph prompts, generated content, food review rules, or image processing.

## Chosen Architecture

Use a `common` publishing port, a new `infra:mq` SQS adapter, and a Spring Batch job in `batch`.

This preserves the repository dependency direction:

```text
batch -> common port <- infra:mq
batch -> common outbox repository
api   -> common outbox repository and food domain
```

The alternatives were rejected as follows:

1. Calling the AWS SDK directly from `batch` would reduce the file count but couple job orchestration to SQS details and violate the existing port-adapter boundary.
2. Publishing from an API scheduler would require a distributed scheduler lock because the API runs multiple instances and would contradict the prior decision that the run-to-completion batch owns publishing.

## Outbox State Model

The state set becomes:

```text
PENDING -> SENT -> COMPLETE
    \----------------^
```

- `PENDING`: not yet confirmed as accepted by SQS.
- `SENT`: SQS accepted the message.
- `COMPLETE`: the callback result was accepted and applied to the food.

`PENDING -> COMPLETE` is intentionally legal. SQS can deliver and LangChain can call back before the publisher commits `SENT`. The publisher therefore marks a row `SENT` only with a conditional `PENDING -> SENT` update and can never overwrite `COMPLETE`.

`attempts` increments once for every SQS publishing attempt, whether that entry succeeds or fails. Failed entries remain `PENDING` and are retried by the next scheduled batch run. Successful entries become `SENT`. Existing `sentAt` records the first confirmed SQS acceptance time. If a fast callback already changed the row to `COMPLETE`, publishing-result persistence increments `attempts` and sets `sentAt` without changing the completed status.

## SQS Message Contract

The publisher sends a standard-queue message body:

```json
{
  "outboxId": 100,
  "foodId": 1234,
  "scannedName": "들깨 칼국수"
}
```

All three fields are required.

- `outboxId` is the outbox row identifier and the callback idempotency key.
- `foodId` identifies the food that receives the callback result.
- `scannedName` is the original name snapshot stored as `FoodContentOutbox.displayName`. It remains distinct from the callback `displayName`, which is the cleaned graph result.

The SQS `SendMessageBatch` entry ID also uses `outboxId`, encoded as a string. The adapter splits input into groups of at most ten entries, maps AWS partial successes back to outbox IDs, and reports successful and failed IDs separately.

## Publisher Batch Job

The existing hourly run-to-completion batch deployment remains the execution mechanism. The job is named `foodContentOutboxPublishJob`, is enabled with the existing `spring.batch.job.enabled=true` mechanism, and uses the configured queue URL and AWS default credential and region chains.

Each job execution starts with an ID cursor below the first outbox ID. For each page of pending rows:

1. Read up to a configured number of `PENDING` rows whose IDs are greater than the cursor, in ascending ID order, in a short read-only transaction.
2. Leave the database transaction.
3. Publish the rows through the `FoodContentEventPublisher` port. The SQS adapter chunks requests to ten entries.
4. In a new short transaction, increment `attempts` for every attempted row, set the first `sentAt` for accepted rows, conditionally mark accepted `PENDING` rows as `SENT`, and leave failed rows `PENDING`. A row already changed to `COMPLETE` retains that status.
5. Advance the cursor to the largest ID in the page and continue until no later pending row exists.

The Spring Batch step uses `ResourcelessTransactionManager`. Explicit `TransactionTemplate` boundaries own database reads and state changes so the SQS network call never runs inside a database transaction.

The monotonic cursor prevents an entry that failed in the current run from being selected again during that same job execution while still allowing later pending rows to be attempted. The cursor resets for the next scheduled execution, so failed entries become eligible again.

## Callback Contract

`POST /api/admin/foods/contents` with `X-API-Version: 1.0` requires positive `outboxId` and `foodId` values on both success and failure payloads.

The service performs the following work in one database transaction:

1. Atomically update the matching outbox row from `PENDING` or `SENT` to `COMPLETE` using both `outboxId` and `foodId` in the predicate.
2. If one row changed, load the food by `foodId` and apply the success or failure result.
3. Commit the outbox completion and food mutation together.

If food validation or persistence fails, the transaction rolls back and the outbox remains in its previous state, so SQS retry can call the API again.

If the conditional update changes no rows:

- A row with the same `outboxId`, `foodId`, and `COMPLETE` status returns HTTP 409 with `FOOD-004`.
- A missing outbox or an `outboxId`/`foodId` mismatch returns HTTP 400 with `COMMON-002`.

The stable terminal-duplicate error is:

```json
{
  "success": false,
  "payload": null,
  "message": "이미 처리된 음식 콘텐츠 수집 요청입니다",
  "code": "FOOD-004"
}
```

## Consumer Retry Contract

The separately owned `kbap-langchain` implementation must interpret callback results as follows:

- HTTP 200: acknowledge the SQS record.
- HTTP 409 with response code `FOOD-004`: log a terminal duplicate and acknowledge the SQS record so it does not retry or reach the DLQ.
- Any other HTTP error, malformed response, or network error: include the record in `batchItemFailures` so SQS retries it and eventually routes it to the DLQ.

The consumer must branch on the response `code`, never on the human-readable `message` or HTTP 409 alone.

## Concurrency Behavior

Two callbacks for the same outbox ID may arrive concurrently. Both execute the same conditional update. One transaction changes the row and applies the food result. The other waits for that transaction and then observes zero changed rows. It confirms `COMPLETE` and returns `FOOD-004` without touching the food.

This guarantees one food mutation for one outbox request. It does not prevent both consumers from running the LangChain graph before reaching the callback.

## Configuration

The new MQ adapter uses project configuration for:

- `kbap.mq.food-content-queue-url`: required food-content queue URL.
- `kbap.batch.food-content-publisher.page-size`: positive publisher page size with a default of 100. The adapter always enforces the SQS limit of ten entries per request.
- AWS credentials and region: the standard AWS default provider chains.

Secrets and credentials remain in the existing AWS runtime credential chain and are not added to repository files.

## Testing Strategy

### Common and persistence

- `COMPLETE` is a valid outbox state.
- Conditional completion succeeds from `PENDING` and `SENT`.
- Conditional completion fails for an already `COMPLETE` row.
- Conditional completion requires both the matching `outboxId` and `foodId`.
- Conditional `PENDING -> SENT` cannot overwrite `COMPLETE`.
- Publishing attempts increment on success and failure.

### MQ adapter

- Messages contain exactly `outboxId`, `foodId`, and `scannedName`.
- More than ten events are split into valid SQS batches.
- Partial AWS success and failure results map to the correct outbox IDs.
- Transport exceptions are reported as failed publishing attempts without marking rows `SENT`.

### Batch

- Only pending rows are selected in ascending ID order.
- Accepted entries become `SENT`; rejected entries stay `PENDING`.
- A failed row is attempted only once in one job execution.
- A callback that reaches `COMPLETE` before publisher state persistence is not overwritten by `SENT`.
- The job exits successfully after exhausting its current work.

### API

- Successful and failed callbacks require `outboxId`.
- The first valid callback completes the outbox and mutates the food atomically.
- Repeating the callback returns 409 `FOOD-004` and leaves the food unchanged.
- Concurrent duplicate callbacks result in one mutation and one `FOOD-004` response.
- Missing and mismatched outbox identities return 400 `COMMON-002`.
- A failed food mutation rolls back the `COMPLETE` transition.
- Existing content state and image-preservation rules remain unchanged.

### Verification

- Run targeted common, infra MQ, batch, and API tests.
- Run the full Gradle test suite and build.
- Execute the publisher against a local fake SQS-compatible surface or an SDK-level fake and observe the emitted JSON and partial-result handling.
- Call the live local API twice with the same outbox payload and observe first-success, second-`FOOD-004`, and one food mutation.

## Documentation and Delivery

The `kbap` PR updates repository-local KB-302 contracts to replace the earlier decision that omitted `outboxId` from the message body.

The `kbap-agenthub` documentation PR updates `wiki/langchain-food-ingest-contract.md` and the pipeline page with:

- The required SQS message fields.
- The required callback `outboxId` echo.
- The `COMPLETE` state and duplicate callback behavior.
- The `FOOD-004` terminal-duplicate rule.
- The distinction between storage idempotency and duplicate LLM execution.

The `kbap-langchain` code change is delivered separately by its owner using this contract.
