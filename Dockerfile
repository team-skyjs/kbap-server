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

FROM public.ecr.aws/aws-cli/aws-cli:2.36.31 AS awscli

FROM eclipse-temurin:21-jdk AS profile-runtime
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
COPY --from=awscli /usr/local/aws-cli/ /usr/local/aws-cli/
RUN ln -s /usr/local/aws-cli/v2/current/bin/aws /usr/local/bin/aws
COPY ops/jfr/kbap-profile.jfc /app/kbap-profile.jfc
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
COPY --from=build /workspace/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
