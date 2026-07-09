# API Request Flows

멘토 공유용으로, 현재 구현된 API가 어떤 컴포넌트를 거쳐 응답을 만드는지 sequence diagram으로 정리한다.

## 구현된 API

| API | 목적 | 큰 흐름 |
| --- | --- | --- |
| `GET /api/v1/foods/detail` | 한국어 메뉴명으로 음식 상세 조회 | Controller -> UseCase -> Core Repository Port -> Persistence -> DB -> Response |
| `POST /api/v1/menu-scans` | 인식된 메뉴 목록 저장 및 위험도 판정 | Controller -> UseCase -> Core Aggregate/Port -> Persistence -> DB -> Response |

## 사용되는 도메인 모듈

| API | 도메인 모듈 | 역할과 책임 |
| --- | --- | --- |
| 음식 상세 조회 | `:core:food` | `Food`, `FoodContent`(음식명·설명 + 대상 언어 번역 맵·폴백), `FoodAvoidanceSubstance`, `FoodRepository`를 제공한다. 음식명/설명 번역은 `FoodContent`에 포함되어 음식 로드와 함께 온다(별도 조회 포트 없음). |
| 음식 상세 조회 | `:core:avoidance` | `AvoidanceSubstance`, `AvoidanceSubstanceCode`, `AvoidanceSubstanceRepository`를 제공한다. 성분 코드로 표시명 카탈로그를 조회해 요청 언어 표시명(ko 폴백)을 해석한다. |
| 음식 상세 조회 | `:core:kernel` | `LanguageCode`, `RiskLevel` 같은 공통 타입을 제공한다. 언어 코드와 위험도 값을 API/유스케이스/도메인 사이에서 공유한다. |
| 메뉴 스캔 제출 | `:core:scan` | `MenuScan`, `ScannedMenuItem`, `BoundingBox`, `MenuScanRepository`를 제공한다. 스캔 항목 개수, `itemId` 중복 같은 스캔 도메인 규칙을 담당한다. |
| 메뉴 스캔 제출 | `:core:kernel` | `RiskLevel` 같은 공통 타입을 제공한다. 스캔 항목 위험도 값에 사용된다. |

## 공통 요청 구조

```mermaid
sequenceDiagram
    actor Client
    participant Controller as Controller<br/>:app:api
    participant UseCase as UseCase<br/>:application:client
    participant Core as Domain / Repository Port<br/>:core:*
    participant Persistence as Repository Adapter<br/>:infra:persistence
    participant DB as Database
    participant Error as GlobalExceptionHandler<br/>:app:api

    Client->>Controller: HTTP request
    alt 요청 검증 실패
        Controller-->>Error: validation/domain error
        Error-->>Client: 400 BaseResponse.fail
    else 요청 정상
        Controller->>UseCase: input DTO 전달
        UseCase->>Core: 도메인 모델/포트 사용
        Core->>Persistence: repository adapter 호출
        Persistence->>DB: query or save
        DB-->>Persistence: rows / persisted entity
        Persistence-->>UseCase: domain result
        UseCase-->>Controller: result DTO
        Controller-->>Client: 200 BaseResponse.ok
    end
```

## 음식 상세 조회

```mermaid
sequenceDiagram
    actor Client
    participant Controller as FoodDetailController<br/>:app:api
    participant UseCase as GetFoodDetailUseCase<br/>:application:client
    participant Kernel as Kernel Types<br/>:core:kernel
    participant FoodCore as Food Domain / Repository Port<br/>:core:food
    participant AvoidanceCore as Avoidance Catalog Port<br/>:core:avoidance
    participant Persistence as Repository Adapters<br/>:infra:persistence
    participant DB as Food DB
    participant Error as GlobalExceptionHandler<br/>:app:api

    Client->>Controller: GET /api/v1/foods/detail?menuName=&lang=
    alt menuName blank
        Controller-->>Error: IllegalArgumentException
        Error-->>Client: 400 실패 응답
    else menuName valid
        Controller->>UseCase: GetFoodDetailInput
        UseCase->>Kernel: LanguageCode 결정, RiskLevel 사용
        UseCase->>FoodCore: 음식 + 포함 기피성분 조회 요청
        FoodCore->>Persistence: findByKoreanName(menuName)
        Persistence->>DB: food + food_avoidance_substance fetch join 조회
        DB-->>Persistence: 음식과 포함 기피성분(코드·포함 확률) 데이터
        Persistence-->>UseCase: Food domain<br/>Food, FoodAvoidanceSubstance
        alt 음식 없음
            UseCase-->>Error: IllegalArgumentException
            Error-->>Client: 400 실패 응답
        else 음식 있음
            UseCase->>AvoidanceCore: 성분 코드로 표시명 카탈로그 조회
            AvoidanceCore->>Persistence: findByCodes(codes)
            Persistence->>DB: avoidance_substance 조회
            DB-->>Persistence: 카탈로그(코드·한국어명·번역 JSON)
            Persistence-->>UseCase: AvoidanceSubstance 목록<br/>displayName(lang) ko 폴백
            Note over UseCase,FoodCore: 음식명·설명 번역은 FoodContent에 이미 포함<br/>content.name(lang)/description(lang) ko 폴백 (추가 조회 없음)
            UseCase->>UseCase: 확률 내림차순 정렬 + mock 위험도 표시 + 응답 결과 조립
            UseCase-->>Controller: GetFoodDetailResult
            Controller-->>Client: 200 FoodDetailResponse
        end
    end
```

