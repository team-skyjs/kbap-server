# KB-794 검증 기록

기능 브랜치 기준이며 dev에 새 계약이 배포됐다는 기록이 아니다. 모든 콘텐츠는 로컬 합성 fixture다. 실제 사진 업로드, 외부 링크 접근, 콘텐츠 게시, 유료 모델 호출, prod 쓰기를 수행하지 않았다.

최종 결과: 전체 `./gradlew build` 성공(16m 19s). 2859개 중 2857개 통과·2개 건너뜀, 실패/오류 0. api 2120 / common 646 / batch 93. 최신 bootJar의 실제 로컬 HTTP 62건 통과. Sentry 구조 검사·ArchUnit·동시 최초 게시·동시 자산 완료 포함. 상세 근거는 `specs/kb-794-curation-feature/verification.md` 및 manifest 참조.

## 재현과 범위

`./gradlew build --console=plain`로 api/common/batch 전체를 검증한다. 결과와 소스 digest는 `verification-manifest.json`에 기록한다. MySQL Testcontainers의 실제 Flyway→Hibernate validate가 새 common 엔티티 매핑도 검사한다. 선행 SQL은 SHA256 `39aec57bd4689cce72c6bbd1fde3de149f8efbd68dd72e1e1a54dcdeab8572fb`로 동일하다.

검증기는 불완전 구현으로 시작한 Red 2건→Green 2건, 공개 HTTP 경로는 404 Red→미게시 200 Green을 거쳤다. 확대 회귀 중 동일 초안 저장의 version 미증가를 실제 실패로 확인하고 강제 version 증가로 수정했다. MockMvc 기본 버전 헤더 때문에 생긴 잘못된 누락 테스트는 헤더를 명시 제거하도록 고쳤다. 별도 ControllerAdvice가 기존 Sentry 구조 검사를 위반한 문제는 중앙 예외 처리기를 유지하는 MVC 등록으로 수정했다. 이 이전 실행들은 최종 성공 근거에 합산하지 않는다.

## 회귀 시나리오

- 고정 세 모음, 항목 9/10/11개, 10개 언어 결손, stations 근처 장소 2~3개, 잘못된 UUID·중복 장소·외부 URL.
- 표지와 항목 사진의 NONE/TEXT/LINK/UNKNOWN/null, 증빙·검수일·checkedAssetId, 문서 변경으로 무효가 되는 검수 hash.
- 세 모음 최초 공개 이전 비노출, 최초 활성화 latch, 내림 후 나머지 상세 유지, 잘못된 초안의 게시 실패 시 기존 게시본 보존.
- stale expectedVersion, 동일 초안 재저장, 동시 초안 저장, 게시·내림 경합, 각 TX가 먼저 과거 snapshot을 읽은 뒤 세 모음을 동시에 처음 게시하는 재현.
- 활성 ADMIN/USER/미인증/없는 관리자, 대상 환경 불일치, 버전·lang 누락/blank, null JSON 및 1MiB 초과 요청.
- 공개 10언어 DTO의 내부 필드 비노출, 전체 영어 fallback, 영어 결손 503, Cache-Control no-store.
- 발급자·모음·환경·형식·완료기한, 원본 변경 시 조건부 복사 거부, 복사 후 기한 초과, 동시에 완료한 두 요청의 동일 최종 결과, 완료 후 staging 변경에도 최종 결과 유지.
- S3 SDK 요청의 CopySourceIfMatch/복사 경로/HEAD metadata는 mock으로 검사한다. 실제 S3 서비스의 정책을 검증한 결과는 아니다.

## 실제 로컬 HTTP

별도 MySQL/Redis와 임시 JWT, placeholder 모델 키 및 dummy AWS 설정으로 최신 bootJar를 띄운다. 공개·관리 조회, 3개 초안 검수와 게시, 최초 공개 전후, 10언어, stale 충돌, 9개 초안 게시 거절, 업로드 URL 형식, 이미 READY인 합성 자산의 완료 재시도, 내림 후 다른 상세 유지, 실제 Swagger를 관측한다. 최신 실행 파일에서 null 및 1MiB 초과 요청 거절까지 포함해 총 62건을 확인했다. 임시 API·MySQL·Redis와 JWT는 검증 후 정리했다. 사진 객체의 실체는 업로드하지 않으므로 이미지 다운로드나 S3 신규 complete 성공의 증거로 사용하지 않는다.

`local-http-examples.json`은 실측 응답 중 일부이며 Authorization을 포함하지 않는다. 전체 응답·Swagger·빌드 로그의 로컬 경로와 SHA256은 manifest에 남긴다. 최종 테스트 집계와 HTTP 관측 수는 manifest를 기준으로 한다.

## 정적 검토와 한계

`kbap-code-review` 체크리스트를 현재 AGENTS.md의 3모듈·엔티티=도메인 규칙에 맞춰 적용한다. 오래된 스킬의 폐기된 계층 및 ko fallback 설명 대신 현재 승인 계약을 따른다. 독립 리뷰어의 승인으로 표기하지 않는다. 신규 Kotlin 주석 없음, BaseResponse 응답, common→api 역방향 없음, 외부 I/O의 트랜잭션 분리, SQL 무변과 diff 공백 오류를 확인한다.

실제 dev 배포 후 Swagger 및 새 S3 prefix의 IAM/CORS/CDN 검증은 남아 있다. 자동 정리는 추가하지 않았으며 staging·패자 복사본의 참조 안전 cleanup은 별도 운영 작업이다. CI 결과는 PR의 정확한 head를 기준으로 별도 확인한다.
