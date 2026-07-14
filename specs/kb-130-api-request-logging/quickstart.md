# Quickstart: 요청 흐름 로깅 검증 (KB-130)

## 로컬 텍스트 로그 확인

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun
```

```bash
curl -i http://localhost:8080/api/v1/foods/search?keyword=kimchi
```

확인 사항:

1. 응답 헤더에 `X-Request-Id: <uuid>` 존재.
2. 콘솔에 같은 uuid 가 붙은 로그 한 쌍:
   - `--> GET /api/v1/foods/search?keyword=kimchi`
   - `<-- 200 GET /api/v1/foods/search (NNms)`
3. 인증 API(`Authorization: Bearer <token>`) 호출 시 `[uuid][memberId]` 두 값이 모두 표시.
4. 존재하지 않는 회원 등 에러 유발 시 에러 상세 로그(예외 타입·errorCode·status·uri)와 응답 로그가 같은 uuid.
5. actuator(`/actuator/health`)는 진입/응답 로그가 없음.

## JSON 구조화 로그 확인 (staging/prod 포맷)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :app:api:bootRun --args='--logging.structured.format.console=ecs'
```

같은 curl 후 콘솔이 JSON 한 줄 로그로 바뀌고 `requestId`·`memberId`·`status`·`elapsedMs` 필드가 보이면 성공. (staging/prod yml 에는 이 프로퍼티가 상시 켜져 있다.)

## 필터링 시연

```bash
# 상관 키로 요청 하나의 전 흐름
grep '550e8400-e29b' api.log

# 회원으로 요청 이력 (JSON 환경)
jq 'select(.memberId=="42")' api.log
```

## 테스트

```bash
./gradlew :app:api:test --tests "com.kbap.app.api.common.logging.*" --tests "com.kbap.app.api.common.GlobalExceptionHandlerTest"
```
