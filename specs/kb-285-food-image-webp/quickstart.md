# Quickstart / 런북: 음식 사진 WebP 변환본 서빙

## 0. AWS 계정 확인 — 팀 계정으로 전환 (S3·DB 작업 전 필수)

**로컬 `aws` CLI 의 `default` 프로필은 팀 계정이 아니다.** 확인 없이 버킷 작업을 실행하면 엉뚱한 계정을 보거나 AccessDenied 로 실패한다.

### 0-1. 지금 누구인지 확인

```bash
aws sts get-caller-identity
aws sts get-caller-identity --profile kbap-prod-deployer
```

**팀 계정 ID: `118178010621`** (리전 `ap-northeast-2`). `Account` 가 이 값이 아니면 그 셸로는 아무 작업도 하지 않는다. `default` 프로필은 다른(개인) 계정이며 서비스 버킷이 존재하지 않는다 — `aws s3 ls` 가 빈 목록이면 잘못된 계정이다.

### 0-2. CLI 로 작업할 때

프로필을 **매 명령에 명시**한다. 셸 export 는 다른 탭·다음 세션에 안 따라와서 사고가 난다.

```bash
aws <명령> --profile kbap-prod-deployer --region ap-northeast-2
```

한 세션 내내 쓸 거면 export 하되, 직후 반드시 계정을 재확인한다.

```bash
export AWS_PROFILE=kbap-prod-deployer AWS_REGION=ap-northeast-2
aws sts get-caller-identity   # Account 가 118178010621 인지 재확인
```

**주의**: `kbap-prod-deployer` 는 배포용 IAM 사용자라 권한이 매우 좁다. 실측 결과 `kbap-assets-kr` 에 대해 `s3:ListBucket`·`s3:GetObject` 모두 거부되고 자기 IAM 정책 조회도 안 된다. 이미지 자산 작업에는 별도 사용자 **`kbap-cli`**(로컬 프로필 `kbap-s3`) 를 쓴다 — `images/*` 읽기 + `images/webp/*` 쓰기만 허용되며, **원본을 덮어쓰거나 삭제하는 것이 권한상 불가능**하다.

프로필이 아예 없는 새 머신이라면:

```bash
aws configure --profile kbap-prod-deployer   # region: ap-northeast-2, output: json
```

### 0-3. 콘솔로 작업할 때 (Lambda·IAM·알람은 이 경로 권장)

1. 기존 세션을 **로그아웃**한다(개인 계정이 남아 있으면 그대로 이어진다).
2. 팀 계정으로 로그인한다.
3. 우측 상단 계정 번호가 **`118178010621`** 인지 확인한다.
4. 리전을 **서울 `ap-northeast-2`** 로 맞춘다.
5. S3 에서 대상 버킷 `kbap-assets-kr`(=`STORAGE_BUCKET` — 저장소에 하드코딩돼 있지 않다)의 `images/webp/food/` 아래에 자산이 보이는지 확인한다. 안 보이면 계정·리전·버킷 중 하나가 틀린 것이다.

### 0-4. §3(백필 SQL)의 DB 접속도 같은 원칙

운영 DB 접속 정보는 팀 인프라 소속이다. 로컬·개인 환경의 기본 접속으로 UPDATE 를 실행하지 않는다. 실행 전 `SELECT DATABASE(), @@hostname;` 로 대상 DB 를 확인한다.

---

## 1. 코드 변경 확인 (로컬)

```bash
./gradlew build
```

기대: 요청 body 에 `output_format=webp`·`output_compression=80` 이 실리고(`OpenAiFoodImageBatchClientTest`), 회수 시 `images/webp/food/{sha12}_{uuid16}.webp` 키로 저장·기록된다(`FoodImageBatchCollectServiceTest`).

## 2. 배포 전 필수 검증 — 모델이 output_format 을 받는가

