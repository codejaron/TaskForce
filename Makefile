.PHONY: help backend frontend mcp backend-test frontend-lint frontend-build mcp-test

help:
	@echo "TaskForce - 本地开发命令"
	@echo ""
	@echo "使用方法: make [命令]"
	@echo ""
	@echo "可用命令:"
	@echo "  backend        - 启动后端服务"
	@echo "  frontend       - 启动前端开发服务"
	@echo "  mcp            - 启动 MCP Server"
	@echo "  backend-test   - 运行后端测试"
	@echo "  frontend-lint  - 运行前端 lint"
	@echo "  frontend-build - 构建前端"
	@echo "  mcp-test       - 运行 MCP Server 测试"

backend:
	@cd TaskForceBackEnd && mvn spring-boot:run

frontend:
	@cd TaskForceFrontEnd && npm run dev

mcp:
	@cd mcp-server && mvn spring-boot:run

backend-test:
	@cd TaskForceBackEnd && mvn test

frontend-lint:
	@cd TaskForceFrontEnd && npm run lint

frontend-build:
	@cd TaskForceFrontEnd && npm run build

mcp-test:
	@cd mcp-server && mvn test
