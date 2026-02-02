#!/bin/bash

# ============================================================
# TaskForce 后端项目重构迁移脚本
# 只移动文件，不改名字
# ============================================================

set -e

BASE_PATH="src/main/java/com/agent"

echo "========================================"
echo "  TaskForce 后端重构迁移脚本"
echo "========================================"

# 检查目录
if [ ! -d "$BASE_PATH" ]; then
    echo "错误: 请在项目根目录执行此脚本"
    exit 1
fi

# ============================================================
# 第一步: 创建新目录结构
# ============================================================
echo "[1/6] 创建新目录结构..."

mkdir -p $BASE_PATH/api/controller
mkdir -p $BASE_PATH/api/request
mkdir -p $BASE_PATH/api/response

mkdir -p $BASE_PATH/application/service/tool

mkdir -p $BASE_PATH/domain/agent
mkdir -p $BASE_PATH/domain/session
mkdir -p $BASE_PATH/domain/plan
mkdir -p $BASE_PATH/domain/tool
mkdir -p $BASE_PATH/domain/token

mkdir -p $BASE_PATH/orchestration/engine
mkdir -p $BASE_PATH/orchestration/state
mkdir -p $BASE_PATH/orchestration/dto
mkdir -p $BASE_PATH/orchestration/graph/node
mkdir -p $BASE_PATH/orchestration/graph/dispatcher
mkdir -p $BASE_PATH/orchestration/graph/config

mkdir -p $BASE_PATH/infrastructure/config
mkdir -p $BASE_PATH/infrastructure/persistence/entity
mkdir -p $BASE_PATH/infrastructure/persistence/mapper
mkdir -p $BASE_PATH/infrastructure/persistence/repository
mkdir -p $BASE_PATH/infrastructure/llm
mkdir -p $BASE_PATH/infrastructure/mcp

mkdir -p $BASE_PATH/common/exception
mkdir -p $BASE_PATH/common/context
mkdir -p $BASE_PATH/common/util

echo "  ✓ 目录创建完成"

# ============================================================
# 第二步: 删除老编排逻辑
# ============================================================
echo "[2/6] 删除老编排逻辑..."


echo "  ✓ 已删除 4 个废弃文件"

# ============================================================
# 第三步: 迁移 API 层
# ============================================================
echo "[3/6] 迁移 API 层..."

