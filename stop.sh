#!/bin/bash

# TaskForce - 停止脚本
# 使用方法: ./stop.sh

set -e

echo "=========================================="
echo "  TaskForce - 停止服务"
echo "=========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# 使用 docker compose 或 docker-compose
if docker compose version &> /dev/null; then
    COMPOSE_CMD="docker compose"
else
    COMPOSE_CMD="docker-compose"
fi

# 询问是否删除数据卷
echo -e "${YELLOW}是否删除数据卷（数据库数据将被清空）？ [y/N]${NC}"
read -r response

if [[ "$response" =~ ^([yY][eE][sS]|[yY])$ ]]; then
    echo -e "${RED}停止并删除所有容器、网络和数据卷...${NC}"
    $COMPOSE_CMD down -v
    echo -e "${GREEN}✓ 已停止服务并删除数据卷${NC}"
else
    echo "停止服务（保留数据卷）..."
    $COMPOSE_CMD down
    echo -e "${GREEN}✓ 已停止服务（数据已保留）${NC}"
fi

echo ""
echo -e "${GREEN}完成！${NC}"
