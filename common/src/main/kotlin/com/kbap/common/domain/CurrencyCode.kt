package com.kbap.common.domain

import java.math.BigDecimal

enum class CurrencyCode(val label: String, val krwPerUnit: BigDecimal) {
    AED("아랍에미리트 디르함", BigDecimal("385.5600")),
    AUD("호주 달러", BigDecimal("999.1300")),
    BDT("방글라데시 타카", BigDecimal("11.4700")),
    BHD("바레인 디나르", BigDecimal("3755.4500")),
    BND("브루나이 달러", BigDecimal("1105.8200")),
    BRL("브라질 레알", BigDecimal("277.2700")),
    CAD("캐나다 달러", BigDecimal("1016.1400")),
    CHF("스위스 프랑", BigDecimal("1747.7200")),
    CNY("중국 위안", BigDecimal("209.9300")),
    CZK("체코 코루나", BigDecimal("67.4300")),
    DKK("덴마크 크로네", BigDecimal("218.6100")),
    EGP("이집트 파운드", BigDecimal("28.3500")),
    EUR("유럽연합 유로", BigDecimal("1634.2100")),
    FJD("피지 달러", BigDecimal("640.2300")),
    GBP("영국 파운드", BigDecimal("1912.5900")),
    HKD("홍콩 달러", BigDecimal("180.4800")),
    HUF("헝가리 포린트", BigDecimal("4.5100")),
    IDR("인도네시아 루피아", BigDecimal("0.0805")),
    ILS("이스라엘 쉐켈", BigDecimal("471.6500")),
    INR("인도 루피", BigDecimal("14.9200")),
    JOD("요르단 디나르", BigDecimal("1997.2200")),
    JPY("일본 엔", BigDecimal("8.8906")),
    KHR("캄보디아 리엘", BigDecimal("34.9900")),
    KRW("대한민국 원", BigDecimal("1.0000")),
    KWD("쿠웨이트 디나르", BigDecimal("4610.8500")),
    KZT("카자흐스탄 텡게", BigDecimal("3.0400")),
    MNT("몽골 투그릭", BigDecimal("0.3900")),
    MXN("멕시코 페소", BigDecimal("82.6600")),
    MYR("말레이시아 링깃", BigDecimal("346.5400")),
    NOK("노르웨이 크로네", BigDecimal("149.1300")),
    NPR("네팔 루피", BigDecimal("9.2700")),
    NZD("뉴질랜드 달러", BigDecimal("833.1700")),
    PHP("필리핀 페소", BigDecimal("23.2500")),
    PKR("파키스탄 루피", BigDecimal("5.1100")),
    PLN("폴란드 즈워티", BigDecimal("379.9600")),
    QAR("카타르 리알", BigDecimal("388.3600")),
    RUB("러시아 루블", BigDecimal("17.2100")),
    SAR("사우디 리알", BigDecimal("377.0100")),
    SEK("스웨덴 크로네", BigDecimal("149.1600")),
    SGD("싱가포르 달러", BigDecimal("1105.9900")),
    THB("태국 바트", BigDecimal("42.8900")),
    TRY("튀르키예 리라", BigDecimal("29.6700")),
    TWD("타이완 달러", BigDecimal("44.0800")),
    USD("미국 달러", BigDecimal("1416.0000")),
    VND("베트남 동", BigDecimal("0.0544")),
    ZAR("남아공 랜드", BigDecimal("87.5400")),
    ;

    companion object {
        fun from(raw: String?): CurrencyCode? = entries.firstOrNull { it.name == raw }
    }
}