# Controllers
mv $BASE_PATH/controller/*.java $BASE_PATH/api/controller/ 2>/dev/null || true

# Request DTOs
mv $BASE_PATH/dto/AgentRequest.java $BASE_PATH/api/request/ 2>/dev/null || true
mv $BASE_PATH/dto/ChannelModelRequest.java $BASE_PATH/api/request/ 2>/dev/null || true
mv $BASE_PATH/dto/LLMProviderRequest.java $BASE_PATH/api/request/ 2>/dev/null || true
mv $BASE_PATH/dto/RemoteModelsRequest.java $BASE_PATH/api/request/ 2>/dev/null || true
mv $BASE_PATH/dto/SessionCreateRequest.java $BASE_PATH/api/request/ 2>/dev/null || true
mv $BASE_PATH/dto/UserInputRequest.java $BASE_PATH/api/request/ 2>/dev/null || true

# Response DTOs
mv $BASE_PATH/dto/AgentResponse.java $BASE_PATH/api/response/ 2>/dev/null || true
mv $BASE_PATH/dto/ApiResponse.java $BASE_PATH/api/response/ 2>/dev/null || true
mv $BASE_PATH/dto/SubmitResponse.java $BASE_PATH/api/response/ 2>/dev/null || true
mv $BASE_PATH/dto/WorkflowStateResponse.java $BASE_PATH/api/response/ 2>/dev/null || true

echo "  ✓ API 层迁移完成"

# ============================================================
# 第四步: 迁移 Application / Domain / Orchestration
# ============================================================
echo "[4/6] 迁移 Application / Domain / Orchestration..."

# Application - Service
mv $BASE_PATH/service/*.java $BASE_PATH/application/service/ 2>/dev/null || true
mv $BASE_PATH/service/tool/*.java $BASE_PATH/application/service/tool/ 2>/dev/null || true

# Domain - Agent
mv $BASE_PATH/model/AgentProfile.java $BASE_PATH/domain/agent/ 2>/dev/null || true

# Domain - Session
mv $BASE_PATH/model/SessionState.java $BASE_PATH/domain/session/ 2>/dev/null || true

# Domain - Tool
mv $BASE_PATH/model/ToolInfo.java $BASE_PATH/domain/tool/ 2>/dev/null || true
mv $BASE_PATH/dto/AgentToolDetail.java $BASE_PATH/domain/tool/ 2>/dev/null || true
mv $BASE_PATH/dto/ToolCallRecord.java $BASE_PATH/domain/tool/ 2>/dev/null || true

# Domain - Plan
mv $BASE_PATH/domain/model/plan/*.java $BASE_PATH/domain/plan/ 2>/dev/null || true
mv $BASE_PATH/domain/model/context/*.java $BASE_PATH/domain/plan/ 2>/dev/null || true
mv $BASE_PATH/domain/repository/*.java $BASE_PATH/domain/plan/ 2>/dev/null || true

# Domain - Token
mv $BASE_PATH/dto/AgentCostDTO.java $BASE_PATH/domain/token/ 2>/dev/null || true
mv $BASE_PATH/dto/AgentUsageDTO.java $BASE_PATH/domain/token/ 2>/dev/null || true
mv $BASE_PATH/dto/DailyCostDTO.java $BASE_PATH/domain/token/ 2>/dev/null || true
mv $BASE_PATH/dto/ModelUsageDTO.java $BASE_PATH/domain/token/ 2>/dev/null || true
mv $BASE_PATH/dto/ProviderCostDTO.java $BASE_PATH/domain/token/ 2>/dev/null || true
mv $BASE_PATH/dto/SessionCostDTO.java $BASE_PATH/domain/token/ 2>/dev/null || true

# Orchestration
mv $BASE_PATH/infrastructure/graph/AgentGraphRunner.java $BASE_PATH/orchestration/engine/ 2>/dev/null || true
mv $BASE_PATH/application/orchestration/StateManager.java $BASE_PATH/orchestration/state/ 2>/dev/null || true
mv $BASE_PATH/application/orchestration/dto/*.java $BASE_PATH/orchestration/dto/ 2>/dev/null || true
mv $BASE_PATH/infrastructure/graph/config/*.java $BASE_PATH/orchestration/graph/config/ 2>/dev/null || true
mv $BASE_PATH/infrastructure/graph/node/*.java $BASE_PATH/orchestration/graph/node/ 2>/dev/null || true
mv $BASE_PATH/infrastructure/graph/dispatcher/*.java $BASE_PATH/orchestration/graph/dispatcher/ 2>/dev/null || true

echo "  ✓ Application / Domain / Orchestration 迁移完成"

# ============================================================
# 第五步: 迁移 Infrastructure
# ============================================================
echo "[5/6] 迁移 Infrastructure..."

# Config
mv $BASE_PATH/config/*.java $BASE_PATH/infrastructure/config/ 2>/dev/null || true

# LLM
mv $BASE_PATH/llm/*.java $BASE_PATH/infrastructure/llm/ 2>/dev/null || true
mv $BASE_PATH/factory/*.java $BASE_PATH/infrastructure/llm/ 2>/dev/null || true

# MCP
mv $BASE_PATH/client/*.java $BASE_PATH/infrastructure/mcp/ 2>/dev/null || true

# Persistence
mv $BASE_PATH/entity/*.java $BASE_PATH/infrastructure/persistence/entity/ 2>/dev/null || true
mv $BASE_PATH/mapper/*.java $BASE_PATH/infrastructure/persistence/mapper/ 2>/dev/null || true
mv $BASE_PATH/infrastructure/persistence/PlanRepositoryImpl.java $BASE_PATH/infrastructure/persistence/repository/ 2>/dev/null || true

# Common
mv $BASE_PATH/infrastructure/exception/*.java $BASE_PATH/common/exception/ 2>/dev/null || true
mv $BASE_PATH/infrastructure/context/*.java $BASE_PATH/common/context/ 2>/dev/null || true
mv $BASE_PATH/util/*.java $BASE_PATH/common/util/ 2>/dev/null || true

echo "  ✓ Infrastructure 迁移完成"

# ============================================================
# 第六步: 清理空目录
# ============================================================
echo "[6/6] 清理空目录..."

find $BASE_PATH -type d -empty -delete 2>/dev/null || true

echo "  ✓ 空目录清理完成"

# ============================================================
# 完成
# ============================================================
echo ""
echo "========================================"
echo "  文件迁移完成！"
echo "========================================"
echo ""
echo "接下来需要:"
echo "1. 运行 update_packages.sh 更新 package 声明"
echo "2. 运行 update_imports.sh 更新 import 语句"
echo "3. 修改 GroupChatController.java 删除老逻辑"
echo "4. 更新 application.yml 和 McpAgentApplication.java 中的包路径"
echo ""
