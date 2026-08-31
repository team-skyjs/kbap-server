package com.kbap.common.domain

enum class CurrencyCode(val label: String) {
    AUD("호주 달러"),
    BRL("브라질 레알"),
    CAD("캐나다 달러"),
    CHF("스위스 프랑"),
    CNY("중국 위안"),
    CZK("체코 코루나"),
    DKK("덴마크 크로네"),
    EUR("유럽연합 유로"),
    GBP("영국 파운드"),
    HKD("홍콩 달러"),
    HUF("헝가리 포린트"),
    IDR("인도네시아 루피아"),
    ILS("이스라엘 쉐켈"),
    INR("인도 루피"),
    ISK("아이슬란드 크로나"),
    JPY("일본 엔"),
    KRW("대한민국 원"),
    MXN("멕시코 페소"),
    MYR("말레이시아 링깃"),
    NOK("노르웨이 크로네"),
    NZD("뉴질랜드 달러"),
    PHP("필리핀 페소"),
    PLN("폴란드 즈워티"),
    RON("루마니아 레우"),
    SEK("스웨덴 크로나"),
    SGD("싱가포르 달러"),
    THB("태국 바트"),
    TRY("튀르키예 리라"),
    USD("미국 달러"),
    ZAR("남아프리카 랜드"),
    ;

    companion object {
        fun from(code: String?): CurrencyCode? = entries.firstOrNull { it.name == code }
    }
}
