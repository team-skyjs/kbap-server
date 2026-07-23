# syntax=docker/dockerfile:1
# 멀티스테이지: Gradle 로 bootJar 빌드 → JRE 런타임 이미지.
# 빌드 대상은 APP_MODULE 인자로 선택(api 기본 — 기존 배포 무영향, batch 는 --build-arg APP_MODULE=batch).
# 테스트는 CI 에서 별도 수행하므로 여기선 제외.

FROM eclipse-temurin:21-jdk AS build
ARG APP_MODULE=api
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew \
 && ./gradlew :app:${APP_MODULE}:bootJar -x test --no-daemon \
 && find app/${APP_MODULE}/build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
