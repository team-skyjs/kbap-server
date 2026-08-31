# Research: 음식 이미지·스캔 이미지 저장 키 규약 정비

## R1. 음식 이미지 저장 폴더 — `images/food/` vs `images/menus/`

- **Decision**: `images/food/{sha256(음식명)[:12]}_{uuid16}.png`
- **Rationale**: Jira KB-243 DoD 는 `images/menus/` 를 지시했으나 플랜 단계에서 사용자가 `images/food/` 로 확정했다(현재 배치 산출물 폴더 유지 + 파일명만 규약화). 캐시 무효화 문제의 원인은 폴더가 아니라 uuid 없는 파일명이므로 폴더 선택은 결함 해소와 무관하다.
- **Alternatives considered**: `images/menus/`(Jira 원안) — 기존 시딩 이미지와 같은 폴더로 합쳐지는 장점이 있으나 사용자 결정으로 기각. Jira 이슈 본문 갱신 필요(후속).

## R2. 해시·uuid 자릿수

- **Decision**: sha256(음식명 UTF-8) 소문자 hex 앞 12자리 + UUID hex(하이픈 제거) 앞 16자리, 구분자 `_`, 확장자 `png`.
- **Rationale**: 기존 시딩 도구(kbap-image-maker)·DB 의 기존 `image_ref` 형식(`7eb8f793d0c9_bee76f920e204f64.png`)과 파일명 규약을 일치시킨다. 사용자 입력은 자릿수를 지정하지 않아 기존 규약을 따른다.
- **Alternatives considered**: 전체 해시/전체 uuid — 키만 길어지고 이득 없음.

## R3. 음식명 확보 시점 (회수 흐름)

- **Decision**: S3 put 전에 `foodRepository.findById` 로 음식을 1회 읽어 이름으로 키를 만들고, put 후 기존 트랜잭션(재조회·attachImage·item.done)은 현행 유지한다. put 전 조회에서 음식이 없으면 put 없이 해당 항목을 fail 처리한다.
- **Rationale**: 외부 호출(S3 put)은 트랜잭션 밖이어야 한다(헌법). 현재 키는 foodId 만으로 만들 수 있었지만 새 키는 음식명이 필요하다. 읽기 1회 추가가 가장 작은 변경.
- **Alternatives considered**: (a) 제출 시점에 ImageBatchItem 에 음식명/키를 미리 저장 — 컬럼 추가·마이그레이션이 필요해 과함. (b) put 을 트랜잭션 안으로 이동 — 헌법 위반.

## R3-1. put 이후 트랜잭션 실패 재시도의 고아 객체 방지 (리뷰 반영)

- **Decision**: put **전에** 생성 키를 `ImageBatchItem.fileName` 에 예약 저장하고, 재시도 시 예약 키가 있으면 재사용한다(같은 키 덮어쓰기 — 고아 없음).
- **Rationale**: 키가 랜덤(uuid)이 되면서, put 성공 후 항목 트랜잭션이 실패(일시 DB 장애 등)하면 PENDING 재시도마다 새 키로 객체가 쌓이는 회귀가 생겼다. 예약-재사용은 delete 보상(베스트에포트)과 달리 S3 장애 시에도 누수가 없고 기존 컬럼(varchar 500)을 그대로 쓴다. 예약 쓰기 자체가 실패하면 put 전에 중단되므로 역시 고아가 없다.
- **Alternatives considered**: 트랜잭션 실패 시 best-effort `delete(key)` — 구현은 짧지만 delete 실패 시 누수가 남고, 이 코드베이스의 페이크 통합 테스트로 실패 주입이 어렵다.

## R4. 스캔 업로드 키 구조

- **Decision**: `{환경접두}/images/scans/{yyyy}/{mm}/{memberId}_{uuid}.{ext}` — 폴더는 연/월까지, 회원ID 는 파일명 접두로 이동, uuid 는 현행(전체 36자) 유지, 환경접두는 KB-171 대로 유지.
- **Rationale**: 사용자 결정 그대로. uuid 자릿수·환경접두는 지정이 없어 현행 유지(발급 키의 유일성·환경 분리 보존).
- **Alternatives considered**: uuid16 단축(음식 규약과 통일) — 지정 없고 이득 없어 기각.

## R5. 다른 업로드 목적(profile·review)의 처리

- **Decision**: `objectKey` 포맷 문자열이 전 목적 공용이므로 파일명 형태(`{memberId}_{uuid}`)는 함께 바뀐다. 폴더명은 MENU_SCAN 만 `scan`→`scans` 로 바꾸고 `profile`·`review` 는 유지한다.
- **Rationale**: 목적별 포맷 분기를 만드는 것보다 단일 포맷 유지가 단순하다. 사용자는 스캔 폴더명만 지정했다. 발급 키는 매번 새로 만들어지므로 기존 저장 객체와의 호환 문제가 없다.
- **Alternatives considered**: 스캔만 새 포맷, 나머지는 구 포맷 유지 — 포맷 문자열 2개 분기가 생기고 이득 없음.

## R6. 기존 저장 객체·경로 호환

- **Decision**: 소급 이관·리네임 없음. `Food.imageRef`·`UploadedImage.path` 는 저장된 문자열 그대로 서빙/검증에 쓰이므로 규약 변경의 영향이 없다.
- **Rationale**: 조회 경로는 전부 저장 문자열 기반(`ImageUrls.resolve`, `findByPath`)임을 확인했다. 키 형식을 검사하는 소비 코드는 없다.
- **Alternatives considered**: `images/food/{id}.png` 잔존 객체 일괄 리네임 — 참조 갱신 배치가 필요해지고, 재생성 시 자연 대체되므로 불요.

## R7. 스키마 영향

- **Decision**: 변경 없음.
- **Rationale**: 새 키 최대 길이(수십 자)는 `food.image_ref`·`image_batch_item.file_name` varchar(500) 안에 충분히 든다.
