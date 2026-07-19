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

## R3. yml 선언 범위

- **Decision**: base `application.yml` 에 `key-prefix: ${STORAGE_KEY_PREFIX:}` 선언 + dev·staging 프로필 yml 에 동일 선언. prod·local 은 미선언(= base 기본 빈 값).
- **Rationale**: base 선언으로 전 환경이 env 에 반응하되 기본 빈 값이라 미설정 기동 실패 없음(FR-004·US3). dev·staging 프로필 중복 선언은 Jira DoD 명시 — 환경 설정 파일만 봐도 접두 사용 환경이 드러나는 가시성 목적(KB-169 프로필별 명시 선언 관례).
- **Alternatives considered**:
  - dev·staging 에 `dev`·`staging` 값 하드코딩 — env 없이도 접두가 걸려 편하지만, Jira 가 값 소유를 인프라(env)에 두기로 결정(`${STORAGE_KEY_PREFIX:}`). 하드코딩 시 환경 이름 변경·홈서버 dev 특수 사정에 커밋이 필요해져 기각.
  - `@Value` 기본값만으로 처리(yml 무선언) — 동작은 같으나 설정 표면이 코드에만 숨어 운영 가시성이 없다. 기각.

## R4. URL 조립·저장 경로 영향

- **Decision**: 변경 없음을 확인만 한다.
- **Rationale**: 접두 포함 키가 발급 응답 `objectKey` 로 나가고 클라이언트가 그 값을 ref 로 제출·저장(KB-154 — DB 에는 경로만). `ImageUrls.resolve(base, ref)` 는 ref 를 경로 그대로 접합하므로 접두는 경로의 일부일 뿐 — 조립 규칙·프로필 경로 검증(`http(s)://` 시작 거부) 모두 영향 없음.
- **Alternatives considered**: 없음(무변경 확인).

## R5. 테스트 표면

- **Decision**: `ImageUploadApplicationServiceTest`(:application, 페이크 port) 확장만 — (1) 접두 `dev` → 키가 `dev/images/…`, (2) 빈 값 → 기존 구조(기존 시나리오가 이미 커버 — `^images/` 정규식), (3) 슬래시 변형(`dev/`·`/dev`) → 동일 정규화 결과.
- **Rationale**: 키 생성은 순수 로직이고 페이크 port 가 키를 기록하는 기존 장치 재사용. 컨트롤러·통합·시나리오 테스트는 키 구조를 단정하지 않아 무수정 통과(SC-005).
- **Alternatives considered**: :app:api 통합 테스트에 프로퍼티 주입 검증 추가 — `@Value` 기본값 배선은 Boot 자동 동작이고 테스트 yml 이 프로필 yml 을 로드하지 않아 정보량이 낮다(KB-169 에서 같은 이유로 리소스 가드 테스트 폐기 선례). 기각.
