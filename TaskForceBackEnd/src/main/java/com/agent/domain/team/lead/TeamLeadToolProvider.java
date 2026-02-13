package com.agent.domain.team.lead;

import com.agent.domain.team.lead.tools.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Team Lead 工具提供者
 * 注册所有 Lead 专属工具
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamLeadToolProvider {

    private final CreateTaskTool createTaskTool;
    private final SpawnWorkerTool spawnWorkerTool;
    private final SendMessageTool sendMessageTool;
    private final BroadcastTool broadcastTool;
    private final ShutdownWorkerTool shutdownWorkerTool;
    private final ListTasksTool listTasksTool;
    private final ListTeammatesTool listTeammatesTool;
    private final ReplyUserTool replyUserTool;

    /**
     * 获取所有 Lead 工具
     *
     * @return 工具列表
     */
    public List<ToolCallback> getLeadTools() {
        List<ToolCallback> tools = new ArrayList<>();

        tools.add(createTaskTool);
        tools.add(spawnWorkerTool);
        tools.add(sendMessageTool);
        tools.add(broadcastTool);
        tools.add(shutdownWorkerTool);
        tools.add(listTasksTool);
        tools.add(listTeammatesTool);
        tools.add(replyUserTool);

        log.info("[TeamLeadToolProvider] Registered {} Lead tools", tools.size());

        return tools;
    }

    /**
     * 获取工具名称列表
     *
     * @return 工具名称列表
     */
    public List<String> getToolNames() {
        return List.of(
                "create_task",
                "spawn_worker",
                "send_message",
                "broadcast",
                "shutdown_worker",
                "list_tasks",
                "list_teammates",
                "reply_user"
        );
    }
}
