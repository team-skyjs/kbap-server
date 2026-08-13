plugins {
    id("kbap.common-conventions")
}

dependencies {
    // 벡터 저장소 어댑터가 MongoCollection 을 공개 시그니처로 노출하고, 부트앱 config 가 MongoClient 를 조립한다.
    "api"(libs.mongodb.driver.sync)
}
