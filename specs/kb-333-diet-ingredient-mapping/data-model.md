# Data Model: diet 카테고리별 회피 재료 매핑 조회

신규 영속 모델 없음 — 기존 `ingredients` 테이블(`Ingredient` 엔티티) 재사용. 이 문서의 핵심은 **기획 번호표 → `IngredientCode` 변환표**(매핑 단일 출처)다.

## 번호 → 코드 대응 (시드 1-based 행 순서 = AUTO_INCREMENT id)

```text
 1 EGG          12 PEANUT       23 SESAME          34 PEA            45 OYSTER_SAUCE  56 COD         67 PEACH       78 ALCOHOL
 2 MILK         13 WALNUT       24 SUNFLOWER_SEED  35 CHICKPEA      46 ABALONE       57 ANCHOVY     68 TOMATO      79 MIRIN
 3 DAIRY        14 PINE_NUT     25 MUSTARD         36 LENTIL        47 MUSSEL        58 FISH_SAUCE  69 CELERY      80 COOKING_WINE
 4 GOAT_MILK    15 ALMOND       26 WHEAT           37 SHRIMP        48 CLAM          59 BROTH       70 POTATO      81 SULFITES
 5 BUTTER       16 CASHEW       27 BUCKWHEAT       38 SALTED_SHRIMP 49 SHORT_NECK_CLAM 60 DASHI     71 CARROT
 6 GHEE         17 PISTACHIO    28 BARLEY          39 CRAB          50 SCALLOP       61 BEEF        72 ONION
 7 CHEESE       18 HAZELNUT     29 RYE             40 CRAYFISH      51 SEAFOOD       62 PORK        73 GARLIC
 8 GELATIN      19 MACADAMIA    30 OAT             41 LOBSTER       52 FISH          63 LARD        74 SCALLION
 9 RENNET       20 PECAN        31 CORN            42 SQUID         53 MACKEREL      64 TALLOW      75 CHIVE
10 HONEY        21 BRAZIL_NUT   32 SOY             43 OCTOPUS       54 SALMON        65 CHICKEN     76 WILD_CHIVE
11 CARMINE      22 CHESTNUT     33 LUPIN           44 OYSTER        55 TUNA          66 POULTRY     77 ASAFOETIDA
```

자주 쓰는 구간: **37~66**(동물성 30종) = SHRIMP~POULTRY, **37~51**(갑각류·연체·해산물 15종) = SHRIMP~SEAFOOD, **72~77**(오신채 6종) = ONION~ASAFOETIDA, **70~76**(근채류 포함 7종) = POTATO~WILD_CHIVE.

## DietCategory enum (`com.kbap.api.ingredient`)

| 코드 | 표시명(ko) | 번호(기획 표) | IngredientCode 집합 |
|------|-----------|--------------|---------------------|
| VEGAN | 비건 | 1~11, 37~66 | EGG~CARMINE(1~11) + 37~66 (41종) |
| VEGETARIAN | 베지테리언 | 8, 9, 11, 37~66 | GELATIN, RENNET, CARMINE + 37~66 (33종) |
| LACTO_VEGETARIAN | 락토 베지테리언 | 1, 8, 9, 11, 37~66 | EGG, GELATIN, RENNET, CARMINE + 37~66 (34종) |
| OVO_VEGETARIAN | 오보 베지테리언 | 2~9, 11, 37~66 | MILK, DAIRY, GOAT_MILK, BUTTER, GHEE, CHEESE, GELATIN, RENNET, CARMINE + 37~66 (39종) |
| PESCATARIAN | 페스코테리언 | 8, 9, 11, 59, 61~66 | GELATIN, RENNET, CARMINE, BROTH, BEEF, PORK, LARD, TALLOW, CHICKEN, POULTRY (10종) |
| GLUTEN_FREE | 글루텐 프리 | 26, 28, 29, 30 | WHEAT, BARLEY, RYE, OAT (4종 — 메밀 27 제외) |
| LACTOSE_FREE | 유당 불내증 | 2~7 | MILK, DAIRY, GOAT_MILK, BUTTER, GHEE, CHEESE (6종) |
| NO_ALCOHOL | 무알코올 | 78, 79, 80 | ALCOHOL, MIRIN, COOKING_WINE (3종) |
| MUSLIM | 무슬림(할랄) | 8, 9, 11, 59, 62, 63, 64, 78, 79, 80 | GELATIN, RENNET, CARMINE, BROTH, PORK, LARD, TALLOW, ALCOHOL, MIRIN, COOKING_WINE (10종) |
| HINDU | 힌두교 | 8, 9, 59, 61, 64 | GELATIN, RENNET, BROTH, BEEF, TALLOW (5종) |
| KOSHER | 유대교(코셔) | 8, 9, 11, 37~51, 59, 62, 63 | GELATIN, RENNET, CARMINE + 37~51 + BROTH, PORK, LARD (21종) |
| BUDDHIST | 불교(사찰식) | 1, 8, 9, 11, 37~66, 72~77 | EGG, GELATIN, RENNET, CARMINE + 37~66 + ONION, GARLIC, SCALLION, CHIVE, WILD_CHIVE, ASAFOETIDA (40종) |
| JAIN | 자이나교 | 1, 8, 9, 10, 11, 37~66, 70~76 | EGG, GELATIN, RENNET, HONEY, CARMINE + 37~66 + POTATO, CARROT, ONION, GARLIC, SCALLION, CHIVE, WILD_CHIVE (42종) |
| NUT_ALLERGY | 견과류 알레르기 | 13~22 | WALNUT, PINE_NUT, ALMOND, CASHEW, PISTACHIO, HAZELNUT, MACADAMIA, PECAN, BRAZIL_NUT, CHESTNUT (10종 — 땅콩 12 제외) |
| SHELLFISH_ALLERGY | 갑각류·조개 알레르기 | 37~51 | SHRIMP ~ SEAFOOD (15종) |

- 필드: `koreanName: String`(표시명), `avoidedIngredients: Set<IngredientCode>`. enum 선언 순서 = 기획 표 순서 = 응답 순서.
- 검증 규칙: 빈 집합 금지(15종 전부 1개 이상), 코드는 `IngredientCode` 타입으로 컴파일 강제. 번호표와의 전수 일치는 `DietCategoryMappingSyncTest` 가 시드 SQL 행 순서 파싱으로 강제(research R3).
- 상태 전이: 없음(불변 상수).

## 응답 DTO (`com.kbap.api.ingredient`)

```text
DietListResponse
└── diets: List<DietItemResponse>            # 15종, enum 선언 순서
    ├── code: String                         # 예: "VEGAN" — 클라이언트 분기용 안정 식별자
    ├── name: String                         # 한국어 표시명(예: "비건")
    └── ingredients: List<DietIngredientResponse>   # 재료 id 오름차순
        ├── id: Long                         # ingredients.id
        ├── code: String                     # 재료 코드(예: "WHEAT") — 클라이언트 분기용 안정 식별자
        └── name: String                     # 요청 lang 표시명(미지원 코드→en, 번역 부재→ko)
```

요청 DTO `DietListRequest`: `lang: String`(`@field:NotBlank` — 헌법 V, 기존 `IngredientListRequest` 와 동일 규약).

## 관계

- `DietCategory` —(코드 집합)→ `IngredientCode` —(코드 일치)→ `Ingredient` 엔티티(row). JPA 연관 없음, 신규 FK 없음.
- 한 재료가 여러 카테고리에 속할 수 있다(예: GELATIN 은 12개 카테고리에 등장) — 응답에서 카테고리별로 중복 포함(정상).
