# 성능 결과 보관 안내

Dashboard와 CLI runner는 campaign 결과를 다음 구조로 만든다.

```text
artifacts/performance/<CAMPAIGN_ID>/
├── campaign.json
└── <TARGET>/
    ├── report.html
    ├── summary.json
    ├── manifest.json
    ├── task-<TASK_ID_1>.jfr
    └── task-<TASK_ID_2>.jfr
```

## 필수 결과 묶음

리뷰 가능한 target 결과는 HTML report, `summary.json`, `manifest.json`, 원본 task JFR 두 개가 모두 있어야 한다. Dashboard의 `부분 수집` 표시는 누락을 숨기지 않기 위한 실패 가시성이다. ZIP에도 durable `campaign.json`과 등록된 allowlist artifact만 포함된다.

JMC 또는 Claude 분석에는 반드시 두 task 원본 JFR과 같은 target의 manifest를 함께 전달한다. 한 task만으로 전체 service를 대표하지 않는다. `summary.json`과 HTML report를 추가하면 부하 결과와 JVM event를 연결하기 쉽다.

## 결과 기록 권장 항목

- `CAMPAIGN_ID`, 실행자, UTC 및 KST 시간 범위
- git commit SHA, 배포 task definition revision, task ID 두 개
- target key, profile, rate 또는 VU, duration 또는 iterations
- p95, p99, 실패율, dropped iterations, threshold 결과
- 같은 target 직전 campaign 대비 절대 변화와 증감률
- Tomcat busy, Hikari pending, CPU, GC, allocation, lock 관찰
- fixture 및 cost 승인 여부, 취소 또는 실패 사유
- artifact 누락과 재수집 여부

## 보안과 공유

JFR은 내부 class, method, thread, stack, endpoint와 환경 문맥을 포함할 수 있다. 외부 업로드 전에 조직의 민감정보 취급 범위를 확인한다. `ACCESS_TOKEN`, Authorization header, fixture secret, AWS credential, presigned URL은 결과 디렉터리와 문서에 기록하지 않는다.

이 디렉터리의 문서는 보관 형식을 설명할 뿐 실제 JFR과 성능 artifact를 Git에 추가하라는 뜻이 아니다. 실제 결과는 ignore된 `artifacts/performance` 또는 승인된 내부 저장소에 둔다. 공개 community 공유와 자동 외부 업로드는 이 workflow 범위에서 제외한다.

전체 실행과 해석 순서는 [k6 + JFR 성능 캠페인 런북](../k6-jfr-runbook.md)을 따른다.
