# k6 write/external fixtures

`dev.json` is operator-owned and must not be committed. Copy `dev.example.json`, then replace every example with values from the target environment before a campaign.

## Preconditions

- Use member `35` and its access token. The member must be `ACTIVE`, not soft-deleted, and have `scan_unlocked = 1`.
- Run `k6/scripts/seed-fixtures.sql` read-only and confirm its first result is `READY`.
- `profileV1` and `profileV11` must exactly match member 35's current valid profile values. Profile targets PATCH these same values so they do not drift state.
- `scanHistoryFoodIds` must contain READY foods already present in member 35's scan history.
- `reviewIds` must contain only reviews created for the current load campaign by member 35. `reportReviewIds` must contain active reviews owned by another member.
- `blockedMemberIds`, `bookmarkFoodIds`, and the update/like uses of `reviewIds` are distributed by `__VU % length`. Set `CONTENDED=true` only when intentionally measuring one-row contention; it pins the first value.

## Unique fixtures

`review-delete`, `report-create`, `imageCompleteFixtures`, and `orderFixtures` are consumed by `FIXTURE_OFFSET + execution.scenario.iterationInTest`. Each review ID or object path must be unique and usable exactly once. `FIXTURE_OFFSET` must be a non-negative integer and must reserve every iteration scheduled by earlier k6 processes, including dropped iterations.

For the three-process runner contract, use these offsets:

- initial smoke: `0`
- warmup: `1`
- measurement read/write: `1 + RATE * 120` for the fixed two-minute warmup
- measurement external or smoke: `2` because smoke and warmup each reserve one iteration

When a unique array is exhausted, or an `imageCompleteFixtures` path lacks the exact current run tag, k6 sends no request and increments `fixture_exhausted`. The `fixture_exhausted` and `scan_failed` thresholds are both `count==0`, so skipped destructive work and incomplete two-step scans fail the run instead of exiting successfully.

- Every `imageCompleteFixtures` path must contain `[load:<campaign>-image-complete]`, where `<campaign>-image-complete` is the exact `RUN_ID`. It must have a matching presign record and uploaded object with the same content type and byte size.
- Every `orderFixtures.imagePath` must identify a distinct scan. Its food must be a valid scanned food.
- The scan targets reuse `scanImagePath`; scan v2 obtains a fresh ticket for every iteration.

External-kind targets reject profile overrides other than `external` or one-iteration `smoke`, and reject more than 200 total iterations (`VUS * ITERATIONS`). `order-create-location` is external-kind because it calls the reverse geocoder. `SCAN_TIMEOUT` defaults to `120s`. Places, scan, location order, upload, and all other provider-facing targets must be exercised against the local mock during automated verification; running them against dev requires the campaign approval gate.

`/api/community/**` is intentionally absent from this catalog.

## Cleanup

The target manifest exposes the runner-facing lifecycle contract:

- `stateCapability=none`: no database capture or cleanup.
- `stateCapability=snapshot-restore`: restore the explicitly captured member 35 profile, block, bookmark, review-like, or review fixture state.
- `stateCapability=tagged-cleanup`: delete exact case-sensitive `[load:$RUN_ID]` review/report/order/image rows and restore captured member counters where applicable.
- `stateCapability=scan-cleanup`: restore member 35's scan count and remove only its scan histories above the captured high-watermark.
- `objectCleanup=imageCompleteFixtures`: delete only `imageCompleteFixtures[].path` objects after database cleanup.
- `objectCleanup=scanGeneratedFoodImageRefs`: consume the `object_cleanup_path` result rows emitted by cleanup. Never delete the pre-existing `scanImagePath` or `orderFixtures[].imagePath` objects.

Before every target whose `stateCapability` is not `none`, Task 8 must run `capture-fixtures.sql` in one MySQL session and store its single `snapshot_base64` value in a mode-0600 temporary file. The required session variables are:

```sql
SET @run_id = '20260831T120000Z-review-create';
SET @target = 'review-create';
SET @blocked_member_ids_json = '[36]';
SET @bookmark_food_ids_json = '[1]';
SET @review_ids_json = '[1]';
SOURCE k6/scripts/capture-fixtures.sql;
```

After all phases, Task 8 must invoke cleanup in a new MySQL session with the exact captured value:

```sql
SET @run_id = '20260831T120000Z-review-create';
SET @target = 'review-create';
SET @snapshot_base64 = '<exact capture output>';
SOURCE k6/scripts/cleanup-fixtures.sql;
```

The cleanup rejects a case-mismatched snapshot run ID or target, missing/blank/percent-bearing run IDs, and malformed snapshots. Allowed underscores are escaped before `LIKE`; tag columns are compared with `BINARY`, so `[LOAD:...]` and case-only run-ID distractors are never selected. In one guarded transaction it operates only on explicitly captured fixture rows or:

- member 35's tagged reports and tagged reviews, plus those reviews' report/like/ranking children
- member 35's orders selected through an exactly tagged `order_item.menu_name`, deleting their items first
- member 35's exactly tagged `uploaded_image` rows

Review update/delete restoration includes every mutable review field, status, version, post-snapshot ranking events, and the original member counters. Profile, block, bookmark, and review-like restoration covers only member 35 and the fixture IDs captured before the run; it never blanket-deletes pre-existing rows.

The current schema maps legacy `INCOMPLETE` food to `FAILED`, and `createIncomplete()` now persists `FAILED`. Scan cleanup therefore considers only `FAILED` foods above the captured food high-watermark that were referenced by post-snapshot member 35 scans. It deletes a candidate only when no scan/bookmark/review/order/ingredient/outbox/image-batch/community reference remains; unresolved candidates remain untouched and cleanup signals a non-zero residual after committing the bounded scan-history/counter restore. Presign calls create no database row, and SQL cannot delete S3 objects, so Task 8 must process the manifest-directed object cleanup separately.
