FROM jenkins/jenkins:lts-jdk21

USER root
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip jq ca-certificates \
 && ARCH="$(uname -m)" \
 && curl -sSL "https://awscli.amazonaws.com/awscli-exe-linux-${ARCH}.zip" -o /tmp/awscliv2.zip \
 && unzip -q /tmp/awscliv2.zip -d /tmp && /tmp/aws/install \
 && if [ "$ARCH" = "aarch64" ]; then P=ubuntu_arm64; else P=ubuntu_64bit; fi \
 && curl -sSL "https://s3.amazonaws.com/session-manager-downloads/plugin/latest/${P}/session-manager-plugin.deb" -o /tmp/smp.deb \
 && dpkg -i /tmp/smp.deb \
 && rm -rf /tmp/aws /tmp/awscliv2.zip /tmp/smp.deb /var/lib/apt/lists/*
USER jenkins