음식 상세 조회는 현재 별도 캐시 계층 없이 요청마다 DB를 조회한다. 음식명·설명 번역이 `food` 행 JSON 칼럼에 있어 음식 로드와 함께 오므로, `ko`든 아니든 쿼리 수는 동일하다(음식+기피성분 fetch join 1회 + 표시명 카탈로그 1회). KB-48 이전 비-ko 요청의 번역 테이블 추가 조회 2회는 사라졌다.

**응답 계약은 동결이다** — 외부 JSON 키는 이전과 동일하게 `payload.ingredients[].{name,iconRef,inclusionPercent,riskStatus}`를 유지한다(클라이언트 무변경). 내부 의미만 재료→포함 기피성분으로 바뀌며, `inclusionPercent`는 이제 **포함 확률(1~100)**, `name`은 성분 표시명, `iconRef`는 현재 미제공(null)이다.

| 데이터 | 저장 위치 | 조회 시점 |
| --- | --- | --- |
| 음식 기본 정보, 한국어 설명, 맵기 | `food` | 음식 상세 요청마다 |
| 음식명·설명 대상 언어 번역(JSON) | `food.name_translations`·`food.description_translations` | 음식 로드에 포함(별도 조회 없음) |
| 음식-기피성분 매핑, 포함 확률(1~100) | `food_avoidance_substance` | 음식 상세 요청마다(음식과 fetch join 1회) |
| 기피성분 표시명 카탈로그 | `avoidance_substance` | 음식 상세 요청마다(`findByCodes` 1회) |

## 메뉴 스캔 제출

```mermaid
sequenceDiagram
    actor Client
    participant Controller as MenuScanController<br/>:app:api
    participant UseCase as MenuScanUseCase<br/>:application:client
    participant Kernel as Kernel Types<br/>:core:kernel
    participant ScanCore as Scan Aggregate / Repository Port<br/>:core:scan
    participant Persistence as MenuScanRepositoryAdapter<br/>:infra:persistence
    participant DB as Scan DB
    participant Error as GlobalExceptionHandler<br/>:app:api

    Client->>Controller: POST /api/v1/menu-scans
    alt 요청 검증 실패
        Controller-->>Error: validation error
        Error-->>Client: 400 실패 응답
    else 요청 정상
        Controller->>UseCase: MenuScanInput
        UseCase->>Kernel: RiskLevel 사용
        UseCase->>UseCase: 항목별 mock 위험도 판정
        UseCase->>ScanCore: MenuScan aggregate 생성<br/>ScannedMenuItem, BoundingBox 포함
        alt 스캔 규칙 실패
            ScanCore-->>Error: IllegalArgumentException
            Error-->>Client: 400 실패 응답
        else 스캔 생성 성공
            UseCase->>ScanCore: 저장 요청
            ScanCore->>Persistence: save(menuScan)
            Persistence->>DB: menu_scan + scanned_menu_item 저장
            DB-->>Persistence: 저장된 스캔
            Persistence-->>UseCase: MenuScan domain
            UseCase-->>Controller: MenuScanResult
            Controller-->>Client: 200 MenuScanResponse
        end
    end
```

메뉴 스캔 제출은 음식 상세 DB를 조회해서 판정하는 구조가 아니다. 클라이언트가 보낸 메뉴 항목을 기준으로 mock 판정 결과를 만들고, 그 결과를 스캔 이력으로 저장한다.

| 데이터 | 저장 위치 | 사용 시점 |
| --- | --- | --- |
| 스캔 단위 | `menu_scan` | 메뉴 스캔 제출 시 저장 |
| 스캔 항목, 위치, 위험도 스냅샷 | `scanned_menu_item` | 메뉴 스캔 제출 시 저장 |
