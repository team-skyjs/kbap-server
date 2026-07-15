# Quickstart: KB-138 메뉴판 vision 실험 실행

## 0. 전제

- `OPENAI_API_KEY` 보유 (기존 `.env.example` 의 키와 동일 계정이면 됨)
- 메뉴판 사진 10~20장 — 인쇄/손글씨·조명·각도·해상도·영문 병기 조건이 섞이게

## 1. 샘플 준비 (수작업)

1. 사진을 외부 접근 가능한 곳에 올린다 — **S3 presigned URL 권장**(실환경과 동일 흐름). TTL 은 실험 세션보다 길게(예: 24h). GitHub raw 등 공개 URL 도 무방.
2. `specs/kb-138-menu-price-mapping/experiment/samples.json` 을 작성한다 — 샘플별 `id`·`imageUrl`·`conditions`·수기 정답 `label`. 형식은 [contracts/experiment-files.md](contracts/experiment-files.md) §1.

## 2. 하네스 실행

```bash
OPENAI_API_KEY=sk-... ./gradlew :infra:llm:test \
  --tests "com.kbap.infra.llm.experiment.MenuBoardVisionExperimentTest" \
  -Dllm.vision.experiment.enabled=true
```

- 기본 모델 `gpt-4o-mini`. 상위 모델 비교 실행: `-Dllm.vision.experiment.model=gpt-4o`
- 결과는 `experiment/results.json` 에 기록된다(summary + 샘플별 매칭·누락·오검출·지연·토큰·비용, 실패 샘플은 `error` 원인 포함).
- 프로퍼티 없이 돌리면(일반 `./gradlew test`) 스펙 전체가 skip 된다 — CI 무해.

## 3. 순수 로직 테스트만 (실키 불필요)

```bash
./gradlew :infra:llm:test --tests "com.kbap.infra.llm.experiment.MenuPriceParserTest" \
  --tests "com.kbap.infra.llm.experiment.ExperimentMetricsTest"
```

## 4. 리포트 작성 (수작업)

`results.json` 을 보고 `experiment/report.md` 를 채운다 — 지표 요약, 오류 사례 분석(정규화 불일치 쌍의 오타 분류 포함), **현행 방식(클라이언트 OCR + Upstage 텍스트 정제) 비교표**, 채택/미채택 결론, 후속 이슈 목록. 템플릿 섹션은 [contracts/experiment-files.md](contracts/experiment-files.md) §4.

## 트러블슈팅

| 증상 | 원인/조치 |
|------|----------|
| 샘플 `error: 4xx image fetch` | presigned URL 만료 또는 비공개 — URL 재발급 후 재실행 |
| 파싱 실패 다수 | 프롬프트 준수 불안정 — research.md R4 의 2차 시도(`response_format: json_schema`) 적용 |
| 스펙이 skip 됨 | `-Dllm.vision.experiment.enabled=true` 누락 |
