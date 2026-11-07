# KB-998: 사진 종류 무힌트 기준선 (v1)

운영 코드·DB 없이 실제 `LlmConfiguration.menuBoardVisionExtractor(...).read(path)`를 사용한다. 정답과 두 기기 예측은 로컬 채점에만 쓴다. 승인된 사진 묶음의 해시는 고정돼 있다. API 키는 live에서만 실행자 환경 변수로 받는다.

1. Python 3 표준 라이브러리로 `python3 tools/scan-hint/pack.py <xs-s22-1000-v1> <출력폴더>`. 로컬 tar(사진과 체크섬)·paths.csv를 만든다. 사진이나 키는 Git에 넣지 않는다.
2. `tools/scan-hint/run <출력폴더>/paths.csv` — manifest 검증 및 가정에 따른 비용 산출, 네트워크 호출 없음.
3. 실행자가 tar의 이미지 1,000장만 `s3://kbap-assets-kr/eval/scan-hint/v1/`에 업로드한다. 다운로드 URL은 서버 Terraform의 이미지 베이스 + 파일 해시 경로다. 각 호출 전에 실제 CDN 바이트와 원본 해시를 대조한다.
4. Java 21, 깨끗한 Git 커밋에서 실행자 셸에 OPENAI_API_KEY를 설정하고 `tools/scan-hint/run <출력폴더>/paths.csv --live`. 같은 사용자·머신에서 같은 명령으로 재개한다. 결과는 `~/.kbap-eval/scan-hint-v1/{predictions.csv,summary.md}`. 다른 머신에서 새로 시작하거나 이 폴더를 지우면 안 된다.
5. 일부만 완료되면 도입 여부는 미판정이다. 1,000장 완료 후 XS와 S22+ 각각의 5건 문턱 및 메뉴판 놓침률 1% 기준을 적용한다. 결과가 양호해도 A/B 실행은 별도 결정이다.

기본 모델·온도는 서버 vision 설정의 2026-11-05 스냅샷(gpt-5.6-luna, 1.0). 서버의 옵션 구성·프롬프트·파서를 그대로 쓰되, 한 장에 여러 번 결제하지 않도록 SDK와 어댑터 재시도는 끈다. 요청 결과가 불확실하거나 usage가 없으면 pending 기록을 남기고 멈춘다. 이를 삭제하고 재실행하지 말고 공급자 사용 내역과 대조한다. 데이터·코드·모델 스냅샷이 다른 기준선과 결과를 섞지 않는다.

예산은 이 기준선의 OpenAI 표준 API 비용 $15다. 예상 $1은 실측이 아니라 입력 2,000·출력 500토큰/장 × 1,000장을 가정한 값이다. 서버 단가와 [모델 단가표](https://developers.openai.com/api/docs/models/gpt-5.6-luna)를 대조했다. 매 호출 전 $0.76을 예약한다(최대 입력 1.05M × 장문·캐시 쓰기 상단 $0.50/M + 최대 출력 128k × $1.80/M = $0.7554). 완료분은 실제 usage 기반 상계로 교체한다. 표준 단가 환산액은 청구서가 아니며, 잔여 예산 계산에서는 캐시 쓰기 할증 상단까지 반영한다. S3/CDN 요금은 이 API 예산에서 제외한다.

검증: `(cd tools/scan-hint && python3 -m unittest -q test_baseline)` 및 `./gradlew :common:test --tests '*ScanBaselineWorkerTest' --tests '*OpenAiMenuBoardVisionExtractorTest'`. HTTP 대역은 로컬에서만 뜬다.
