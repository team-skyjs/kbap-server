package com.meogo.batch

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

// 배치 앱. com.meogo 전체를 컴포넌트 스캔해 application/도메인/infra 빈을 조립한다.
// web 컨트롤러가 있는 :meogo-api:api 는 의존하지 않으므로 batch 클래스패스에 없고 스캔에도 잡히지 않는다.
@SpringBootApplication(scanBasePackages = ["com.meogo"])
class MeogoBatchApplication

fun main(args: Array<String>) {
    runApplication<MeogoBatchApplication>(*args)
}
