# Phase 0 Research: 스캔 v2 — 서버 OCR 파이프라인과 유사 음식 대체 응답

## R1. 신·구 분기 수단 — X-API-Version 헤더 (동일 정책 재사용)

**Decision**: 기존 `POST /api/v1/scans` 하나에 `X-API-Version` 헤더(선택, `yyyy.mm.sprint차수` 캘린더 표기)로 분기한다. `2026.08.07` 이상이면 v2(서버 OCR·유사 폴백), 미전송·이전·파싱 불가면 종전 계약. 판정은 KB-300 이 만든 `api.core.ApiVersion` 을 그대로 재사용하고 임계값 상수는 `ScanController` 가 소유한다.

**Rationale**: 버저닝 정책(위키 `api-versioning-policy.md`)의 "같은 리소스의 동작 분기는 헤더" 기준에 정확히 해당한다. 요청 형태(items 유무)로 암묵 분기하면 구버전 앱의 items 누락 버그가 조용히 v2 로 흘러가는 문제가 KB-300 개정 1과 동일하게 재현되므로 헤더로 명시 분기한다.

**Alternatives considered**:
- *`/api/v2/scans` 경로 신설* — 컨트롤러·DTO·swagger 한 벌 복제. KB-300 에서 철회한 것과 같은 이유로 기각.
- *items 빈 배열이면 v2 로 암묵 분기* — 구버전 계약(items `@NotEmpty` 400)이 실수를 잡아주는 방어가 사라진다. 기각.

## R2. 서버 OCR — 기존 비전 seam 재사용 + 힌트 없는 프롬프트 분기 (LLM 1회)

**Decision**: 새 seam 을 만들지 않고 `MenuBoardVisionExtractor.extract(imagePath, ocrItems = emptyList())` 를 v2 경로로 쓴다. `OpenAiMenuBoardVisionExtractor` 가 **ocrItems 가 비어 있으면 힌트 없는 서버 OCR 프롬프트**(OCR 교정·matchedIdx 규칙 절 제거, 추출·정제 규칙은 동일)로 분기한다. Jira 가 서술한 2단(OCR 추출 → 이름·가격 정제)은 **단일 비전 호출로 통합**한다 — 프롬프트의 [규칙] 절이 이미 정제(표준 한국어명 `koreanName`·가격 정수화)를 수행한다.

**Rationale**: v1 이 증명했듯 현행 비전 모델은 사진→정제된 (name, koreanName, price) 를 한 호출로 낸다. 2단 분리는 지연·비용을 배로 늘리면서 품질 이득 근거가 없다(중간 원시 텍스트를 소비하는 곳도 없음). v1 요청은 items 최소 1개가 강제되므로 "빈 ocrItems = v2 경로"가 계약상 모호하지 않다. 반환 타입 `ExtractedMenu.matchedIdx` 는 nullable 이라 그대로 null 로 흐른다.

**Alternatives considered**:
- *별도 seam `MenuBoardOcrExtractor` 신설* — 인터페이스·어댑터·조립·페이크가 한 벌 늘지만 시그니처는 파라미터 하나 차이. 기각.
- *OCR LLM → 정제 LLM 2단 파이프라인(Jira 서술 그대로)* — 지연 2배·비용 2배·실패 지점 2배. 중간 산출물 수요가 생기면 그때 분리한다. 기각.
- *기존 프롬프트에 빈 OCR 목록 그대로 투입* — 프롬프트가 OCR 힌트를 전제(진실의 출처·matchedIdx 규칙)로 쓰여 있어 빈 목록이면 지시가 공허해지고 오작동 여지가 있다. 프롬프트 분기로 기각.

## R3. 벡터 검색 — `:api` 직접 구현, 영속 접근 취급 (2026-08-10 개정 2, 사용자 결정)

**Decision**: DocumentDB 벡터 검색을 **외부 시스템 seam-어댑터 대상이 아니라 영속(데이터스토어) 접근으로 분류**한다 — MySQL JPA 선례(인프라 모듈 없이 리포지토리 직접 접근)와 동일 취급. `mongodb-driver-sync` 의존과 구현을 **`:api` 모듈에 직접** 둔다:

