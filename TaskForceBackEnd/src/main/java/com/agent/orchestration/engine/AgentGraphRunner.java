package com.agent.orchestration.engine;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
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

    public AgentGraphRunner(CompiledGraph compiledAgentGraph) {
        this.compiledAgentGraph = compiledAgentGraph;
    }

    /**
     * 提交新任务
     */
    public Flux<ServerSentEvent<String>> submit(String sessionId, String requestId, String userInput) {
        log.info("[GraphRunner] Submit: sessionId={}, requestId={}", sessionId, requestId);

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

        if (output instanceof StreamingOutput streamingOutput) {
            // 流式 token
            Map<String, Object> streamingMap = new HashMap<>();
            streamingMap.put("type", "streaming");
            streamingMap.put("node", nodeName);
            streamingMap.put("chunk", streamingOutput.chunk());
            return JSON.toJSONString(streamingMap);
        } else {
            // 节点完成输出
            JSONObject nodeOutput = new JSONObject();
            nodeOutput.put("type", "node_complete");
            nodeOutput.put("node", nodeName);
            
            // 添加空指针检查
            if (output.state() != null && output.state().data() != null) {
                nodeOutput.put("data", output.state().data());
            } else {
                log.warn("[GraphRunner] Output state or data is null for node: {}", nodeName);
                nodeOutput.put("data", new HashMap<>());
            }
            
            return nodeOutput.toJSONString();
        }
    }
}