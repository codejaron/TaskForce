# 第一阶段：构建阶段
FROM node:20-alpine AS builder

WORKDIR /app

# 复制 package.json 和 package-lock.json
COPY package*.json ./

# 安装依赖
RUN npm ci --only=production=false && \
    echo "✓ Dependencies installed successfully"

# 复制源代码
COPY . .

# 构建应用
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=${VITE_API_BASE_URL}

# 构建时显示详细信息
RUN echo "Building with VITE_API_BASE_URL=${VITE_API_BASE_URL}" && \
    npm run build && \
    echo "✓ Build completed successfully" && \
    ls -la dist/

# 第二阶段：运行阶段
FROM nginx:alpine

# 复制自定义 nginx 配置
COPY --from=builder /app/nginx.conf /etc/nginx/conf.d/default.conf

# 复制构建产物
COPY --from=builder /app/dist /usr/share/nginx/html

# 暴露端口
EXPOSE 80

# 健康检查
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://localhost:80 || exit 1

# 启动 nginx
CMD ["nginx", "-g", "daemon off;"]