```kotlin
// com.kbap.api.scan — 인터페이스는 테스트 대역용으로만 유지(DocumentDB 는 로컬 재현 불가)
fun interface SimilarFoodSearcher {
    fun search(embedding: FloatArray, limit: Int): List<SimilarFoodDocument>
}
// SimilarFoodDocument(foodId: Long, score: Double)  — 메타데이터(이름·설명·사진)는 응답에 쓰지 않으므로 반환 최소화(R5)
```

구현 `DocumentDbSimilarFoodSearcher`(`$search` vectorSearch 집계 1회)와 MongoClient 조립(`@ConditionalOnProperty("kbap.vector.enabled")`)도 `com.kbap.api.scan` 기능 패키지에 함께 둔다(ADR-0017 기능 응집). `common.port` 신설·인프라 모듈 변경 없음.

**Rationale**: DocumentDB 는 LLM·소셜인증 같은 "행위 위임 외부 서비스"가 아니라 우리가 소유한 **데이터스토어**다 — MySQL 이 seam 없이 직접 접근하는 것과 같은 분류(2026-08-10 사용자 결정). 소비자가 api 하나뿐이라 :common 으로 올릴 이유도 없다(배치·랭체인이 쓰게 되면 그때 승격). 인터페이스 1개는 남긴다 — DocumentDB `$search` 가 Testcontainers·로컬 MongoDB 로 재현 불가라 통합 테스트가 fake 주입을 요구하기 때문(순수 테스트 실용주의, 계약 추상화 목적 아님).

**감수하는 비용**: Redis(refresh token)는 `:infra:redis` 로 뺀 선례와 분류가 갈린다 — "데이터스토어 = 직접, 행위 위임 = seam" 기준으로 정리하고 넘어간다. api 모듈에 mongodb 드라이버 의존이 늘어난다.

**Alternatives considered**:
- *신규 `:infra:vector` 모듈* — 초안 결정. 파일 2~3개에 모듈 세리머니(settings·build·아키타입) 과다, KB-244 다이어트 방향 역행. 철회.
- *`:infra:llm` 편입* — 개정 1 결정. 신규 모듈은 없지만 batch classpath 에 드라이버가 얹히고, DocumentDB 를 "LLM 스택"으로 분류하는 것도 어색하다. **사용자 결정으로 api 직접 구현으로 대체**.
- *spring-boot-starter-data-mongodb* — MongoTemplate·자동구성·헬스체크가 딸려오지만 쓰는 건 집계 1회. 자동구성 차단 설정만 늘어난다. 기각.
- *Spring AI VectorStore 추상화* — MongoDB Atlas 용 구현은 `$vectorSearch`(Atlas 전용 문법)라 DocumentDB(`$search`)와 호환되지 않는다. 기각.

**테스트 전략(제약)**: DocumentDB 의 `$search` 벡터 문법은 로컬 MongoDB·Testcontainers 로 재현 불가(Atlas 와도 문법이 다름). 어댑터는 쿼리 조립+매핑만 하는 얇은 층으로 유지하고 dev 클러스터로 수동 검증(quickstart), 서비스·컨트롤러 로직은 fake seam 으로 검증한다(헌법 I — LLM fan-out 의 페이크 단위검증 선례).

## R4. 임베딩 호출 — KB-136 `TextEmbeddingClient` 재사용, api 프로필 활성화

**Decision**: 검색 질의 임베딩은 기존 seam `common.port.llm.TextEmbeddingClient`(Bedrock Titan V2, 256차원 — `kbap.llm.embedding.*`) 를 재사용한다. 질의 텍스트는 **정제된 표준 한국어명(`koreanName`) 단독**(KB-318 결정 — 저장은 이름+긴 설명, 검색은 이름만). api 의 dev/prod 프로필에 `kbap.llm.embedding.enabled: true` 를 켠다(local 은 미활성 유지).

**Rationale**: 차원·모델이 적재 측과 일치해야 검색이 성립한다 — 이미 256 고정으로 합의된 seam 이 있으므로 새 수단을 만들 이유가 없다. `enabled` 게이트 덕에 빈 미생성 환경에서도 부팅이 안전하다.

**Alternatives considered**: *DocumentDB 어댑터 안에서 임베딩까지 수행* — seam 이 두 외부 시스템(Bedrock+DocumentDB)에 걸쳐 비대해지고 페이크 검증 단위가 뭉개진다. 기각.

## R5. 유사 음식 응답 데이터 — foodId 로 MySQL 재조회 (벡터 메타데이터 비노출)