`gpt-image-2` 가 Batch API 요청 body 의 `output_format`·`output_compression` 을 수용하는지 **단건 호출로 먼저 확인**한다. 미지원이면 배치 전량이 400 으로 실패한다.

```bash
curl -s https://api.openai.com/v1/images/generations \
  -H "Authorization: Bearer $OPENAI_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"gpt-image-2","prompt":"김치찌개 사진","size":"1024x1024","quality":"medium","output_format":"webp","output_compression":80}' \
  | head -c 300
```

- 200 + `b64_json` → 지원. 그대로 배포한다.
- 400 `Unknown parameter` → 미지원. 이 브랜치를 배포하지 말고 research.md R1~R5(PNG + 변환 Lambda) 구조로 복귀한다.

응답 base64 를 디코드해 `file` 로 포맷을 확인하면 확실하다.

## 3. 기존 적재분 백필 (운영 DB, Flyway 아님)

먼저 대상 수를 확인한다.

```sql
SELECT COUNT(*) FROM food WHERE image_ref LIKE 'images/food/%.png';
```

**변환본이 전부 존재하는지 먼저 대조한다.** 하나라도 빠지면 그 음식만 빈 이미지가 된다.

```bash
export AWS_PROFILE=kbap-s3 AWS_REGION=ap-northeast-2
aws s3 ls s3://kbap-assets-kr/images/webp/food/ --recursive | grep -c '\.webp$'
```

이 값이 위 SQL 건수 이상이어야 한다. 미변환분이 남았으면 로컬 변환 루프(`cwebp -q 80 -m 6`)를 먼저 마저 돌린다.

```sql
UPDATE food
SET image_ref = REPLACE(REPLACE(image_ref, 'images/food/', 'images/webp/food/'), '.png', '.webp')
WHERE image_ref LIKE 'images/food/%.png';
```

- 멱등: 갱신된 행은 `LIKE 'images/food/%.png'` 에 다시 걸리지 않는다.
- 절대 URL·NULL·관리자 수동 입력값은 조건에 걸리지 않아 그대로 남는다.

검증:

```sql
SELECT COUNT(*) FROM food WHERE image_ref LIKE 'images/food/%.png';   -- 0
SELECT COUNT(*) FROM food WHERE image_ref LIKE 'images/webp/food/%.webp';
```

## 4. dev 검증

1. 관리자 화면에서 이미지 배치를 제출하고 회수 스케줄(또는 수동 트리거)을 태운다.
2. S3 `images/webp/food/` 에 새 `.webp` 객체가 생겼는지, 크기가 200KB 안팎인지 확인한다(PNG 였다면 2MB 대).
3. 해당 음식의 상세 API 응답 `imageUrl` 이 `.webp` 로 끝나는지 확인한다.
4. 앱에서 목록·상세 이미지가 정상 렌더링되고 화질 열화가 없는지 본다.

## 5. 전환 후 정리 (§2 검증 통과 시)

PNG 를 더 이상 만들지 않으므로 변환 파이프라인 일체를 폐기한다.

- Lambda `convert-food-image-png-to-webp` + Pillow 레이어 + 실행 롤
- S3 이벤트 알림(트리거) — 버킷 속성 → 이벤트 알림에서 제거
- 임시 IAM 사용자 `kbap-cli` 액세스 키

기존 `images/food/*.png` 620장은 남겨둔다 — 지워도 얻는 게 없고(스토리지 비용 무시 가능) 되돌릴 여지만 없앤다.

## 롤백

코드 롤백(이전 커밋)이면 다시 PNG 로 받는다. 기존 PNG 원본이 그대로 있어 `image_ref` 도 역방향 UPDATE 로 되돌릴 수 있다(전환 이후 생성분은 webp 만 존재하므로 대상에서 빼야 한다).

```sql
UPDATE food
SET image_ref = REPLACE(REPLACE(image_ref, 'images/webp/food/', 'images/food/'), '.webp', '.png')
WHERE image_ref LIKE 'images/webp/food/%.webp';
```
