package com.agent.domain.worker.execution;

import com.agent.domain.team.lead.tools.ListTeammatesTool;
import com.agent.domain.worker.execution.tools.CompleteTaskTool;
import com.agent.domain.worker.execution.tools.ReadInboxTool;
import com.agent.domain.worker.execution.tools.SendMessageTool;
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
    // ClaimTaskTool 已废弃：Leader 分配模式下任务在 spawn 时已分配，不需要 Worker 自主认领
    // private final ClaimTaskTool claimTaskTool;
    private final CompleteTaskTool completeTaskTool;

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
        // tools.add(claimTaskTool); // 已废弃：Leader 分配模式下不需要
        tools.add(completeTaskTool);

        log.debug("[WorkerToolProvider] Registered {} worker tools", tools.size());

        return tools;
    }
}
