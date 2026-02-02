#!/bin/bash

# ============================================================
# 更新 import 语句
# ============================================================

BASE_PATH="src/main/java"

echo "更新 import 语句..."

sed_inplace() {
    if [[ "$OSTYPE" == "darwin"* ]]; then
        sed -i '' "$@"
    else
        sed -i "$@"
    fi
}

find $BASE_PATH -name "*.java" -type f | while read file; do

    # Controller
    sed_inplace 's/import com\.agent\.controller\./import com.agent.api.controller./g' "$file"

    # Request/Response DTO
    sed_inplace 's/import com\.agent\.dto\.AgentRequest/import com.agent.api.request.AgentRequest/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.ChannelModelRequest/import com.agent.api.request.ChannelModelRequest/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.LLMProviderRequest/import com.agent.api.request.LLMProviderRequest/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.RemoteModelsRequest/import com.agent.api.request.RemoteModelsRequest/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.SessionCreateRequest/import com.agent.api.request.SessionCreateRequest/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.UserInputRequest/import com.agent.api.request.UserInputRequest/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.AgentResponse/import com.agent.api.response.AgentResponse/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.ApiResponse/import com.agent.api.response.ApiResponse/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.SubmitResponse/import com.agent.api.response.SubmitResponse/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.WorkflowStateResponse/import com.agent.api.response.WorkflowStateResponse/g' "$file"

    # Service
    sed_inplace 's/import com\.agent\.service\./import com.agent.application.service./g' "$file"

    # Domain - Model
    sed_inplace 's/import com\.agent\.model\.AgentProfile/import com.agent.domain.agent.AgentProfile/g' "$file"
    sed_inplace 's/import com\.agent\.model\.SessionState/import com.agent.domain.session.SessionState/g' "$file"
    sed_inplace 's/import com\.agent\.model\.ToolInfo/import com.agent.domain.tool.ToolInfo/g' "$file"

    # Domain - DTO
    sed_inplace 's/import com\.agent\.dto\.AgentToolDetail/import com.agent.domain.tool.AgentToolDetail/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.ToolCallRecord/import com.agent.domain.tool.ToolCallRecord/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.AgentCostDTO/import com.agent.domain.token.AgentCostDTO/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.AgentUsageDTO/import com.agent.domain.token.AgentUsageDTO/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.DailyCostDTO/import com.agent.domain.token.DailyCostDTO/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.ModelUsageDTO/import com.agent.domain.token.ModelUsageDTO/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.ProviderCostDTO/import com.agent.domain.token.ProviderCostDTO/g' "$file"
    sed_inplace 's/import com\.agent\.dto\.SessionCostDTO/import com.agent.domain.token.SessionCostDTO/g' "$file"

    # Domain - Plan
    sed_inplace 's/import com\.agent\.domain\.model\.plan\./import com.agent.domain.plan./g' "$file"
    sed_inplace 's/import com\.agent\.domain\.model\.context\./import com.agent.domain.plan./g' "$file"
    sed_inplace 's/import com\.agent\.domain\.repository\./import com.agent.domain.plan./g' "$file"

    # Orchestration
    sed_inplace 's/import com\.agent\.infrastructure\.graph\.AgentGraphRunner/import com.agent.orchestration.engine.AgentGraphRunner/g' "$file"
    sed_inplace 's/import com\.agent\.application\.orchestration\.StateManager/import com.agent.orchestration.state.StateManager/g' "$file"
    sed_inplace 's/import com\.agent\.application\.orchestration\.dto\./import com.agent.orchestration.dto./g' "$file"
    sed_inplace 's/import com\.agent\.infrastructure\.graph\.config\./import com.agent.orchestration.graph.config./g' "$file"
    sed_inplace 's/import com\.agent\.infrastructure\.graph\.node\./import com.agent.orchestration.graph.node./g' "$file"
    sed_inplace 's/import com\.agent\.infrastructure\.graph\.dispatcher\./import com.agent.orchestration.graph.dispatcher./g' "$file"

    # Infrastructure - Config
    sed_inplace 's/import com\.agent\.config\./import com.agent.infrastructure.config./g' "$file"

    # Infrastructure - LLM
    sed_inplace 's/import com\.agent\.llm\./import com.agent.infrastructure.llm./g' "$file"
    sed_inplace 's/import com\.agent\.factory\./import com.agent.infrastructure.llm./g' "$file"

    # Infrastructure - MCP
    sed_inplace 's/import com\.agent\.client\./import com.agent.infrastructure.mcp./g' "$file"

    # Infrastructure - Persistence
    sed_inplace 's/import com\.agent\.entity\./import com.agent.infrastructure.persistence.entity./g' "$file"
    sed_inplace 's/import com\.agent\.mapper\./import com.agent.infrastructure.persistence.mapper./g' "$file"

    # Common
    sed_inplace 's/import com\.agent\.infrastructure\.exception\./import com.agent.common.exception./g' "$file"
    sed_inplace 's/import com\.agent\.infrastructure\.context\./import com.agent.common.context./g' "$file"
    sed_inplace 's/import com\.agent\.util\./import com.agent.common.util./g' "$file"

done

echo "✓ import 语句更新完成"
