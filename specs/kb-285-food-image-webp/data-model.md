# Data Model: 음식 사진 WebP 변환본 서빙

**스키마 변경 없음** — DDL·Flyway 마이그레이션을 추가하지 않는다. 바뀌는 것은 기존 컬럼에 담기는 **값의 의미**뿐이다.

## food.image_ref (varchar(500), nullable)

| 항목 | 변경 전 | 변경 후 |
|------|---------|---------|
| 값 형식 | `images/food/{sha12}_{uuid16}.png` | `images/webp/food/{sha12}_{uuid16}.webp` |
| 가리키는 객체 | S3 원본(PNG, 약 2MB) | S3 변환본(WebP, 약 1/10) |
| 작성 주체 | `FoodImageBatchCollectService.handleResult` | 동일 |
| 소비 | `ImageUrls.resolve(cdnBase, imageRef)` | 동일(규칙 불변) |

- 길이 여유: 최대 약 50자 → 500자 제한과 무관.
- 관리자 화면에서 운영자가 직접 입력하는 `imageRef`(`AdminFoodService`)는 이번 규칙의 대상이 아니다 — 입력값을 그대로 저장한다.
- 절대 URL(`http(s)://…`)로 저장된 값은 백필 대상에서 제외되고 `ImageUrls.resolve` 가 그대로 통과시킨다.

## image_batch_item.file_name (varchar(500), nullable)

**변경 없음.** PNG 업로드 키를 예약하는 값으로 유지한다(`images/food/….png`). put 대상 키와 항상 같아야 재시도 시 고아 객체가 생기지 않는다.

## S3 객체 레이아웃

| 경로 | 포맷 | 생성 주체 | 보존 |
|------|------|-----------|------|
| `images/food/{name}.png` | PNG 1024×1024 | 회수기(`storageObjectStore.put`) | 영구(재가공 대비) |
| `images/webp/food/{name}.webp` | WebP 1024×1024(리사이즈 없음) | S3 PutObject 이벤트 → Lambda | 서빙용 |

파일명(`{sha12}_{uuid16}`)은 두 경로에서 동일하다 — 이 대응이 백필 SQL 과 `webpRefOf` 매핑의 근거다.

## 상태 전이

`Food.attachImage` → `transitionByContentState()` 흐름 불변. `PENDING_IMAGE` → `PENDING_REVIEW`(텍스트 완비 시) / `INCOMPLETE` 유지(텍스트 미완). 기록되는 문자열만 바뀐다.
