# Data Model: 음식 이미지·스캔 이미지 저장 키 규약 정비

엔티티·스키마 변경 없음. 기존 컬럼에 담기는 **값의 형식**만 바뀐다.

## Food (`food.image_ref`, varchar(500), nullable) — 기존 컬럼, 값 형식 변경

| 구분 | 형식 |
|------|------|
| 기존(배치 산출) | `images/food/{foodId}.png` |
| 기존(시딩) | `images/menus/{sha256(음식명)[:12]}_{uuid16}.png` |
| **신규(배치 산출)** | `images/food/{sha256(음식명)[:12]}_{uuid16}.png` |

- 갱신 경로: 회수 성공 시 `Food.attachImage(newKey)` — 현행 유지.
- 기존 값은 그대로 유효(소급 이관 없음). `ImageUrls.resolve(base, ref)` 가 문자열 그대로 URL 화.

## ImageBatchItem (`image_batch_item.file_name`, varchar(500), nullable) — 기존 컬럼, 값 형식 변경

- `done(fileName)` 에 저장되는 값이 새 키 형식으로 바뀐다. 상태 전이(PENDING→DONE/FAILED)는 무변경.

## UploadedImage (`uploaded_image.path`) — 무변경

- 업로드 완료 신고가 발급 키 문자열을 그대로 저장·검증(`findByPath`). 발급 키 형식이 바뀌어도 저장·검증 로직은 형식 비의존.

## 키 생성 규칙 (검증 규칙 — FR-005·FR-006)

| 대상 | 규칙 |
|------|------|
| 음식 이미지 | `images/food/` + sha256(음식명 UTF-8) 소문자 hex 12자리 + `_` + UUID hex(하이픈 제거) 16자리 + `.png`. 환경접두 없음. 호출마다 새 uuid. |
| 스캔 업로드 | (`{key-prefix}/` 접두, 비어 있으면 생략) + `images/scans/` + `{yyyy}/{mm}/` (UTC) + `{memberId}_{UUID(36자)}` + `.{ext}` (jpeg→jpg 정규화 현행 유지). |
