package com.agent.domain.orchestration.graph.config;


import com.agent.domain.orchestration.graph.dispatcher.PlannerDispatcher;
import com.agent.domain.orchestration.graph.dispatcher.ReplannerDispatcher;
import com.agent.domain.orchestration.graph.dispatcher.WorkerDispatcher;
import com.agent.domain.orchestration.graph.node.HumanFeedbackNode;
import com.agent.domain.orchestration.graph.node.PlannerNode;
import com.agent.domain.orchestration.graph.node.ReplannerNode;
import com.agent.domain.orchestration.graph.node.WorkerNode;
import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class AgentGraphConfiguration {

    @Bean
    public StateGraph agentGraph(
            PlannerNode plannerNode,
            WorkerNode workerNode,
            ReplannerNode replannerNode,
            HumanFeedbackNode humanFeedbackNode) throws GraphStateException {

        // 状态字段定义
        KeyStrategyFactory keyStrategyFactory = () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            strategies.put("sessionId", new ReplaceStrategy());
            strategies.put("requestId", new ReplaceStrategy());
            strategies.put("userInput", new ReplaceStrategy());
            strategies.put("currentStepIndex", new ReplaceStrategy());
            strategies.put("nextAction", new ReplaceStrategy());
            strategies.put("clarifyQuestion", new ReplaceStrategy());
            strategies.put("humanAnswer", new ReplaceStrategy());
            return strategies;
        };

        StateGraph graph = new StateGraph("agent-workflow", keyStrategyFactory)
                .addNode("planner", node_async(plannerNode))
                .addNode("worker", node_async(workerNode))
                .addNode("replanner", node_async(replannerNode))
                .addNode("human_feedback", node_async(humanFeedbackNode))
                .addEdge(START, "planner")
                .addConditionalEdges("planner",
                        edge_async(new PlannerDispatcher()),
                        Map.of(
                                "execute", "worker",
                                "clarify", "human_feedback",
                                "cannot_plan", END
                        ))
                .addEdge("human_feedback", "planner")
                .addConditionalEdges("worker",
                        edge_async(new WorkerDispatcher()),
                        Map.of(
                                "continue", "worker",
                                "replan", "replanner",
                                "clarify", "human_feedback",
                                "complete", END
                        ))
                .addConditionalEdges("replanner",
                        edge_async(new ReplannerDispatcher()),
                        Map.of(
                                "continue", "worker",
                                "clarify", "human_feedback",
                                "complete", END
                        ));

        GraphRepresentation uml = graph.getGraph(GraphRepresentation.Type.PLANTUML, "Agent Workflow");
        log.info("Graph PlantUML:\n{}", uml.content());

        return graph;
    }

    @Bean
    public RedisSaver redisSaver(RedissonClient redissonClient) {

        return RedisSaver.builder()
                .redisson(redissonClient)
                .build();
    }

    @Bean
    public CompiledGraph compiledAgentGraph(
            StateGraph agentGraph,
            RedisSaver redisSaver) throws GraphStateException {

        SaverConfig saverConfig = SaverConfig.builder()
                .register(redisSaver)
                .build();

        return agentGraph.compile(
                CompileConfig.builder()
                        .saverConfig(saverConfig)
                        .interruptBefore("human_feedback")
                        .build()
        );
    }
}
