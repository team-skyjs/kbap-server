# Quickstart: 관리자 페이지 로컬 실행·검증

**Plan**: [plan.md](plan.md)

## 로컬 실행

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

최초 관리자 계정은 로컬 DB 에 직접 INSERT 한다(운영도 동일 — 1회성 수동 등록, 계정 화면 없음):

```sql
INSERT INTO admin_account (admin_id, admin_pwd, status, created_at, updated_at)
VALUES ('admin', '<BCrypt 해시>', 'ACTIVE', NOW(), NOW());
```

BCrypt 해시 생성(비밀번호를 셸 히스토리·로그에 남기지 않기 — 프롬프트 입력):

```bash
htpasswd -nBC 10 "" | tr -d ':\n'   # macOS 기본 포함, 비밀번호는 프롬프트로 입력
```

(또는 구현 후 테스트 유틸 `BCryptPasswordEncoder().encode(...)` 일회 실행. `$2y$` 접두는 `$2a$` 와 호환.)

브라우저에서 `http://localhost:8080/admin` → 로그인 화면으로 리다이렉트 → 로그인 → 음식 대시보드.

## 검증 시나리오 (spec Success Criteria 매핑)

1. **SC-001/US2**: `/admin/foods` — 전체 건수·상태별 4종 건수·READY 비율 표시 확인.
2. **SC-002/US3**: 대시보드에서 시드 등록 폼 제출 → flash 결과 확인. 이미지 배치 제출 버튼 → 제출 결과 확인(대상 0건이면 "대상 없음").
3. **SC-003/US4**: 사이드바 → 회원 관리 → 목록 페이징 → 행 클릭 → 상세(프로필·상태).
4. **SC-004/US1**: 시크릿 창에서 `/admin/foods` 직접 접근 → `/admin/login` 리다이렉트. 잘못된 비밀번호 → 오류 문구.
5. **SC-005**: 브라우저 뷰포트 768×1024 (아이패드 미니) — 가로 스크롤 없이 전 화면 조작.

## 테스트 실행

```bash
./gradlew :api:test --tests "com.kbap.api.admin.*"   # 관리자 화면 단위·통합
./gradlew :common:test                                # 집계 쿼리 통합 포함
./gradlew test                                        # 전체 (ArchUnit 포함)
```

통합 테스트는 MySQL Testcontainers 로 동작 — Docker 데몬 필요.

## 운영 배포 메모

- 배포 후 운영 DB 에 최초 관리자 계정 1회 수동 INSERT(BCrypt 해시) — env 설정 불필요.
- 선택: ALB 리스너 규칙으로 `/admin*` 경로 IP allowlist(네트워크 층 방어 — 코드 변경 없음).
