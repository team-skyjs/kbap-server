# Quickstart: 전 API URI 버전 제거 + 버전 헤더 필수화

## 검증 실행

```bash
./gradlew :api:test --tests "com.kbap.api.core.config.ApiVersionRequiredTest"   # 무헤더 400·app-version 예외·구 경로 404
./gradlew build                                                                  # 전체 회귀 (경로 치환 그물)
grep -rn 'ApiPaths.V1\|api/v1' api/src common/src infra/*/src batch/src         # 0건이어야 함
```

## 수동 확인 (local)

```bash
curl -si http://localhost:8080/api/members/me                          # 400 COMMON-002 (헤더 없음)
curl -si -H 'X-API-Version: 1.0' http://localhost:8080/api/app-version # 200 (예외 경로는 헤더 있어도 됨)
curl -si http://localhost:8080/api/app-version                         # 200 (헤더 없이도)
curl -si http://localhost:8080/api/v1/members/me                       # 404 (구 경로 소멸)
```

## 구현 시 주의

- WebConfig 폴백 리졸버가 예외 범위의 단일 출처 — 필터·컨트롤러에 예외 로직을 흩뿌리지 않는다.
- v1 스캔 컨트롤러는 `/api/scans` 로 이동하면 v2 와 같은 경로가 된다 — 버전 속성(기본 vs `2.0+`) 분기가 유일한 구분자이므로 매핑 충돌 여부를 테스트로 확인.
- MockMvc 기본 헤더 주입(customizer) 후에도 기존 테스트 중 X-API-Version 을 명시하는 곳(scan v2)과의 병합 동작 확인.
- 배포 순서 엄수: kbap-langchain 헤더 추가 → iOS 새 릴리스 전환 → 서버 개정 → minSupportedVersion 상향 (plan 참조).
- CLAUDE.md·meogo-conventions 경로 규약 절 갱신을 잊지 말 것.
