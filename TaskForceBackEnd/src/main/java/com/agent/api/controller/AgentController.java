package com.agent.api.controller;

import com.agent.service.AgentToolService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.agent.api.request.AgentRequest;
import com.agent.api.response.AgentResponse;
import com.agent.api.dto.AgentToolDetail;
import com.agent.api.response.ApiResponse;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.persistence.entity.LLMProvider;
import com.agent.infrastructure.llm.ChatModelFactory;
import com.agent.infrastructure.persistence.mapper.LLMProviderMapper;
import com.agent.service.AgentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 智能体控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    private final AgentService agentService;
    private final ChatModelFactory chatModelFactory;
    private final LLMProviderMapper providerMapper;
    private final AgentToolService agentToolService;
    
    /**
     * 创建智能体
     */
    @PostMapping
    public ApiResponse<AgentResponse> createAgent(@Valid @RequestBody AgentRequest request) {
        try {
            Agent agent = agentService.createAgent(request);

            // 获取工具详情并构建响应
            List<AgentToolDetail> toolDetails = agentToolService.getAgentToolDetails(agent.getId());
            AgentResponse response = AgentResponse.fromAgentWithToolDetails(agent, toolDetails);

            return ApiResponse.success("智能体创建成功", response);
        } catch (Exception e) {
            log.error("Create agent failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
    
    /**
     * 更新智能体
     */
    @PutMapping("/{agentId}")
    public ApiResponse<AgentResponse> updateAgent(
        @PathVariable Long agentId,
        @Valid @RequestBody AgentRequest request
    ) {
        try {
            Agent agent = agentService.updateAgent(agentId, request);

            // 获取工具详情并构建响应
            List<AgentToolDetail> toolDetails = agentToolService.getAgentToolDetails(agent.getId());
            AgentResponse response = AgentResponse.fromAgentWithToolDetails(agent, toolDetails);

            return ApiResponse.success("智能体更新成功", response);
        } catch (Exception e) {
            log.error("Update agent failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
    
    /**
     * 删除智能体
     */
    @DeleteMapping("/{agentId}")
    public ApiResponse<Void> deleteAgent(@PathVariable Long agentId) {
        try {
            agentService.deleteAgent(agentId);
            return ApiResponse.success("智能体删除成功", null);
        } catch (Exception e) {
            log.error("Delete agent failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取所有智能体（包含系统内置Agent）
     * 用于系统配置页初始化/展示。
     */
    @GetMapping("/all")
    public ApiResponse<List<AgentResponse>> getAllAgentsIncludeSystem() {
        try {
            List<Agent> agents = agentService.getAllAgentsIncludeSystem();

            List<AgentResponse> responses = agents.stream()
                    .map(agent -> {
                        List<AgentToolDetail> toolDetails = agentToolService.getAgentToolDetails(agent.getId());
                        return AgentResponse.fromAgentWithToolDetails(agent, toolDetails);
                    })
                    .toList();

            return ApiResponse.success(responses);
        } catch (Exception e) {
            log.error("Get all agents (include system) failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取所有智能体（包含工具配置）
     */
    @GetMapping
    public ApiResponse<List<AgentResponse>> getAllAgents() {
        try {
            List<Agent> agents = agentService.getAllAgents();

            // 为每个 Agent 附加工具配置信息
            List<AgentResponse> responses = agents.stream()
                    .map(agent -> {
                        List<AgentToolDetail> toolDetails = agentToolService.getAgentToolDetails(agent.getId());
                        return AgentResponse.fromAgentWithToolDetails(agent, toolDetails);
                    })
                    .toList();

            return ApiResponse.success(responses);
        } catch (Exception e) {
            log.error("Get all agents failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * 获取智能体详情（包含工具配置）
     */
    @GetMapping("/{agentId}")
    public ApiResponse<AgentResponse> getAgent(@PathVariable Long agentId) {
        try {
            Agent agent = agentService.getAgentById(agentId);

            // 获取工具详情
            List<AgentToolDetail> toolDetails = agentToolService.getAgentToolDetails(agentId);

            // 构建响应
            AgentResponse response = AgentResponse.fromAgentWithToolDetails(agent, toolDetails);

            return ApiResponse.success(response);
        } catch (Exception e) {
            log.error("Get agent failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }

    /**
     * AI优化提示词
     */
    @PostMapping("/optimize-prompt")
    public ApiResponse<Map<String, String>> optimizePrompt(@RequestBody Map<String, String> request) {
        try {
            String prompt = request.get("prompt");
            if (prompt == null || prompt.isBlank()) {
                return ApiResponse.error("Prompt is required");
            }

            // 获取任意可用LLM Provider
            List<LLMProvider> providers = providerMapper.selectList(new QueryWrapper<>());
            if (providers.isEmpty()) {
                return ApiResponse.error("No LLM provider configured");
            }

            LLMProvider provider = providers.get(0);

            ChatModel chatModel = chatModelFactory.createChatModel(provider.getId());

            String optimizeInstruction = """
                You are an expert prompt engineer. Optimize the following system prompt to make it clearer, more specific, and more effective.
                Keep the core intent but improve clarity, structure, and specificity.
                Return ONLY the optimized prompt text, nothing else.

                Original prompt:
                %s
                """.formatted(prompt);

            String optimized = chatModel.call(new Prompt(optimizeInstruction))
                .getResult()
                .getOutput()
                .getText();

            return ApiResponse.success(Map.of("optimized", optimized));
        } catch (Exception e) {
            log.error("Optimize prompt failed", e);
            return ApiResponse.error(e.getMessage());
        }
    }
}
