# Quickstart: KB-171 이미지 업로드 객체 키 환경 접두

## 1. 로컬 검증 (구현 직후)

```bash
./gradlew :application:test --tests "com.kbap.application.upload.ImageUploadApplicationServiceTest"
./gradlew test        # 전체 무수정 통과 확인 (SC-005)
```

## 2. 환경별 기대 동작

| 환경 | STORAGE_KEY_PREFIX | 발급 키 예시 |
|------|--------------------|--------------|
| local | (미설정) | `images/scan/2026/07/42/{uuid}.jpg` — 기존과 동일 |
| dev | `dev` | `dev/images/scan/2026/07/42/{uuid}.jpg` |
| staging | `staging` | `staging/images/scan/2026/07/42/{uuid}.jpg` |
| prod | (미설정) | `images/scan/2026/07/42/{uuid}.jpg` — 기존과 동일 |

## 3. 배포 후 검증 런북 (dev)

1. 인프라: dev 환경 변수에 `STORAGE_KEY_PREFIX=dev` 추가 후 배포.
2. 로그인 후 업로드 URL 발급:
   ```bash
   curl -s -X POST "$DEV_HOST/api/v1/images/upload-url" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"purpose":"MENU_SCAN","contentType":"image/jpeg","contentLength":1024}' | jq .payload.objectKey
   ```
   → `dev/images/scan/…` 로 시작하면 성공.
3. 발급된 `uploadUrl` 로 PUT 업로드 → S3 콘솔에서 `dev/` 폴더 아래 객체 확인.
4. `publicUrl` 접근으로 CDN 표시 확인(접두 포함 경로 그대로 조립됨).
5. staging 도 `STORAGE_KEY_PREFIX=staging` 으로 동일 반복.
6. prod: env 미설정 유지 — 배포 후 발급 키가 `images/` 로 시작하는지(무변경) 확인.

## 4. 롤백

env 에서 `STORAGE_KEY_PREFIX` 제거(또는 빈 값) 후 재기동 — 접두 없는 기존 구조로 복귀. 코드 롤백 불필요. 이미 접두로 저장된 ref 는 경로 그대로 유효(URL 조립이 ref 를 그대로 접합).
