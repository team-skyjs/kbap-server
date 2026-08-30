# Quickstart: k6 스캔 부하 테스트 런북

전제: dev 에 모니터링(KB-379~381) 동작 중, 로컬에 `k6`(`brew install k6`)·`python3`.

## 1. 준비

```bash
# (a) 테스트 계정 — 기존 dev 로그인 계정이 있으면 그 id 에 무제한만 켠다
#     UPDATE member SET scan_unlocked = true WHERE id = <id>;
#   없으면 더미 1건 (JSON 컬럼은 JSON_ARRAY() 필수):
#     INSERT INTO member (provider, provider_uid, email, nickname, spiciness_preference,
#       avoidance_substance_codes, diet_categories, member_status, onboarding_completed,
#       scan_count, scan_unlocked, review_count, unique_reviewed_food_count, status, created_at, updated_at)
#     VALUES ('GOOGLE','loadtest-k6','loadtest@example.com','k6-loadtest','SKIP',
#       JSON_ARRAY(), JSON_ARRAY(),'ACTIVE',true,0,true,0,0,'ACTIVE',NOW(),NOW());
#     SELECT id FROM member WHERE provider_uid='loadtest-k6';

# (b) 토큰 — dev 시크릿은 셸에만(채팅/커밋 금지)
export JWT_SECRET='<SSM /kbap/dev/JWT_SECRET>'
TOKEN=$(python3 k6/mint-token.py <memberId> 2)     # 2시간

# (c) 검증 — 티켓 발급이 200 이면 계정·토큰 OK
curl -s -X POST https://dev.kbap.site/api/scans/tickets \
  -H "Authorization: Bearer $TOKEN" -H "X-API-Version: 1.0" | head -c 200

# (d) 이미지 픽스처
cp <실제 메뉴판 사진>.jpg k6/menu-board.jpg
```

## 1-b. 시드 (1회 — 업로드 체인으로 objectKey 확보)

```bash
cd k6
# presign → S3 PUT → complete 를 1회 수행하고 objectKey 를 출력한다
# k6 의 console.log 는 stderr 로 나가므로 2>&1 로 합쳐 뽑는다
export SCAN_IMAGE_PATH=$(k6 run -e ACCESS_TOKEN=$TOKEN -e IMG=./menu-board.jpg seed-image.js 2>&1 \
  | grep -oE 'SCAN_IMAGE_PATH=[^" ]+' | head -1 | cut -d= -f2)
echo "seeded: $SCAN_IMAGE_PATH"     # 예: scan/1234/20260830-abc.jpg
```

## 2. 실행 (계단 — 각 회차 사이 수 분 회복 대기)

```bash
cd k6   # SCAN_IMAGE_PATH 는 1-b 에서 export 됨
k6 run -e ACCESS_TOKEN=$TOKEN -e SCAN_IMAGE_PATH=$SCAN_IMAGE_PATH -e VUS=5   scan-burst.js   # 리허설 ≈50원 — 티켓+스캔 200 확인
k6 run -e ACCESS_TOKEN=$TOKEN -e SCAN_IMAGE_PATH=$SCAN_IMAGE_PATH -e VUS=50  scan-burst.js   # ≈500원
k6 run -e ACCESS_TOKEN=$TOKEN -e SCAN_IMAGE_PATH=$SCAN_IMAGE_PATH -e VUS=145 scan-burst.js   # ≈1450원 (누적 200건)
```

요약 저장: `--summary-export=run-50.json`. (선택) 서버 메트릭과 겹쳐 보려면 `-o experimental-prometheus-rw`.

## 3. 관찰 (Grafana env="dev", 실행 창 동안)

- 스캔 p95 vs 타 API p95 — `http_server_requests_seconds`
- HikariCP active/pending, JVM heap/GC, 호스트 CPU/mem
- CloudWatch: ALB TargetResponseTime·5xx
- 429 나오면 OpenAI 한도 → 앱 결함과 분리 기록

## 4. 기록·정리

- 회차별 수치·병목 판정을 지식 위키에 기록
- `k6/seed-image.js`·`scan-burst.js`·`mint-token.py` 커밋(`menu-board.jpg` 는 .gitignore, objectKey 는 커밋 안 함)
- 더미 계정 만들었으면: `UPDATE member SET status='DELETED' WHERE provider_uid='loadtest-k6';`
