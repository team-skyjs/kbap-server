package com.meogo.api.common

/**
 * API 엔드포인트 경로 규약(고정).
 *
 * 모든 컨트롤러의 `@RequestMapping` 은 버전 베이스로 시작한다 — `/api/{버전}`.
 * 예: `@RequestMapping(ApiPaths.V1 + "/menu-scans")` → `POST /api/v1/menu-scans`.
 *
 * 새 버전은 여기 상수를 추가(예: `const val V2 = "/api/v2"`)하고 해당 버전 컨트롤러가 참조한다.
 * 같은 리소스의 v1·v2 컨트롤러는 서로 다른 베이스를 써 공존한다.
 * (actuator·springdoc 등 프레임워크 경로는 이 규약 밖이며 별도 경로를 유지한다.)
 */
object ApiPaths {
    const val V1 = "/api/v1"
}
