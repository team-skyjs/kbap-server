# Quickstart: KB-171 이미지 업로드 객체 키 환경 접두

## 1. 로컬 검증 (구현 직후)

```bash
./gradlew :application:test --tests "com.kbap.application.upload.ImageUploadApplicationServiceTest"
./gradlew test        # 전체 무수정 통과 확인 (SC-005)
```

## 2. 환경별 기대 동작 (2026-07-20 개정 — 프로필 yml 이 환경명 기본값 소유, env 는 오버라이드)

| 환경 | 기본값(yml) | 발급 키 예시 |
|------|-------------|--------------|
| local·테스트 | `local` | `local/images/scan/2026/07/42/{uuid}.jpg` |
| dev | `dev` | `dev/images/scan/2026/07/42/{uuid}.jpg` |
| staging | `staging` | `staging/images/scan/2026/07/42/{uuid}.jpg` |
| prod | `prod` | `prod/images/scan/2026/07/42/{uuid}.jpg` |

**음식 사진은 환경 공용** — `images/menus/…` 에 두고 전 환경이 같은 경로를 참조한다. 업로드 API(용도 `scan`·`review`·`profile`)를 경유하지 않으므로 환경 접두가 붙지 않는다(구조적 보장) — 향후 배치의 음식 사진 제작도 `images/` 아래 직접 기록(이 설정 무관). 기존 저장된 ref(`images/…` 레거시 포함)도 전 환경에서 그대로 유효 — URL 조립이 ref 를 그대로 접합. 접두 결합 빈 값 계약은 단위 테스트가 고정(env 로 빈 값 반전 시 무접두).

## 3. 배포 후 검증 런북 (dev)

1. 배포만 하면 됨 — env 추가 불필요(yml 기본값 `dev`).
2. 로그인 후 업로드 URL 발급:
   ```bash
   curl -s -X POST "$DEV_HOST/api/v1/images/upload-url" \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"purpose":"MENU_SCAN","contentType":"image/jpeg","contentLength":1024}' | jq .payload.objectKey
   ```
   → `dev/images/scan/…` 로 시작하면 성공.
3. 발급된 `uploadUrl` 로 PUT 업로드 → S3 콘솔에서 `dev/` 폴더 아래 객체 확인.
4. `publicUrl` 접근으로 CDN 표시 확인(접두 포함 경로 그대로 조립됨).
5. staging·prod 도 배포 후 발급 키가 각각 `staging/`·`prod/` 로 시작하는지 확인.

## 4. 롤백

env 로 `STORAGE_KEY_PREFIX=`(빈 값) 지정 후 재기동 — 접두 없는 기존 구조로 복귀. 코드 롤백 불필요. 이미 접두로 저장된 ref 는 경로 그대로 유효(URL 조립이 ref 를 그대로 접합).
