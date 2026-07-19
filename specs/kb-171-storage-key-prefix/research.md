# Research: 이미지 업로드 객체 키 환경 접두(key-prefix) 지원

**Feature**: KB-171 | **Date**: 2026-07-20

Technical Context 에 NEEDS CLARIFICATION 없음 — Jira Background·DoD 가 결정을 명시하고, 기존 코드(KB-145·KB-154)가 패턴을 고정한다. 아래는 결정 확인 기록.

## R1. 프로퍼티 이름·위치

- **Decision**: `kbap.storage.key-prefix`, env `STORAGE_KEY_PREFIX`, 기본 빈 값.
- **Rationale**: Jira DoD 명시. 버킷(`kbap.storage.bucket`)·공개 URL(`kbap.storage.public-base-url`)과 같은 storage 군 — 저장소 레이아웃 관심사이지 업로드 정책(`kbap.upload.*` — 형식·크기·TTL)이 아니다.
- **Alternatives considered**: `kbap.upload.key-prefix` — 업로드 정책 군에 두는 안. 접두는 버킷 공유 구조에서 오는 저장소 관심사라 기각.

## R2. 접두 결합 지점과 정규화 규칙

- **Decision**: `ImageUploadApplicationService.objectKey()` 에서 `keyPrefix.trim('/')` 후 빈 값이면 기존 키, 아니면 `"$prefix/$key"` 로 결합.
- **Rationale**: 키 생성의 유일 지점이라 결합도 여기 1곳. `trim('/')` 하나로 `dev`/`dev/`/`/dev` 세 입력이 동일 결과가 되고 선행·중복 슬래시가 구조적으로 불가능 — FR-003 을 최소 코드로 충족.
- **Alternatives considered**:
  - S3 어댑터(`S3PresignedUploadPort`)에서 결합 — 키 규칙이 두 곳으로 갈라지고 단위 테스트가 :infra 로 밀림. 기각.
  - 별도 KeyPrefix 값 타입 — 문자열 1개에 타입 하나는 과설계. 기각.

## R3. yml 선언 범위 (2026-07-20 개정 — 사용자 결정)

- **Decision**: base `application.yml` 에 `key-prefix: ${STORAGE_KEY_PREFIX:local}`(local 프로필·미지정 실행), 테스트 yml 에 `local`, dev·staging·prod 프로필 yml 에 **환경명 기본값** `${STORAGE_KEY_PREFIX:dev|staging|prod}` 선언. env 는 커밋 없는 오버라이드(빈 값 반전 포함).
- **Rationale**: KB-169(`REDIS_SSL_ENABLED` 프로필 기본값+env 반전)와 동일 관례 — 인프라 env 추가 없이 배포만으로 전 환경 폴더 분리가 완성되고, 특수 사정은 env 로 커밋 없이 반전한다. 버킷 최상위가 `local/`·`dev/`·`staging/`·`prod/`·`images/`(음식 사진 등 환경 공용 자산)로 정리된다 — 향후 배치의 음식 사진 제작은 업로드 API 밖에서 `images/menus/…` 에 직접 기록하므로 이 설정과 무관.
- **Alternatives considered**:
  - (최초안) 기본 빈 값 + 인프라 env 주입(`${STORAGE_KEY_PREFIX:}`), prod·local 미선언 — Jira DoD 원문. 인프라 env 작업이 선행돼야 효력이 생기고 prod 는 레거시 혼재가 지속돼 사용자 결정으로 대체.
  - `@Value` 기본값만으로 처리(yml 무선언) — 설정 표면이 코드에만 숨어 운영 가시성이 없다. 기각.

## R4. URL 조립·저장 경로 영향

- **Decision**: 변경 없음을 확인만 한다.
- **Rationale**: 접두 포함 키가 발급 응답 `objectKey` 로 나가고 클라이언트가 그 값을 ref 로 제출·저장(KB-154 — DB 에는 경로만). `ImageUrls.resolve(base, ref)` 는 ref 를 경로 그대로 접합하므로 접두는 경로의 일부일 뿐 — 조립 규칙·프로필 경로 검증(`http(s)://` 시작 거부) 모두 영향 없음.
- **Alternatives considered**: 없음(무변경 확인).

## R5. 테스트 표면

- **Decision**: `ImageUploadApplicationServiceTest`(:application, 페이크 port) 확장만 — (1) 접두 `dev` → 키가 `dev/images/…`, (2) 빈 값 → 기존 구조(기존 시나리오가 이미 커버 — `^images/` 정규식), (3) 슬래시 변형(`dev/`·`/dev`) → 동일 정규화 결과.
- **Rationale**: 키 생성은 순수 로직이고 페이크 port 가 키를 기록하는 기존 장치 재사용. 컨트롤러·통합·시나리오 테스트는 키 구조를 단정하지 않아 무수정 통과(SC-005).
- **Alternatives considered**: :app:api 통합 테스트에 프로퍼티 주입 검증 추가 — `@Value` 기본값 배선은 Boot 자동 동작이고 테스트 yml 이 프로필 yml 을 로드하지 않아 정보량이 낮다(KB-169 에서 같은 이유로 리소스 가드 테스트 폐기 선례). 기각.
