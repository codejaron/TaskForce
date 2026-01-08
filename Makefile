.PHONY: help check build up down restart logs ps clean rebuild

# 默认目标
help:
	@echo "TaskForce - Docker 部署命令"
	@echo ""
	@echo "使用方法: make [命令]"
	@echo ""
	@echo "可用命令:"
	@echo "  build     - 构建所有 Docker 镜像"
	@echo "  up        - 启动所有服务"
	@echo "  down      - 停止所有服务"
	@echo "  restart   - 重启所有服务"
	@echo "  logs      - 查看所有服务日志"
	@echo "  ps        - 查看服务状态"
	@echo "  clean     - 停止服务并删除数据卷"
	@echo "  rebuild   - 重新构建并启动服务"
	@echo ""
	@echo "特定服务命令:"
	@echo "  make logs-backend   - 查看后端日志"
	@echo "  make logs-frontend  - 查看前端日志"
	@echo "  make restart-backend - 重启后端服务"
	@echo ""

# 构建镜像
build:
	@echo "构建 Docker 镜像..."
	@docker-compose build

# 启动服务
up:
	@echo "启动服务..."
	@docker-compose up -d
	@echo "等待服务启动..."
	@sleep 10
	@docker-compose ps

# 停止服务
down:
	@echo "停止服务..."
	@docker-compose down

# 重启服务
restart:
	@echo "重启服务..."
	@docker-compose restart

# 查看日志
logs:
	@docker-compose logs -f

# 查看后端日志
logs-backend:
	@docker-compose logs -f backend

# 查看前端日志
logs-frontend:
	@docker-compose logs -f frontend

# 查看 MySQL 日志
logs-mysql:
	@docker-compose logs -f mysql

# 查看服务状态
ps:
	@docker-compose ps

# 重启后端
restart-backend:
	@docker-compose restart backend

# 重启前端
restart-frontend:
	@docker-compose restart frontend

# 清理所有数据
clean:
	@echo "警告: 这将删除所有数据！"
	@read -p "确认删除所有数据？[y/N] " confirm; \
	if [ "$$confirm" = "y" ] || [ "$$confirm" = "Y" ]; then \
		docker-compose down -v; \
		echo "已清理所有数据"; \
	else \
		echo "已取消"; \
	fi

# 重新构建并启动
rebuild:
	@echo "重新构建并启动服务..."
	@docker-compose down
	@docker-compose build --no-cache
	@docker-compose up -d
	@echo "等待服务启动..."
	@sleep 10
	@docker-compose ps

# 进入后端容器
shell-backend:
	@docker-compose exec backend /bin/sh

# 进入 MySQL 容器
shell-mysql:
	@docker-compose exec mysql /bin/bash

# 备份数据库
backup-db:
	@echo "备份数据库..."
	@docker-compose exec -T mysql mysqldump -uroot -p$${MYSQL_ROOT_PASSWORD:-TaskForce123456} taskforce > backup_$$(date +%Y%m%d_%H%M%S).sql
	@echo "备份完成！"

# 查看资源占用
stats:
	@docker stats --no-stream

