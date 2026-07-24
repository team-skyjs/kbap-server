package com.kbap.application.foodimage

data class FoodImageProperties(
    val model: String,
    val batchSize: Int,
    val inputUsdPerMillionTokens: Double,
    val outputUsdPerMillionTokens: Double,
    val usdToKrw: Double,
) {
    fun promptFor(koreanName: String): String = PROMPT_TEMPLATE.replace(NAME_PLACEHOLDER, koreanName)

    companion object {
        const val PROMPT_VERSION = "v1"

        private const val NAME_PLACEHOLDER = "{음식명}"

        val PROMPT_TEMPLATE = """
            {음식명}의 음식 사진. 실제 한국 식당에서 판매하는 {음식명}을 카메라로 촬영한 실사 사진처럼 표현한다.

            구도: 수평 기준 70~90도 사이의 하이앵글로 내려다보는 시점. {음식명}에 실제로 어울리는 그릇(뚝배기, 냄비, 접시, 대접, 컵, 잔, 병 등)에 담겨 있다. 그릇에 바짝 다가간 클로즈업 풀샷 — 그릇 전체가 잘리지 않고 프레임에 들어오되, 그릇이 화면의 85~90%를 차지할 만큼 크게 담는다. 배경 테이블은 가장자리에 살짝만 보인다. 정사각형 1:1 구도. 음식이 화면의 주인공으로 크고 먹음직스럽게 보인다.

            음식 표현: 그 음식의 대표적인 조리 형태와 실제 재료만 보여준다. 재료와 고명은 일정한 간격이나 대칭 없이 자연스럽게 흐트러진, 약간 불완전한 배치. 뜨거운 음식은 은은한 김, 차가운 음식은 물기 정도의 현실적인 연출. 음식 표면은 부드럽고 매끄럽게 표현한다 — 오돌토돌한 요철, 좁쌀 같은 알갱이, 주름, 미세한 굴곡 등 세밀한 표면 질감을 살리지 말고, 살짝 소프트하게 뭉갠 듯 은은하고 무광의 부드러운 질감으로 그린다. 국물의 기포는 큼직한 것 두세 개 이하로 최소화하고, 밥알·깨·소스 방울·구멍·거품 같은 작고 둥근 요소가 빽빽하게 밀집한 표현은 절대 하지 않는다(불쾌감 유발).

            카메라·조명: 풀프레임 DSLR, 50mm 렌즈, f/5.6 느낌. 초점은 부드럽고 디테일은 은은하게. 창가에서 들어오는 부드러운 자연광이 한쪽에서 비추고, 그림자는 옅고 자연스럽다. 화이트밸런스는 중립, 색은 실물 그대로.

            금지: 음식 표면의 오돌토돌한 질감·미세 요철·알갱이 강조 금지. 촘촘한 구멍·기포·알갱이 무리 등 군집 패턴 금지. 과도한 채도나 인공적인 색 보정 금지. CG·3D 렌더·일러스트·그림 느낌 금지. 플라스틱 같은 인공적인 광택 금지. 모공이나 기름방울까지 보이는 극단적 접사 디테일 금지. 재료가 규칙적인 패턴으로 반복 배열되는 것 금지. 글자, 워터마크, 손, 사람 등장 금지.
        """.trimIndent()
    }
}
