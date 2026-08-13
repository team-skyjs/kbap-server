# Data Model: 앱 버전 정보 조회

## 엔티티

### AppVersion (`com.kbap.common.domain.appversion.model.AppVersion`)

`BaseEntity` 상속(공통 `id`·`status`·`createdAt`·`updatedAt` — 자체 id·시각 컬럼 금지). 단일 행으로 운용한다.

| 필드 | 타입 | 컬럼 | 제약 | 설명 |
|------|------|------|------|------|
| minSupportedVersion | String | `min_supported_version` | NOT NULL, VARCHAR(20) | 최소 지원 버전 (semver) |
| latestVersion | String | `latest_version` | NOT NULL, VARCHAR(20) | 최신 버전 (semver) |
| iosStoreUrl | String? | `ios_store_url` | NULL, VARCHAR(512) | 앱스토어 링크 (미배포·미설정이면 null) |
| aosStoreUrl | String? | `aos_store_url` | NULL, VARCHAR(512) | 플레이스토어 링크 (현재 미배포 — null) |

- **도메인 메서드**: `update(minSupportedVersion, latestVersion, iosStoreUrl, aosStoreUrl)` — 전체 값 치환. 상태 전이·파생 로직 없음.
- **연관관계 없음** — 다른 도메인을 참조하지 않는 독립 컨텍스트(`ModuleBoundaryTest` 허용 맵 `"appversion" to emptySet()`).
- **소프트 삭제**: `BaseEntity` 의 `@SQLRestriction("status = 'ACTIVE'")` 상속. 이 행은 삭제 대상이 아니다(delete 시나리오 없음).

### 리포지토리 (`AppVersionRepository`)

- Spring Data JPA, public. 단일 행 조회는 `findTopByOrderByIdAsc()` 파생 쿼리 하나면 충분하다.

## 검증 규칙 (요청 경계 소유)

`AdminAppVersionUpdateRequest` (`com.kbap.api.admin`):

- `minSupportedVersion`·`latestVersion`: `@field:NotBlank` + `@field:Pattern("^\\d+\\.\\d+\\.\\d+$")` (semver)
- `iosStoreUrl`·`aosStoreUrl`: nullable, 값이 있으면 `@field:Size(max = 512)`

## Flyway 마이그레이션

파일명은 생성 시각 timestamp 포맷 `V2026.08.13.HH.mm.ss__app_version_table.sql` (구현 시점에 확정).

```sql
CREATE TABLE app_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    min_supported_version VARCHAR(20) NOT NULL,
    latest_version VARCHAR(20) NOT NULL,
    ios_store_url VARCHAR(512) NULL,
    aos_store_url VARCHAR(512) NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

INSERT INTO app_version (min_supported_version, latest_version, ios_store_url, aos_store_url, status, created_at, updated_at)
VALUES ('1.0.0', '1.0.1', NULL, NULL, 'ACTIVE', NOW(6), NOW(6));
```

- 시드 ios 링크: 구현 시점에 실제 앱스토어 URL 이 확정돼 있으면 NULL 대신 그 값으로 시드한다(R3).
- 인덱스 불필요 — 단일 행 PK 테이블. 다른 마이그레이션과 순서 의존 없음(독립 실행 가능).
- `status`·`created_at`·`updated_at` 컬럼 정의는 기존 테이블들과 동일 관례를 따른다(구현 시 최근 마이그레이션에서 확인).
