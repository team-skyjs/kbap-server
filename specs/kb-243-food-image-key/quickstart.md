# Quickstart: 음식 이미지·스캔 이미지 저장 키 규약 정비

## 변경 지점 (프로덕션 3파일)

1. `api/src/main/kotlin/com/kbap/api/food/FoodImageBatchCollectService.kt`
   - `storageKeyOf(foodId)` → 음식명 기반 `storageKeyOf(foodName)` : `images/food/{sha256[:12]}_{uuid16}.png`
   - `handleResult` : S3 put **전에** `foodRepository.findById` 로 음식명 확보(없으면 put 없이 fail), put 후 트랜잭션 분기는 현행 유지
2. `api/src/main/kotlin/com/kbap/api/image/UploadPurpose.kt` — `MENU_SCAN("scan")` → `MENU_SCAN("scans")`
3. `api/src/main/kotlin/com/kbap/api/image/PresignedUploadService.kt` — `images/%s/%04d/%02d/%d/%s.%s` → `images/%s/%04d/%02d/%d_%s.%s`

## 검증

```bash
./gradlew :api:test --tests "com.kbap.api.food.FoodImageBatchCollectServiceTest" \
                    --tests "com.kbap.api.image.PresignedUploadServiceTest"
./gradlew :api:test   # 회귀 전체
```

기대 키 예시:
- 음식: `images/food/7eb8f793d0c9_bee76f920e204f64.png` (환경접두 없음, 회수마다 uuid 갱신)
- 스캔: `dev/images/scans/2026/07/1024_550e8400-e29b-41d4-a716-446655440000.jpg`

## 순서 (TDD)

1. `FoodImageBatchCollectServiceTest` — 키 정규식·재생성 신규 키·동명 음식 비충돌 기대로 수정 + 파일명 규칙 단위 검증 추가 → Red
2. `FoodImageBatchCollectService` 구현 → Green
3. `PresignedUploadServiceTest` — 발급 키 정규식을 `images/scans/{yyyy}/{mm}/{memberId}_{uuid}` 로 수정 → Red
4. `UploadPurpose`·`PresignedUploadService` 구현 → Green
