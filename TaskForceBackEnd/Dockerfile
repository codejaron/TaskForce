# 构建阶段
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

# 运行阶段 - 完整环境（支持 Java + Node.js + Python + Git）
FROM ubuntu:22.04

WORKDIR /app

# 安装 Java、Node.js、Python 和常用工具
RUN apt-get update && apt-get install -y \
    # Java 运行环境
    openjdk-21-jre-headless \
    # Node.js 和 npm（用于 MCP Server）
    nodejs npm \
    # Python 和 pip（用于 Python MCP Server）
    python3 python3-pip \
    # 常用系统工具
    git curl wget \
    # 清理缓存减小镜像体积
    && rm -rf /var/lib/apt/lists/*

# 验证安装
RUN java -version && \
    node --version && \
    npm --version && \
    python3 --version && \
    git --version

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]

