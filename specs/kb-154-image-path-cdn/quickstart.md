# Quickstart: KB-154 검증 절차

## 1. 테스트 실행

```bash
./gradlew :core:test --tests "com.kbap.core.image.ImageUrlsTest"
./gradlew :domain:member:test --tests "com.kbap.domain.member.model.MemberProfileTest"
./gradlew :app:api:test    # 통합 — test yml 의 public-base-url=https://cdn.test 로 조합 검증
./gradlew build            # 전체 게이트
```

## 2. 수동 확인 (선택 — local 프로필)

1. `IMAGE_PUBLIC_BASE_URL=https://cdn.example.com` 지정 후 `:app:api:bootRun`
2. 온보딩/프로필 수정에 `"profileImageUrl": "profile-image/2026/07/18/1/uuid.jpg"` 전송 → 200
3. `GET /api/v1/members/me` → `profileImageUrl` 이 `https://cdn.example.com/profile-image/...` 인지 확인
4. `"profileImageUrl": "https://evil.com/x.jpg"` 전송 → 400 `MEMBER-008`
5. DB 확인: `SELECT profile FROM member WHERE id=...` → JSON 의 `profileImageUrl` 에 도메인 없음

## 3. 배포 유의

- 환경변수 변경 없음(`IMAGE_PUBLIC_BASE_URL` 기존 그대로). `PROFILE_IMAGE_ALLOWED_HOSTS` 는 더 이상 읽지 않음 — 제거 가능
- 데이터 마이그레이션 없음 — 레거시 절대 URL 행은 응답에서 그대로 통과
- 클라이언트 릴리스 조율: 사진 등록 입력이 전체 URL → 경로(objectKey)로 바뀜 (presigned 발급 응답의 objectKey 사용)
