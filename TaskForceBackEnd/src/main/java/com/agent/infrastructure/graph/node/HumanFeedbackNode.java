package com.agent.infrastructure.graph.node;

import com.agent.application.orchestration.StateManager;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.SessionResumeEvent;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Human Feedback Node
 * 处理人工中断恢复
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HumanFeedbackNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) {
        log.info("human_feedback node is running.");

        String humanAnswer = state.value("humanAnswer", "");

        HashMap<String, Object> resultMap = new HashMap<>();
        String nextAction;

        if (humanAnswer == null || humanAnswer.isBlank()) {
            // 没有人工输入，可能是首次到达，等待输入
            nextAction = "waiting";
        } else {
            // 有人工输入，继续执行
            nextAction = "continue";
            resultMap.put("humanAnswer", humanAnswer);
        }

        resultMap.put("nextAction", nextAction);
        log.info("human_feedback node -> nextAction: {}", nextAction);

        return resultMap;
    }
}

