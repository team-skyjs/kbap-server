# Quickstart: 앱 버전 정보 조회

## 검증 실행

```bash
./gradlew :api:test --tests "com.kbap.api.appversion.*" --tests "com.kbap.api.admin.AdminAppVersionIntegrationTest"
./gradlew :api:test --tests "com.kbap.api.architecture.ModuleBoundaryTest"   # appversion 컨텍스트 등록 확인
./gradlew build                                                              # 전체 회귀
```

통합 테스트는 MySQL Testcontainers 위에서 Flyway 마이그레이션(시드 포함)을 그대로 태운다 — 시드 행 존재를 전제로 작성한다.

## 수동 확인 (local 프로필)

```bash
./gradlew :api:bootRun   # SPRING_PROFILES_ACTIVE=local

# 공개 조회 (무인증)
curl -s http://localhost:8080/api/app-version

# 관리자 갱신 (ADMIN 롤 토큰 필요)
curl -s -X PUT http://localhost:8080/api/admin/app-version \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"minSupportedVersion":"1.0.0","latestVersion":"1.0.2","iosStoreUrl":"https://apps.apple.com/...","aosStoreUrl":null}'
```

## 구현 시 주의

- `GET /api/app-version` 은 WebConfig JWT 필터에 **등록하지 않는다**(무인증 요구사항). admin 경로는 기존 패턴이 커버해 WebConfig 무변경.
- `ModuleBoundaryTest` 의 `allowedDomainDeps` 에 `"appversion" to emptySet()` 추가를 잊으면 arch 태그 테스트가 실패한다.
- Flyway 파일명은 파일 생성 시각 timestamp(`Vyyyy.MM.dd.HH.mm.ss__app_version_table.sql`)로 짓는다.
- Kotlin 소스 주석 금지 · 테스트는 BehaviorSpec(한국어 given/when/then) 고정.
