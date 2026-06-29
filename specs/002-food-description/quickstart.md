# Quickstart: 음식 설명(간단·자세) 확인

**Feature**: 002-food-description | **Date**: 2026-06-29

## 빌드·테스트

```bash
./gradlew :meogo-api:food:test                 # 도메인 불변(설명 non-null/길이)
./gradlew :meogo-api:persistence:test          # 번역 조회·폴백 영속(H2)
./gradlew :meogo-api:application:test           # use case 독립 폴백
./gradlew :meogo-api:presentation:test          # web 응답 필드(MockMvc)
./gradlew build                                 # 전체
```

## 로컬 실행 (MySQL, V4 적용)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :meogo-api:presentation:bootRun
```

V4 가 `food.brief_description`·`detailed_description` 컬럼과 `food_description_translation` 테이블 + seed 를 적용한다.

## 확인 시나리오

```bash
# 1) 영어 — 간단·자세 설명 영어로
curl 'http://localhost:8080/api/v1/foods/detail?menuName=된장찌개&lang=en'
#   payload.briefDescription / payload.detailedDescription = 영어 텍스트

# 2) 미지정 — ko 폴백
curl 'http://localhost:8080/api/v1/foods/detail?menuName=된장찌개'
#   두 설명 모두 한국어 원문

# 3) 미지원 lang — ko 폴백
curl 'http://localhost:8080/api/v1/foods/detail?menuName=된장찌개&lang=xx'

# 4) 미수록 메뉴 — 400 (회귀)
curl 'http://localhost:8080/api/v1/foods/detail?menuName=없는메뉴'
#   { success:false, message:"해당 음식 정보 없음" }
```

## 기대 결과 요약

- `briefDescription`·`detailedDescription` 가 응답 payload 에 **항상 non-null** 로 포함.
- 지원 lang → 해당 언어, 미지원/미지정/번역부재 → 설명별 **독립** ko 폴백.
- 음식명·재료·이미지·400 동작은 기존과 동일(회귀 없음).
