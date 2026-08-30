# k6 write/external fixtures

`dev.json` is operator-owned and must not be committed. Copy `dev.example.json`, then replace every example with values from the target environment before a campaign.

## Preconditions

- Use member `35` and its access token. The member must be `ACTIVE`, not soft-deleted, and have `scan_unlocked = 1`.
- Run `k6/scripts/seed-fixtures.sql` read-only and confirm its first result is `READY`.
- `profileV1` and `profileV11` must exactly match member 35's current valid profile values. Profile targets PATCH these same values so they do not drift state.
- `scanHistoryFoodIds` must contain READY foods already present in member 35's scan history.
- `reviewIds` must contain only reviews created for the current load campaign by member 35. `reportReviewIds` must contain active reviews owned by another member.
- `blockedMemberIds`, `bookmarkFoodIds`, and `reviewIds` are distributed by `__VU % length`. Set `CONTENDED=true` only when intentionally measuring one-row contention; it pins the first value.

## Unique fixtures

`imageCompleteFixtures` and `orderFixtures` are consumed by global scenario iteration. Each object path must be unique and usable exactly once. When the array is exhausted, k6 sends no request and increments `fixture_exhausted`.

- Every `imageCompleteFixtures` path must have a matching presign record and uploaded object with the same content type and byte size.
- Every `orderFixtures.imagePath` must identify a distinct scan. Its food must be a valid scanned food.
- The scan targets reuse `scanImagePath`; scan v2 obtains a fresh ticket for every iteration.

The `external` profile rejects more than 200 total iterations (`VUS * ITERATIONS`). `SCAN_TIMEOUT` defaults to `120s`. Places, scan, location order, upload, and all other provider-facing targets must be exercised against the local mock during automated verification; running them against dev requires the campaign approval gate.

`/api/community/**` is intentionally absent from this catalog.

## Cleanup

Creation text is tagged with `[load:$RUN_ID]`. Prefer the API teardown targets so ranking changes follow domain logic. For stranded review/report fixtures, set the exact run ID in the same MySQL session and source the guarded cleanup script:

```sql
SET @run_id = '20260831T120000Z';
SOURCE k6/scripts/cleanup-fixtures.sql;
```

The script rejects missing, blank, or unsafe run IDs. It deletes only member 35's tagged reviews, their report/like/ranking children, and member 35's tagged reports, then recalculates member 35's review counters. It never deletes member 35, food master rows, or untagged reviews.
