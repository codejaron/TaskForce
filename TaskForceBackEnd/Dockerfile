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

# 配置 Ubuntu 使用阿里云镜像源（加速下载）
RUN sed -i 's@//.*archive.ubuntu.com@//mirrors.aliyun.com@g' /etc/apt/sources.list && \
    sed -i 's@//.*ports.ubuntu.com@//mirrors.aliyun.com@g' /etc/apt/sources.list

# 安装 Java、Python 和常用工具
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-21-jre-headless \
    python3 python3-pip \
    git curl wget ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# 安装 Node.js 20.x（使用国内镜像加速）
RUN curl -fsSL https://mirrors.tuna.tsinghua.edu.cn/nodesource/deb_20.x/nodistro/repo/deb/nodesource-repo.gpg.key | gpg --dearmor -o /usr/share/keyrings/nodesource.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/nodesource.gpg] https://mirrors.tuna.tsinghua.edu.cn/nodesource/deb_20.x/nodistro nodistro main" > /etc/apt/sources.list.d/nodesource.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends nodejs && \
    rm -rf /var/lib/apt/lists/*

# 配置 npm 使用淘宝镜像
RUN npm config set registry https://registry.npmmirror.com

# 验证安装
RUN java -version && \
    node --version && \
    npm --version && \
    python3 --version && \
    git --version

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /app/app.jar"]

