// :common — api·batch·infra 가 공유하는 코드(구 :core 커널 + 공유 도메인 + seam 인터페이스).
// 공통 설정(JPA·BOM·testFixtures)은 kbap.common-conventions 아키타입에서 온다(KB-244).
plugins {
    id("kbap.common-conventions")
}
