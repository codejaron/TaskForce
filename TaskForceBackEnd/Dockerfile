# ========================
# 第一阶段：构建 (Builder)
# ========================
FROM maven:3.9.5-eclipse-temurin-21 AS builder

WORKDIR /build

# 配置 Maven 使用阿里云镜像
RUN mkdir -p /root/.m2 && \
    echo '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" \
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" \
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 \
          http://maven.apache.org/xsd/settings-1.0.0.xsd"> \
      <mirrors> \
        <mirror> \
          <id>aliyunmaven</id> \
          <mirrorOf>*</mirrorOf> \
          <name>阿里云公共仓库</name> \
          <url>https://maven.aliyun.com/repository/public</url> \
        </mirror> \
      </mirrors> \
    </settings>' > /root/.m2/settings.xml

COPY pom.xml .
RUN mvn dependency:go-offline -B || mvn dependency:resolve -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# ========================
# 第二阶段：运行 (Runtime)
# ========================
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# 1. 根据架构配置 apt 源（x86 使用阿里云加速，ARM 使用官方源）
RUN ARCH=$(dpkg --print-architecture) && \
    echo "Detected architecture: $ARCH" && \
    if [ "$ARCH" = "amd64" ]; then \
        echo "Using Aliyun mirrors for x86_64..." && \
        sed -i 's|http://archive.ubuntu.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list && \
        sed -i 's|http://security.ubuntu.com|http://mirrors.aliyun.com|g' /etc/apt/sources.list; \
    else \
        echo "Using official Ubuntu Ports for ARM..."; \
    fi

# 2. 安装基础环境 (Python3, Git, Curl)
RUN apt-get update && \
    apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    python3-venv \
    git \
    curl \
    xz-utils && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# 3. [新增] 安装 uv (用于支持 uvx 命令)
# 使用官方脚本安装到 /usr/local/bin 以便全局可用
RUN curl -LsSf https://astral.sh/uv/install.sh | sh && \
    mv /root/.local/bin/uv /usr/local/bin/ && \
    mv /root/.local/bin/uvx /usr/local/bin/

# 4. 安装 Node.js 20.x (加速下载)
ENV NODE_VERSION=20.11.0
RUN ARCH=$(uname -m) && \
    if [ "$ARCH" = "aarch64" ]; then ARCH="arm64"; fi && \
    if [ "$ARCH" = "x86_64" ]; then ARCH="x64"; fi && \
    echo "Detected architecture: $ARCH" && \
    curl -fsSL https://npmmirror.com/mirrors/node/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-${ARCH}.tar.xz -o node.tar.xz && \
    tar -xJf node.tar.xz -C /usr/local --strip-components=1 && \
    rm node.tar.xz && \
    node --version && npm --version

# 5. 配置 npm 淘宝镜像
RUN npm config set registry https://registry.npmmirror.com

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]