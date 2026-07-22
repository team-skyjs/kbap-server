# Quickstart: 배치 콘텐츠 LLM 클라이언트

**Plan**: [plan.md](plan.md)

## 빌드·테스트

```bash
./gradlew build                    # 전체(테스트 포함)
./gradlew :infra:llm:test          # 구현체 단위 테스트만
./gradlew :infra:storage:test      # StorageObjectStore.put 테스트
./gradlew :app:batch:test          # 배치 부팅(조립) 검증
```

- 단위 테스트는 외부 호출 없이 동작한다 — `LlmModelCaller`·`StorageObjectStore` 페이크 사용.
- ArchUnit 제외 실행: `-Dkotest.tags="!arch"`.

## 로컬에서 실제 LLM 호출 (선택)

배치 프로필에 키를 주입해야 클라이언트 빈이 생성된다:

```yaml
kbap:
  llm:
    openai: { enabled: true, api-key: ..., model: ... }    # 번역·설명 + fan-out 1/3
    upstage: { enabled: true, api-key: ... }               # fan-out 2/3
    gemini: { enabled: true, api-key: ... }                # fan-out 3/3
    image: { enabled: true, api-key: ..., model: ... }     # 사진 생성
```

이미지 업로드는 S3 구성(`BatchStorageConfig` 의 region·bucket 프로퍼티)이 함께 필요하다. 키/플래그가 없으면 해당 빈만 빠진 채 부팅된다(안전).

```bash
./gradlew :app:batch:bootRun       # foodContentJob 실행 — INCOMPLETE 음식 처리
```

## 검증 포인트

- 계약 위반 응답(언어 누락·256자 설명·범위 밖 percent·후보 밖 코드)이 예외로 전파되는지 — 각 BehaviorSpec 의 실패 시나리오.
- 기피성분: 유효 모델 응답 2개 미만이면 예외, 2~3개면 평균 종합.
- 사진: put 성공 전 키 반환 없음, 같은 키 재호출 시 덮어쓰기.
