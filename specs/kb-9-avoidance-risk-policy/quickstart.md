# Quickstart 검증: 기피성분 위험도 정책 (KB-9)

구현 후 정책이 목을 실제로 대체했는지 확인하는 시나리오. 스키마 변경이 없어 마이그레이션 실측은 불필요하고, 테스트(단위·통합)와 로컬 기동으로 검증한다.

## A. 테스트 검증 (TDD 산출물 재실행)

```bash
./gradlew :core:kernel:test --tests "*RiskLevel*"
./gradlew :core:food:test --tests "*FoodOverallRisk*"
./gradlew :application:client:test --tests "*GetFoodDetail*" --tests "*MockAvoidedSubstanceProvider*"
./gradlew :app:api:test --tests "*FoodDetail*"
./gradlew build   # 전체 그린 + 삭제된 MockAvoidanceRiskMarker 참조 잔존 없음
```

### 통과 기준
- **경계값(kernel)**: 9→SAFE, 10→CAUTION, 59→CAUTION, 60→DANGER, 100→DANGER.
- **집계(kernel)**: `[]`→SAFE, `[SAFE,CAUTION,DANGER]`→DANGER, `[SAFE,UNKNOWN]`→UNKNOWN, `[SAFE,SAFE]`→SAFE.
- **종합(food)**: 회피 ∩ 성분 최악값, 공집합→SAFE, 성분없음→SAFE.
- **유스케이스**: 성분별 riskStatus=실제 확률 매핑, overallRiskStatus=목 회피 기반 종합, 미등록→FoodException(NOT_FOUND).
- **컨트롤러**: `payload.overallRiskStatus` 존재, `ingredients[].riskStatus` 실제값, 미등록 메뉴명→400.

## B. 로컬 기동 실측 (선택)

```bash
# docker MySQL + local 프로필 (user runs app; IntelliJ 기동 시 8080 점유 주의)
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun
```

```bash
# 된장찌개(SOY100·WHEAT80·CLAM50), 목 회피={SOY,MILK,PEANUT,SHRIMP,EGG}
curl "http://localhost:8080/api/v1/foods/detail?menuName=%EB%90%9C%EC%9E%A5%EC%B0%8C%EA%B0%9C&lang=en"
# 기대: payload.overallRiskStatus=="DANGER"
#       ingredients[SOY].riskStatus=="DANGER", [WHEAT]=="DANGER", [CLAM]=="CAUTION"

# 미등록 메뉴 → 400
curl -i "http://localhost:8080/api/v1/foods/detail?menuName=없는메뉴"
# 기대: HTTP 400, message "해당 음식 정보 없음"
```

## C. 회귀 체크리스트
- [ ] 목 위험도(첫 성분 CAUTION·나머지 SAFE) 흔적 완전 제거(`MockAvoidanceRiskMarker` 삭제).
- [ ] `overallRiskStatus` 최상위 추가 외 기존 응답 필드·언어 폴백·400 계약 불변.
- [ ] `:core:food` 가 `:core:avoidance` enum 을 import 하지 않음(원칙 II, ArchUnit `ModuleBoundaryTest` 그린).
- [ ] 성분 목록은 회피 목록으로 필터링되지 않음(음식 포함 성분 전체 노출).
- [ ] 스키마/마이그레이션/엔티티 무변경.
