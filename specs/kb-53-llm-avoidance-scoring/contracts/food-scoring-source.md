# Contract: 음식 공급 seam (`FoodScoringSource`)

**Feature**: kb-53-llm-avoidance-scoring | 조사 대상 음식을 청크로 공급하는 port. 배치 잡이 소비, `:infra:persistence` 가 구현, 테스트는 페이크.

## Port — `com.meogo.core.food`
```
interface FoodScoringSource {
    fun nextChunk(page: Int, size: Int): List<Food>
}
```
- **계약**: `page`(0-base)·`size`(≥1) 로 조사 대상 음식을 페이지 단위 공급 — 해당 페이지의 음식을 `size` 이하로 반환. 더 없으면 빈 리스트(잡은 종료). `size` 초과 반환 금지. 정렬은 결정적(id asc)이라 페이지 간 누락·중복이 없다.
- **초기 구현**(`FoodScoringSourceAdapter`): active `food`(soft-delete 제외는 BaseEntity `@SQLRestriction` 자동) 를 `PageRequest.of(page, size)` 로 페이지 조회해 공급. 스키마·마이그레이션 **무변경**. **재조사 상태·중복제거(이미 스코어링된 음식 스킵)·재시도는 후속**(research.md D6, 영속 마커=KB-54 필요) — 즉 **run 내 전체 큐 페이징 소진은 이 계약이 보장**하고, **run 간 이미 처리한 음식 스킵**만 후속이다.
- **배치 사용**: 잡이 `nextChunk(0, size)`·`nextChunk(1, size)`… 로 **page 를 전진**하며 빈 리스트까지 반복 호출해 **한 run 에 전체 active 큐를 소진**한다. 각 청크를 `ScoringFood` 로 매핑해 파이프라인 투입. (비전진 어댑터 오작동 대비 seen-foodId 가드도 병행.)

## 테스트 계약
- 페이크 `FoodScoringSource`(고정 음식 목록·잔여<size 마지막 청크·빈 대기열)로 잡 종단 검증.
- 어댑터: MySQL Testcontainers 로 active food 조회·`size` 상한 검증([[mysql-testcontainers-setup]]).

## 경계/주의
- "조사 필요" 판별 기준(매핑 부재 vs 재조사 플래그)은 초기 미확정 — 초기엔 단순 공급, 정교화는 후속 태스크.
- 전용 대기열 테이블 도입 시 이 port 계약(청크 공급)은 유지하고 어댑터 구현만 교체(seam 안정성).