**Decision**: 벡터 검색은 **foodId(와 score)만** 취하고, 사용자 응답에 담는 이름·설명·사진 경로는 그 foodId 로 **MySQL 음식 마스터를 재조회**해 만든다. miss 항목당 최유사 1건, score 가 임계(`kbap.vector.similarity-threshold`, 튜닝값) 미달이면 대체 없음. 재조회 결과가 없거나(소프트 삭제 등) READY 가 아니면 대체 없음.

**Rationale**: (1) **상세 조회 정합(FR-008)** — 응답 데이터의 출처가 MySQL 이므로 foodId 상세 조회와 어긋날 수 없다. Jira 고려사항("PK 부재 시 상세 조회 연동")이 구조적으로 해소된다. (2) **언어 정책(헌법 V)** — 기존 `displayName(lang)`·설명 번역 체계를 그대로 태워 벡터 메타데이터의 언어·신선도 문제를 우회하지 않는다. (3) 벡터 문서 메타데이터(이름·설명·사진)는 적재 시점 스냅샷이라 MySQL 과 드리프트한다 — 검색용으로만 쓰고 응답 진실은 단일 출처(MySQL)로 유지한다.

**Alternatives considered**: *벡터 도큐먼트 메타데이터를 그대로 응답(Jira·KB-318 서술)* — 재조회 1회를 아끼지만 번역 정책 우회·스테일 데이터·상세 조회 불일치 리스크를 산다. 기각(도큐먼트 메타데이터는 계약상 유지하되 — contracts/vector-food-document.md — 서버 응답 경로에선 쓰지 않는다).

## R6. v2 응답 계약 — 기존 응답에 additive 확장

**Decision**: `ScanResult.ItemRiskResult`/`ScanResponse` 항목에 `similarFood`(nullable 객체: `foodId`·`name`·`koreanName`·`description`·`imageUrl`) 를 추가한다. 판정 규약: `matched=true` → 정확 매칭, `matched=false && similarFood != null` → 유사 대체(주의 표시), 둘 다 아니면 미등록. v1 경로는 `similarFood` 항상 null — 필드 추가는 하위 호환(tolerant reader)이라 v1 클라이언트에 영향 없다. `imageUrl` 은 기존 공개 베이스 URL 조합(`ImageUrls.resolve`)을 따른다.

**Rationale**: 봉투·기존 필드를 유지해 v1/v2 응답 처리 코드를 클라이언트가 공유할 수 있다. 유사 대체 구분자(FR-007)는 별도 boolean 대신 `similarFood` 의 존재 자체로 표현 — 필드 하나로 구분과 데이터를 동시에 준다.

**Alternatives considered**: *v2 전용 응답 DTO 분리* — 필드 대부분이 중복되고 헤더 분기라 응답 타입 스위칭 복잡성만 는다. 기각.

## R7. v2 부수 동작 — scan_history·카운트·조사 대기 등록 유지

**Decision**: v2 도 기존과 동일하게 miss 이름을 `createIncomplete` 로 조사 대기 등록하고, scan_history 전량 기록(가격 보존·매칭 실패 포함), 스캔 카운트 증가를 수행한다. 유사 대체 여부는 이력에 별도 저장하지 않는다(스키마 무변경 — 필요해지면 후속).

**Rationale**: 미등록 메뉴 수요 파악·홈 최근 스캔·랭킹이 이 부수 동작에 의존한다. v2 라고 뺄 이유가 없고 스키마를 건드리지 않는 선에서 전부 재사용된다.

## R8. 실패 처리 — 부분 성공 우선

**Decision**: 비전 추출 실패는 기존과 동일하게 `SCAN-002`(503) 전체 실패. 임베딩·벡터 검색 실패는 **해당 miss 항목들의 유사 대체만 생략**하고 스캔은 성공 응답한다(warn 로그). 임베딩·검색 빈이 미구성(로컬 등)이면 유사 폴백 자체가 no-op.

**Rationale**: 유사 대체는 부가 정보다 — 부가 정보의 외부 장애가 핵심 기능(스캔)을 죽이면 안 된다(FR-009, spec Edge Cases). 빈 부재 no-op 은 `@ConditionalOnProperty` 게이트와 짝을 이뤄 환경별 안전 부팅을 보장한다.
