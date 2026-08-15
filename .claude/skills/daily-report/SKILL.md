---
name: daily-report
description: 오늘(또는 지정 날짜) 진행한 모든 Claude 세션 대화를 데일리 보고서 마크다운으로 정리해 kbap-agenthub/daily/ 에 남길 때 사용 — "데일리 보고서", "오늘 세션 정리해줘", "오늘 작업 내용 데일리로 남겨줘", "daily report" 요청 시.
---

# 데일리 보고서 (daily-report)

오늘 swm-kbap 관련 **모든 Claude 세션**(kbap·워크트리·kbap-langchain·kbap-agenthub)의 트랜스크립트를 읽어 데일리 보고서 하나로 정리하고 허브 `daily/` 에 커밋한다. 사용자가 원하는 시점에 명령해 실행한다 — 자동 훅 없음.

## 절차

1. **날짜 결정** — 기본은 오늘. 인자로 `yyyy-mm-dd` 가 오면 그 날짜.

2. **세션 트랜스크립트 수집**:
   ```bash
   DATE=<yyyy-mm-dd>
   find ~/.claude/projects -maxdepth 2 -name "*.jsonl" -path "*swm-kbap*" \
     -newermt "$DATE" ! -newermt "$DATE +1 day"
   ```
   파일 크기도 함께 본다(`ls -la`) — 수 KB 수준의 빈 세션은 건너뛴다.

3. **세션별 서브에이전트 요약** — 트랜스크립트는 세션당 수십만 토큰일 수 있으므로 **메인 컨텍스트로 직접 읽지 않는다**. 세션마다 서브에이전트(Explore 또는 general-purpose)를 병렬 dispatch 해 다음을 개조식으로 받아온다:
   - 무슨 작업을 했는지(주제·결정·산출물: 커밋/PR/문서)
   - 미완·블락 사항
   - repo 구분(프로젝트 디렉터리명에서 판별: `...-kbap` / `...-kbap-langchain` / `...-kbap-agenthub` / 워크트리)
   현재 실행 중인 세션의 트랜스크립트도 목록에 잡힌다 — 정상이며 그대로 요약한다.

4. **보고서 작성** — `/Users/simjonghan/source_code/swm-kbap/kbap-agenthub/daily/<yyyy-mm-dd> 데일리 보고서.md` (연도 하위 폴더 없이 `daily/` 직속):
   ```markdown
   # yyyy-mm-dd 데일리 보고서

   ## kbap
   ### <세션 주제 한 줄>
   - 개조식 요약...

   ## kbap-langchain
   ...
   ```
   repo 섹션은 해당 날짜에 세션이 있는 것만. 같은 날 재실행하면 기존 파일을 **덮어쓴다**(그날 전체를 다시 정리하는 것이 정의).

5. **허브 커밋 + 푸시** (main 직커밋):
   ```bash
   cd /Users/simjonghan/source_code/swm-kbap/kbap-agenthub && git add daily/ && git commit -m "daily: <yyyy-mm-dd> 데일리 보고서" && git push
   ```

## 주의

- `daily/2026/` 의 과거 훅 기록 파일들은 건드리지 않는다(append-only 유산).
- cwd 리셋 함정 — 허브 작업은 `cd ... && ...` 단일 명령으로 묶는다.
