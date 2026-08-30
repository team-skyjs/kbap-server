# Contract: 스캔 부하 — 시드(1회) + 루프(반복) (dev)

베이스 `https://dev.kbap.site`. 공통 헤더: `Authorization: Bearer <access>`, `X-API-Version`(단계별), `Content-Type: application/json`. 성공 판정은 HTTP 200 + `BaseResponse.success=true`.

**구조**: 시드(S1~S3)는 부하 실행 **전 1회**만 수행해 `objectKey`를 얻는다. 부하 루프(L1~L2)는 각 VU가 반복하며 시드 `objectKey`를 재사용한다. 스캔은 imagePath의 완료·소유를 검증하지 않으므로(`scanMenuBoardImageV2` 코드 확인) 재사용이 성립한다.

## [시드 1회]

### S1. 업로드 URL 발급
- `POST /api/images/upload-url` · 헤더 `X-API-Version: 1.0`
- body `{ "purpose":"MENU_SCAN", "contentType":"image/jpeg", "contentLength":<정확 바이트> }`
- 응답 `payload.uploadUrl`(presigned PUT), `payload.requiredHeaders`(그대로 실어 PUT), `payload.objectKey`(경로), `payload.publicUrl`

### S2. S3 이미지 업로드
- `PUT <uploadUrl>` · 헤더 = S1 `requiredHeaders` 그대로 · body = 이미지 바이트
- 앱 헤더(Authorization/X-API-Version) 붙이지 않는다 — presigned URL 은 S3 직접
- 성공 200

### S3. 업로드 완료 신고 → objectKey 확보
- `POST /api/images/complete` · 헤더 `X-API-Version: 1.0`
- body `{ "path":<objectKey>, "contentType":"image/jpeg", "size":<바이트> }`
- 서버가 소유·존재 검증 후 기록. path 는 전체 URL 아닌 오브젝트 경로
- **이 objectKey 를 부하 루프에 `SCAN_IMAGE_PATH` 로 넘긴다.**

## [부하 루프 — 반복]

### L1. 스캔 티켓 발급
- `POST /api/scans/tickets` · 헤더 `X-API-Version: 1.0` · body 없음
- 조건: 회원이 `isScanAllowed()`(scan_unlocked=true 또는 무료 3회 미만). 아니면 403 `SCAN-004`
- 응답 `payload.ticket`(서명 티켓, 300초 **1회용** — 매 스캔 새로 발급), `payload.expiresInSeconds`

### L2. 스캔 요청 (과금)
- `POST /api/scans?lang=en&currency=KRW` · 헤더 `X-API-Version: 2.0`, `X-Scan-Ticket: <L1 ticket>`
- body `{ "imagePath": <SCAN_IMAGE_PATH> }` (전체 URL 금지 패턴 — 시드 objectKey 그대로)
- 티켓 subject ≠ memberId 또는 만료면 400(`SCAN-007`/INVALID_SCAN_TICKET). LLM 비전 호출 발생 — **이 단계만 과금**
- 응답 `payload` = 스캔 결과(메뉴 항목 등)

## 실패 분리 규칙
- 시드(S1~S3) 실패 → 부하 실행 자체를 시작하지 않는다.
- 루프에서 티켓 발급(L1) 실패 → 그 VU 는 스캔(L2)을 호출하지 않는다(비용 0).
- L2 의 429 = 외부 LLM 한도 → `scan_failed` 와 별개로 상태코드로 분리 집계.

## 재사용 안전성 근거
- `scanMenuBoardImageV2`: 티켓 검증(jti) → `ScanReservationStore.reserve(memberId, jti, ...)` → `scanService.scan(member, imagePath, ...)`. imagePath 로 업로드 레코드를 다시 조회하거나 소유를 확인하는 단계가 없다.
- 예약은 (memberId, jti) 키 — 티켓마다 jti 가 유일해 동시 VU 간 DUPLICATE 충돌 없음. scan_unlocked 계정은 limit=MAX 라 한도 미발동.
