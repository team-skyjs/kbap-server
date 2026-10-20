# Research — KB-836

- **Decision**: 전용 엔드포인트, 홈·상세 무변. **Rationale**: 사용자 결정(2026-10-21) — 기존 API 에 섞지 않는다.
- **Decision**: 매핑 전체 `findAll`(@SQLRestriction 으로 ACTIVE) 후 앱에서 음식별 position 최소 → 정렬. **Rationale**: 수십 행 소형 테이블, 규칙이 코드 한 곳. **Alternatives**: 윈도 함수 native 쿼리 — 행 수 대비 과함.
- **Decision**: 시드는 같은 마이그레이션에 `INSERT … SELECT … WHERE korean_name`. **Rationale**: 환경별 id 차이·빈 테스트 DB 에서 FK 실패 없음(D8).
- **Decision**: 항목은 `@JsonUnwrapped FoodSummaryResponse` + `video`. **Rationale**: `RecentScanResponse` 선례, FE 가 기존 요약 어댑터 재사용.
- **Decision**: 헤더 누락 400 은 curl 로 검증. **Rationale**: 테스트 MockMvc 는 `MockMvcDefaultVersionConfig` 가 기본 헤더를 붙여 재현 불가.
