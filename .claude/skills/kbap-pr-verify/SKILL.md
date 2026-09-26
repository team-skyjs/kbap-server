---
name: kbap-pr-verify
description: >
  kbap-server PR을 읽는 게 아니라 **띄워서** 검증한다. "PR 실행 검증", "#N 띄워서 확인",
  "검토 게이트 서버 PR" 요청 시 발동 — CI 결과 확인 → 워크트리 체크아웃 → 로컬 스택 기동
  → PR 본문 계약을 curl로 실측 → 표로 보고. 코드 리뷰(kbap-code-review)와 짝으로 쓴다.
---

# PR 실행 검증

"테스트 1490건 통과"는 PR 작성자의 주장이다. 이 스킬은 주장을 **실행 결과**로 바꾼다.
검증 범위 = PR 본문의 **"변경 사항" 표·"검증" 절에 적힌 계약**. 거기 없는 건 검증하지 않고
"본문에 계약 없음"으로 보고한다(본문 보강 요청 사유).

## 절차

1. **CI가 테스트 정본** — `gh pr checks <N>`. `Build with Gradle`(= `gradlew build`, 테스트 포함)
   pass가 아니면 여기서 중단·보고. 로컬 전체 테스트 재실행은 CI가 없을 때만.
2. **워크트리** — 메인 체크아웃을 건드리지 않는다:
   `git fetch origin <head> && git worktree add <scratchpad>/wt-<N> origin/<head>`
   `.env`를 워크트리로 복사(Spring이 `optional:file:.env`로 직접 읽는다 — `source` 불필요).
3. **로컬 스택** — `docker compose up -d mysql redis`(앱 컨테이너는 안 띄움, bootRun이 대신).
   `JAVA_HOME=$(/usr/libexec/java_home -v 21)`(기본 java는 17 — CI와 맞춘다) ·
   `SPRING_PROFILES_ACTIVE=local` · **`STORAGE_ENABLED`는 건드리지 않는다**(기본 true —
   false면 `FoodImageBatchCollectService`가 `StorageObjectStore` 빈을 못 찾아 부팅 실패.
   `StorageConfig.kt` 주석 "local엔 빈 없음"은 낡았다. S3 클라이언트는 호출 전까지 네트워크 안 씀) ·
   `.env`에 `JWT_SECRET`·`OPENAI_API_KEY` 필요(ScanService가 키 없으면 부팅 거부 —
   LLM 엔드포인트를 안 칠 거면 `export OPENAI_API_KEY=sk-local-placeholder`, 보고에 명시).
   실행: `nohup ./gradlew :api:bootRun --console=plain > ../boot-<N>.log 2>&1 & disown` 후
   로그를 폴링해 `Started KbapApiApplication` 확인(첫 빌드 3~4분, 이후 10초).
   ⚠️ bootRun 출력을 `| head`로 자르지 말 것 — SIGPIPE로 서버가 죽는다(실측).
   Flyway가 로컬 DB에 마이그레이션을 적용한다(PR에 마이그레이션이 있으면 여기서 실제로 돈다 —
   실패 = Blocker).
4. **픽스처** — 로컬 DB는 비어 있다. 계약 검증에 필요한 최소 행만 `docker exec kbap-mysql
   mysql -uroot -proot kbap -e "INSERT …"`로 넣는다(스키마는 `api/src/main/resources/db/migration`
   init + 후속 ALTER 참조). 회원이 필요하면 `member` 1행 + `python3 k6/mint-token.py <id>`
   (`JWT_SECRET` env)로 토큰. 관리자 엔드포인트는 관리자 자격 별도(admin 스킴 확인).
   zsh에서 `M="docker exec …"; $M "…"`처럼 변수를 명령으로 쓰면 깨진다 — 셸 함수로 감쌀 것.
5. **계약 실측** — PR 본문 표의 각 행을 `curl -s -H 'X-API-Version: 1.0'` 로 친다.
   음식 계열 GET은 `lang=` 필수(없으면 COMMON-002). 요청 바디는 `/v3/api-docs`의
   `components.schemas.<XxxRequest>.required`로 최소 필드를 확인해 만든다.
   에러 분기 검증엔 **정상 케이스 1건도 같이** 친다(픽스처·라우트가 살아 있다는 대조군).
   대조 항목: HTTP 상태 · `success` · 에러 `code` · payload 필드. **swagger도 본다**:
   `http://localhost:8080/v3/api-docs`에서 PR이 추가한 에러코드/필드가 문서에 실렸는지.
6. **보고** — 표 `엔드포인트 | 기대(PR 본문) | 실측 | 판정`. 불일치 = 반려 근거.
   그다음 `kbap-code-review` 스킬로 정적 리뷰. 둘 다 통과해야 검토 게이트 통과.
7. **정리** — bootRun 종료, `git worktree remove <path>`. compose는 그대로 둬도 됨(볼륨 유지).

## 원칙
- 검증 대상은 PR 본문이 **약속한 것**. 본문이 계약을 안 적었으면 그게 첫 지적이다.
- dev/prod DB·Swagger에는 쓰지 않는다. 실측은 로컬 스택에서만.
- 로컬에서 재현 못 하는 것(SQS·S3·Firebase 실연동)은 "로컬 검증 불가 — 통합 테스트/CI 근거"로 표기.
