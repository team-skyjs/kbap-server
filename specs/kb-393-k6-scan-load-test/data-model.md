# Data Model: k6 스캔 부하 테스트

물리 스키마 변경은 없다. 아래는 테스트 운영 개념 모델이다.

## 테스트 계정 (dev member)

| 필드 | 값/제약 |
|---|---|
| 식별 | 기존 dev 로그인 계정 재사용 우선. 없으면 더미 1건 `provider_uid='loadtest-k6'` |
| scan_unlocked | `true` (무제한 스캔 — `isScanAllowed()` 통과) |
| onboarding_completed | `true` |
| member_status / status | `ACTIVE` / `ACTIVE` |
| 상태 전이 | (더미인 경우) 생성 → 사용 → 테스트 후 `status='DELETED'`(소프트 삭제) |

## 회차 (run) — 기록 단위

| 필드 | 예 |
|---|---|
| vus | 5 / 50 / 145 |
| 실행 시각 | 2026-08-30THH:MM |
| scan_p95 | k6 `scan_duration` p95 |
| fail_rate | `scan_failed`/vus |
| http_429 | 외부 한도 초과 건수(별도) |
| bottleneck | 앱스레드 / Hikari / heap / 호스트CPU / LLM-429 중 판정 |
| 비고 | 캡처 링크·이상 관찰 |

## 시드 이미지 (1회 업로드 → 재사용 키)

| 필드 | 값 |
|---|---|
| 원본 파일 | `k6/menu-board.jpg` (실제 메뉴판 사진 1장, 시드 입력) |
| objectKey | 시드(S1~S3) 산출물. `-e SCAN_IMAGE_PATH` 로 루프에 주입, 커밋 안 함 |
| 취급 | 바이너리 — `.gitignore`, quickstart 로 준비 안내 |
| 사용 | 시드 1회로 얻은 **하나의 objectKey 를 전 회차·전 VU 가 재사용**(재업로드 없음) |

## 파생 관계

- 회차(run) 1 : N 스캔요청(= vus). 스캔요청 N : 1 시드 objectKey(재사용). 스캔요청 1 : 1 티켓(매번 새 jti). 계정 1 : N 회차.
