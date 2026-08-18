# syntax=docker/dockerfile:1
# 멀티스테이지: Gradle 로 web bootJar 빌드 → JRE 런타임 이미지.
# 빌드 대상은 web 앱(:api). 테스트는 CI 에서 별도 수행하므로 여기선 제외.
# 배치 앱 이미지는 Dockerfile.batch 로 별도 빌드·관리한다.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew \
 && ./gradlew :api:bootJar -x test --no-daemon \
 && find api/build/libs -name '*.jar' ! -name '*-plain.jar' -exec cp {} /workspace/app.jar \;

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# DocumentDB TLS: Amazon RDS 사설 CA 번들을 JVM 기본 truststore(cacerts)에 추가한다(대체 아님 — 다른 HTTPS 유지).
# Java 드라이버는 URI tlsCAFile 미지원이라 truststore 만이 경로다.
ADD https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /tmp/rds-global-bundle.pem
RUN awk 'BEGIN{n=0} /-----BEGIN CERTIFICATE-----/{n++} {print > ("/tmp/rds-ca-" n ".pem")}' /tmp/rds-global-bundle.pem \
 && for cert in /tmp/rds-ca-*.pem; do \
      keytool -importcert -cacerts -storepass changeit -noprompt \
        -alias "rds-$(basename "$cert" .pem)" -file "$cert"; \
    done \
 && rm /tmp/rds-global-bundle.pem /tmp/rds-ca-*.pem
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
