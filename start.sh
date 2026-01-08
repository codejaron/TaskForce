#!/bin/bash

# TaskForce - 一键部署脚本
# 使用方法: ./start.sh

set -e

echo "=========================================="
echo "  TaskForce - Docker 一键部署"
echo "=========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 检查 Docker 是否安装
if ! command -v docker &> /dev/null; then
    echo -e "${RED}错误: Docker 未安装，请先安装 Docker${NC}"
    exit 1
fi

# 检查 Docker Compose 是否安装
if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
    echo -e "${RED}错误: Docker Compose 未安装，请先安装 Docker Compose${NC}"
    exit 1
fi

# 检查 .env 文件
if [ ! -f .env ]; then
    echo -e "${YELLOW}未找到 .env 文件，从模板创建...${NC}"
    cp .env.example .env
    echo -e "${GREEN}✓ 已创建 .env 文件，请根据需要修改配置${NC}"
fi

# 检查 mcp-config.json
if [ ! -f mcp-config.json ]; then
    echo -e "${YELLOW}警告: 未找到 mcp-config.json 文件${NC}"
    echo "创建默认配置..."
    cat > mcp-config.json << 'EOF'
{
  "mcpServers": {}
}
EOF
    echo -e "${GREEN}✓ 已创建默认 mcp-config.json${NC}"
fi

echo ""
echo -e "${GREEN}开始构建和启动服务...${NC}"
echo ""

# 使用 docker compose 或 docker-compose
if docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
else
    COMPOSE_CMD="docker-compose"
fi

# 构建并启动服务
$COMPOSE_CMD up -d --build

# 从 .env 读取端口（不存在则用默认值）
FRONTEND_PORT_VAL=${FRONTEND_PORT:-3000}
BACKEND_PORT_VAL=${BACKEND_PORT:-8080}
QDRANT_PORT_VAL=${QDRANT_PORT:-6333}

echo ""
echo -e "${GREEN}=========================================="
echo "  部署完成！"
echo "==========================================${NC}"
echo ""
echo "服务访问地址："
echo -e "  前端应用: ${GREEN}http://localhost:${FRONTEND_PORT_VAL}${NC}"
echo -e "  后端 API: ${GREEN}http://localhost:${BACKEND_PORT_VAL}${NC}"
echo -e "  Qdrant:   ${GREEN}http://localhost:${QDRANT_PORT_VAL}/dashboard${NC}"
echo ""
echo "查看服务状态："
echo -e "  ${YELLOW}$COMPOSE_CMD ps${NC}"
echo ""
echo "查看日志："
echo -e "  ${YELLOW}$COMPOSE_CMD logs -f${NC}"
echo ""
echo "停止服务："
echo -e "  ${YELLOW}$COMPOSE_CMD down${NC}"
echo ""
echo -e "${YELLOW}注意: 首次启动可能需要 3-5 分钟，请耐心等待${NC}"
echo ""

# 等待服务启动
echo "等待服务启动..."
sleep 10

# 检查服务健康状态
echo ""
echo "检查服务健康状态..."
$COMPOSE_CMD ps

echo ""
echo -e "${GREEN}完成！请访问 http://localhost:${FRONTEND_PORT_VAL} 开始使用${NC}"
