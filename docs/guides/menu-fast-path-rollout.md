# 메뉴판 바로가기(Jev) 운영 단계적 켜기 — KB-1015

가공식품 비전 스캔의 첫 사진을 Jev(TypeSafe 이미지 API)로 먼저 판별해, MENU 확률 0.98 이상이면 기존 판독을 건너뛴다. 실패·1초 초과·확신 부족이면 기존 판독 1회로 간다. 설정은 `api/src/main/resources/application.yml` 의 `kbap.processed-food.vision.menu-fast-path` 한 곳에 있고 모든 환경의 기본값은 OFF다.

## 환경변수·시크릿

| 이름 | 종류 | 기본 | 뜻 |
|---|---|---|---|
| `JEV_API_KEY` | secret(SSM SecureString `/kbap/<env>/JEV_API_KEY`) | 없음 | 비면 분류기 빈이 없어 다른 값과 무관하게 Jev 호출 0 |
| `PROCESSED_FOOD_MENU_FAST_PATH_ENABLED` | env | `false` | 전체 스위치 |
| `PROCESSED_FOOD_MENU_FAST_PATH_MEMBER_IDS` | env | 빈 값 | 항상 대상인 회원 id(쉼표 구분) |
| `PROCESSED_FOOD_MENU_FAST_PATH_ROLLOUT_PERCENT` | env | `0` | 0~100. memberId 해시 버킷(0~99) < 값이면 대상. 범위 밖이면 api 가 기동하지 않는다 |

대상 = 명단 OR 버킷 < 비율. 같은 회원은 늘 같은 버킷이라 비율을 올리면 기존 대상은 유지되고 새 회원만 더해진다. 요청 신호(`menuFastPathEligible=true`, 앱이 첫 사진에만 보냄)가 없으면 대상이어도 부르지 않는다.

## 순서 (운영)

태스크 정의 secret 은 컨테이너 시작 때 SSM 에서 읽는다. **SSM 파라미터 없이 secret 매핑만 있으면 태스크가 뜨지 못한다** — 반드시 1 → 2 순서.

1. CloudShell 에서 SSM 파라미터를 만든다(값은 화면·기록에 남기지 않는다).
   ```bash
   read -s -p "JEV API key: " JEV_KEY; echo
   aws ssm put-parameter --region ap-northeast-2 --name /kbap/prod/JEV_API_KEY --type SecureString --value "$JEV_KEY" --overwrite
   unset JEV_KEY
   ```
2. 운영 api 태스크 정의를 복제해 새 리비전을 만든다.
   - `secrets` 에 `{"name": "JEV_API_KEY", "valueFrom": "<1의 파라미터 ARN>"}` 추가.
   - `environment` 에 `PROCESSED_FOOD_MENU_FAST_PATH_ENABLED=true`, `PROCESSED_FOOD_MENU_FAST_PATH_MEMBER_IDS=<팀 테스트 계정 id>` 추가(비율은 넣지 않거나 `0`).
   - 실행 역할이 이 파라미터를 읽을 수 있는지(ssm:GetParameters·kms:Decrypt) 확인.
3. 서비스를 새 리비전으로 갱신하고 롤링이 끝날 때까지 본다. 이후 배포는 살아 있는 태스크 정의를 복제해 이미지만 바꾸므로 이 매핑이 유지된다. 단 terraform 으로 운영 태스크 정의를 다시 적용하면 tfvars 에 없는 매핑은 사라진다.
4. 명단 회원으로 확인한 뒤 `PROCESSED_FOOD_MENU_FAST_PATH_ROLLOUT_PERCENT` 를 5 → 25 → 100 처럼 올린다(값을 바꿀 때마다 2~3 반복).

끄기: `PROCESSED_FOOD_MENU_FAST_PATH_ENABLED=false`(또는 비율 0·명단 비움) 리비전으로 갱신. secret 매핑은 남겨도 된다.

## 보는 곳

- 메트릭 `processed_food.vision.menu_fast_path{result,reason}` — `menu` 비율과 폴백 사유(`timeout`·`busy`·`not_menu`·`low_probability`·`rate_limited`·`server_error` 등).
- 지연 `processed_food.vision.menu_fast_path.latency{result}`.
- 스캔 요청 문서 `visionSource` — `MENU_FAST_PATH` | `READER`.
