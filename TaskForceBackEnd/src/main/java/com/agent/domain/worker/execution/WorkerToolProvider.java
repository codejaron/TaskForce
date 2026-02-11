package com.agent.domain.worker.execution;

import com.agent.domain.team.lead.tools.ListTeammatesTool;
import com.agent.domain.worker.execution.tools.ClaimTaskTool;
import com.agent.domain.worker.execution.tools.CompleteTaskTool;
import com.agent.domain.worker.execution.tools.ReadInboxTool;
import com.agent.domain.worker.execution.tools.ReadStepOutputTool;
import com.agent.domain.worker.execution.tools.SendMessageTool;
import com.agent.domain.worker.execution.tools.WriteStepSummaryTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Worker 工具提供者
 * 注册 Worker 专属工具集
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkerToolProvider {

    private final SendMessageTool sendMessageTool;
    private final ReadInboxTool readInboxTool;
    private final ListTeammatesTool listTeammatesTool;
    private final ClaimTaskTool claimTaskTool;
    private final CompleteTaskTool completeTaskTool;
    private final ReadStepOutputTool readStepOutputTool;
    private final WriteStepSummaryTool writeStepSummaryTool;

    /**
     * 获取 Worker 专属工具列表
     */
    public List<ToolCallback> getWorkerTools() {
        List<ToolCallback> tools = new ArrayList<>();

        // 通信工具
        tools.add(sendMessageTool);
        tools.add(readInboxTool);
        tools.add(listTeammatesTool);

        // 任务工具
        tools.add(claimTaskTool);
        tools.add(completeTaskTool);

        // 上下文工具
        tools.add(readStepOutputTool);
        tools.add(writeStepSummaryTool);

        log.debug("[WorkerToolProvider] Registered {} worker tools", tools.size());

        return tools;
    }
}
