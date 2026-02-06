package com.agent.domain.orchestration.graph;

import com.agent.service.SessionService;
import com.agent.service.SessionStopService;
import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class AgentGraphRunner {

    private final CompiledGraph compiledAgentGraph;
    private final SessionStopService sessionStopService;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public AgentGraphRunner(CompiledGraph compiledAgentGraph,
                            SessionStopService sessionStopService,
                            SessionService sessionService, ObjectMapper objectMapper) {
        this.compiledAgentGraph = compiledAgentGraph;
        this.sessionStopService = sessionStopService;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    /**
     * 提交新任务
     */
    public Flux<ServerSentEvent<String>> submit(String sessionId, String requestId, String userInput) {
        log.info("[GraphRunner] Submit: sessionId={}, requestId={}", sessionId, requestId);

        // 检查会话状态
        try {
            var session = sessionService.getSessionById(sessionId);
            if ("PAUSED".equals(session.getStatus())) {
                log.warn("[GraphRunner] Session is paused, clearing stop flag: sessionId={}", sessionId);
                sessionStopService.clearStop(sessionId);
                // 不在这里更新状态，让 PlanningStartEvent 触发状态更新
            }
        } catch (Exception e) {
            log.warn("[GraphRunner] Failed to check session status: sessionId={}", sessionId, e);
        }

        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .build();

        Map<String, Object> input = new HashMap<>();
        input.put("sessionId", sessionId);
        input.put("requestId", requestId);
        input.put("userInput", userInput);

        return executeAndStream(input, config, sessionId);
    }

    /**
     * 恢复中断的任务（Human-in-the-loop）
     */
    public Flux<ServerSentEvent<String>> resume(String sessionId, String userAnswer) {
        log.info("[GraphRunner] Resume: sessionId={}", sessionId);

        // 检查会话状态并清除停止标志
        try {
            var session = sessionService.getSessionById(sessionId);
            if ("PAUSED".equals(session.getStatus())) {
                log.info("[GraphRunner] Clearing stop flag for paused session: sessionId={}", sessionId);
                sessionStopService.clearStop(sessionId);
                // 不在这里更新状态，让后续事件触发状态更新
            }
        } catch (Exception e) {
            log.warn("[GraphRunner] Failed to check session status: sessionId={}", sessionId, e);
        }

        RunnableConfig config = RunnableConfig.builder()
                .threadId(sessionId)
                .build();

        Map<String, Object> updates = Map.of("humanAnswer", userAnswer);

        try {
            compiledAgentGraph.updateState(config, updates, "human_feedback");
        } catch (Exception e) {
            log.error("[GraphRunner] Failed to update state: sessionId={}", sessionId, e);
            return Flux.error(e);
        }

        return executeAndStream(Map.of(), config, sessionId);
    }

    /**
     * 执行图并返回 SSE 流
     */
    private Flux<ServerSentEvent<String>> executeAndStream(Map<String, Object> input,
                                                           RunnableConfig config,
                                                           String sessionId) {

        Flux<NodeOutput> outputFlux = compiledAgentGraph.stream(input, config);

        return outputFlux
                .map(output -> {
                    String content = formatOutput(output);
                    return ServerSentEvent.builder(content).build();
                })
                .concatWith(Flux.just(ServerSentEvent.builder("{\"type\":\"complete\"}").build()))
                .doOnSubscribe(s -> log.info("[GraphRunner] Stream started: sessionId={}", sessionId))
                .doOnComplete(() -> log.info("[GraphRunner] Stream completed: sessionId={}", sessionId))
                .doOnError(e -> log.error("[GraphRunner] Stream error: sessionId={}", sessionId, e))
                .doOnCancel(() -> log.info("[GraphRunner] Client disconnected: sessionId={}", sessionId));
    }

    /**
     * 格式化输出
     */
    private String formatOutput(NodeOutput output) {
        String nodeName = output.node();

        try {
            if (output instanceof StreamingOutput streamingOutput) {
                Map<String, Object> streamingMap = new HashMap<>();
                streamingMap.put("type", "streaming");
                streamingMap.put("node", nodeName);
                streamingMap.put("chunk", streamingOutput.chunk());
                return objectMapper.writeValueAsString(streamingMap);
            } else {
                ObjectNode nodeOutput = objectMapper.createObjectNode();
                nodeOutput.put("type", "node_complete");
                nodeOutput.put("node", nodeName);

                if (output.state() != null && output.state().data() != null) {
                    nodeOutput.set("data", objectMapper.valueToTree(output.state().data()));
                } else {
                    log.warn("[GraphRunner] Output state or data is null for node: {}", nodeName);
                    nodeOutput.putObject("data");
                }

                return objectMapper.writeValueAsString(nodeOutput);
            }
        } catch (Exception e) {
            log.error("[GraphRunner] Failed to format output for node: {}", nodeName, e);
            return "{\"type\":\"error\",\"node\":\"" + nodeName + "\"}";
        }
    }
}