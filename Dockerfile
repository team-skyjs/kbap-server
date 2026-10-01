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

# aws CLI 는 공식 배포 zip 에서 설치한다(dev 프로파일링의 `aws s3 cp` 용).
# public.ecr.aws 의 aws-cli 이미지 메타데이터 조회가 간헐적으로 실패해(httpReadSeeker unexpected status)
# dev 배포가 깨졌으므로, 레지스트리 조회 대신 curl 재시도가 붙은 단일 파일 다운로드로 바꿨다.
# 압축 해제는 JDK 의 jar 도구로 해 unzip 설치(apt 조회)를 피한다.
# 아카이브는 빌드 대상 아키텍처(TARGETARCH)에 맞춘다 — dev 워크플로는 amd64, 로컬 ARM 빌드는 aarch64.
FROM eclipse-temurin:21-jdk AS awscli
ARG AWS_CLI_VERSION=2.36.31
ARG TARGETARCH
RUN case "${TARGETARCH}" in \
      amd64) AWS_CLI_ARCH=x86_64 ;; \
      arm64) AWS_CLI_ARCH=aarch64 ;; \
      *) echo "지원하지 않는 TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
 && curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 \
      "https://awscli.amazonaws.com/awscli-exe-linux-${AWS_CLI_ARCH}-${AWS_CLI_VERSION}.zip" -o /tmp/awscli.zip \
 && cd /tmp && jar xf awscli.zip && chmod -R a+x aws/install aws/dist \
 && ./aws/install --bin-dir /usr/local/bin --install-dir /usr/local/aws-cli \
 && rm -rf /tmp/aws /tmp/awscli.zip

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
