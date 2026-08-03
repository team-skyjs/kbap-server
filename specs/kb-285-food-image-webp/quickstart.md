# Quickstart / 런북: 음식 사진 WebP 변환본 서빙

## 0. AWS 계정 확인 — 팀 계정으로 전환 (§2·§3 작업 전 필수)

**로컬 `aws` CLI 의 `default` 프로필은 팀 계정이 아니다.** 확인 없이 §2(Lambda·IAM·알람)를 실행하면 엉뚱한 계정에 리소스를 만들거나 AccessDenied 로 실패한다.

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

**주의**: `kbap-prod-deployer` 는 배포용 IAM 사용자라 권한이 좁다. 실측 결과 `s3:ListAllMyBuckets` 는 거부되고(버킷 이름을 알고 접근해야 한다), Lambda·IAM 은 조회만 확인됐다. **Lambda 함수 생성·실행 롤 생성 권한은 없을 가능성이 높다** — AccessDenied 가 나면 권한을 넓히려 하지 말고 0-3 의 콘솔 경로로 간다.

프로필이 아예 없는 새 머신이라면:

```bash
aws configure --profile kbap-prod-deployer   # region: ap-northeast-2, output: json
```

### 0-3. 콘솔로 작업할 때 (Lambda·IAM·알람은 이 경로 권장)

1. 기존 세션을 **로그아웃**한다(개인 계정이 남아 있으면 그대로 이어진다).
2. 팀 계정으로 로그인한다.
3. 우측 상단 계정 번호가 **`118178010621`** 인지 확인한다.
4. 리전을 **서울 `ap-northeast-2`** 로 맞춘다.
5. S3 에서 대상 버킷(`STORAGE_BUCKET` 환경변수 값 — 저장소에 하드코딩돼 있지 않다)의 `images/food/` 아래에 PNG 원본이 실제로 보이는지 확인한다. 안 보이면 계정·리전·버킷 중 하나가 틀린 것이다.

### 0-4. §3(백필 SQL)의 DB 접속도 같은 원칙

운영 DB 접속 정보는 팀 인프라 소속이다. 로컬·개인 환경의 기본 접속으로 UPDATE 를 실행하지 않는다. 실행 전 `SELECT DATABASE(), @@hostname;` 로 대상 DB 를 확인한다.

---

## 1. 코드 변경 확인 (로컬)

```bash
./gradlew :api:test --tests "com.kbap.api.food.FoodImageBatchCollectServiceTest"
```

기대: 회수 후 `food.image_ref` = `images/webp/food/….webp`, `fakeStorage` put 키 = `images/food/….png`, `image_batch_item.file_name` = png 키.

## 2. 인프라 설정 (저장소 밖 — AWS 콘솔/CLI)

변환 Lambda 는 이 repo 에 코드가 없다. 아래 요건만 만족하면 백엔드와 계약이 맞는다.

| 항목 | 값 |
|------|-----|
| 트리거 | 이미지 버킷 `s3:ObjectCreated:*`, prefix `images/food/`, suffix `.png` |
| 출력 키 | `images/webp/food/{원본과 동일한 파일명}.webp` |
| 변환 | 동일 해상도(리사이즈 금지), WebP 인코딩 |
| Content-Type | `image/webp` |
| IAM | 원본 `s3:GetObject` on `images/food/*`, 변환본 `s3:PutObject` on `images/webp/food/*` — 그 외 권한 없음 |
| 실패 인지 | Lambda DLQ 또는 `Errors` 지표 CloudWatch 알람 |

주의: 출력 prefix(`images/webp/food/`)가 트리거 prefix(`images/food/`)와 겹치지 않으므로 재귀 호출은 발생하지 않는다.

## 3. 기존 적재분 백필 (운영 DB, Flyway 아님)

먼저 대상 수를 확인한다.

```sql
SELECT COUNT(*) FROM food WHERE image_ref LIKE 'images/food/%.png';
```

변환본이 실제로 존재하는지 표본 몇 건을 S3 에서 확인한 뒤 실행한다(Lambda 배포 이전 적재분은 변환본이 없으므로, 필요하면 원본을 재저장하거나 일괄 변환을 먼저 돌린다).

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
2. S3 에 `images/food/….png` 원본과 `images/webp/food/….webp` 변환본이 둘 다 생겼는지 확인한다.
3. 해당 음식의 상세 API 응답 `imageUrl` 이 `.webp` 로 끝나는지 확인한다.
4. 앱에서 목록·상세 이미지가 정상 렌더링되고, 네트워크 탭에서 장당 전송량이 기존 대비 크게 줄었는지 본다.

## 롤백

코드 롤백(이전 커밋)만으로 신규 기록이 PNG 경로로 돌아간다. 원본 PNG 는 계속 보존되므로, 이미 갱신된 `image_ref` 는 역방향 UPDATE 로 되돌릴 수 있다.

```sql
UPDATE food
SET image_ref = REPLACE(REPLACE(image_ref, 'images/webp/food/', 'images/food/'), '.webp', '.png')
WHERE image_ref LIKE 'images/webp/food/%.webp';
```
