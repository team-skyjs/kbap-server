# KB-794 기능 구현 기록

기준 develop173426ec3. 2026-10-16 센터가 #402(dev)/#403(prod) 선행 migration 및 API/batch 기동 게이트 해제를 전달했다. prod 직접 읽기: checksum1439660255/success1/1·3·0, CodeDeploy d-HFI8QJTDL Succeeded13:04:22KST, API14단일2HEALTHY/batch14COMPLETED1HEALTHY, 실제 b740af image 일치, workflow38845496153/38848727705success. 원시 조회 /private/tmp/kb403-readback. dev는 센터 검증 근거이며 이번 세션이 AWS 전체를 재조회한 것은 아니다.

#408 승인 준비 계약 기준. 스캔 #409와 독립, 기능 push/Draft/검증만 승인됨. 머지/prod배포/실제 콘텐츠 게시 금지. 기존 V2026.10.15.22.16.07 SQL 동일내용 보존, 새 SQL 없음.

- [x] 게시 검증 Red→Green: 3×10/10lang/권리NONE/URL/검수 hash
- [x] 모델·CAS·초안/게시격리·최초latch·공개조회
- [x] ADMIN 환경/권한·자산 예약/조건부 복사/확정
- [x] HTTP·경합·언어·업로드회귀·Swagger
- [x] 전체 build/실제 로컬 HTTP/정적 자체 검토
- [ ] 정확한 HEAD의 CI·Draft 인계

검수 JSON 구체화: editorialReview.contentHash는 editorialReview 자신을 제외한 문서의 canonical SHA256에 결합한다. 관리자 상세/validate 응답의 requiredContentHash를 확인해 현재 내용의 검수 기록으로 제출한다. 기존 hash는 내용/사진/URL/증빙 변경 시 더 이상 일치하지 않아 게시가 차단된다. 사진 checkedAssetId와 링크 review.url도 각각 실제 assetId/externalUrl에 결합한다. 날짜는 ISO local date로 미래값을 거절한다. 이는 별도 결재 역할이 아닌 같은 ADMIN의 검수 기록이다.

S3 새prefix IAM/CORS/CDN 실제 연동은 로컬 fake 검증과 구별해 미검증 표시한다. 정리 잡은 이번 기능에서 활성화하지 않으며 전용prefix 자산은 기존 정리와 분리한다. 참조 안전 정리/retention은 dry-run 후 별도 운영 단계다.

게시 잠금 구체화: 기본 REPEATABLE READ에서 관리자 조회가 만든 과거 snapshot으로 다른 모음의 상태를 판단하지 않도록 root→고정3 collection ID순 현재잠금읽기→asset ID순을 사용한다. 고정3행이라 범위를 제한하며 초안저장은 해당collection→asset만 유지한다. 동시 최초게시 테스트는 각 외부TX가 먼저 같은 미게시 snapshot을 만든 뒤 시작해 이를 검증한다. 격리수준 변경 없음.
