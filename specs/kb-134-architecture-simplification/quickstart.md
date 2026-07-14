# Quickstart: 아키텍처 단순화 (KB-134) 검증 절차

## 완료 판정 체크

```bash
# 1. 전체 빌드 + 테스트 (ArchUnit 새 규칙 포함)
./gradlew clean build

# 2. 옛 구조 잔재 0건 확인
ls infra/persistence 2>/dev/null && echo "FAIL: persistence 잔존"
grep -rn "com.meogo.infra.persistence" --include="*.kt" --include="*.kts" . | grep -v build/ && echo "FAIL"
grep -rn "@OneToMany\|@ManyToOne\|@OneToOne\|@ManyToMany" --include="*.kt" . | grep -v build/ && echo "FAIL"
grep -rn -i "mongo" --include="*.yml" --include="*.kts" --include="*.toml" app/ gradle/ docker-compose*.yml | grep -v build/ && echo "FAIL"

# 3. internal 경계 확인 — 도메인 밖에서 엔티티 참조 시 컴파일 실패해야 함 (수동 스팟 체크)
#    예: application:client 에 MemberJpaEntity import 를 임시로 넣고 컴파일 에러 확인 후 되돌린다

# 4. 로컬 실행 검증 (스키마·부팅·Swagger 계약 육안 확인)
docker compose up -d mysql            # mongo 서비스는 제거됨
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun   # IntelliJ 실행 중이면 생략
# http://localhost:8080/swagger-ui/index.html — 엔드포인트·스키마 변경 없음 확인

# 5. 배치 부팅 검증
./gradlew :app:batch:test
```

## 리뷰 포인트

- 도메인 서비스가 유일한 public 창구인가 (엔티티·Spring Data 리포지토리에 `internal` 누락 없나)
- `FoodService` 의 자식(food_avoidance_substance) 명시 저장·삭제가 기존 cascade 동작과 동일한가 (교체 저장 통합 테스트)
- 삭제된 페이크 port 테스트의 시나리오가 도메인 서비스/컨트롤러 통합 테스트에 전부 승계됐는가 (tasks 의 1:1 매핑표 대조)
- 헌법 v3.0.0 + ADR-0012 + CLAUDE.md 갱신이 코드와 일치하는가
- 값 클래스(FoodId·MemberId) JPQL 파라미터 바인딩이 통합 테스트로 검증됐는가
