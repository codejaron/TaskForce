package com.agent.domain.team.lead.tools;

import com.agent.domain.team.model.Team;
import com.agent.domain.team.model.TeamMember;
import com.agent.domain.team.service.TeamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 列出团队成员工具
 * Lead 使用此工具查看所有团队成员状态
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListTeammatesTool implements ToolCallback {

    private final TeamService teamService;
    private final ObjectMapper objectMapper;

    @Override
    public ToolDefinition getToolDefinition() {
        String inputSchema = """
            {
              "type": "object",
              "properties": {}
            }
            """;

        return ToolDefinition.builder()
                .name("list_teammates")
                .description("列出当前团队的所有成员（包括 Lead 和其他 Worker）及其状态")
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        try {
            String sessionId = extractSessionId(toolContext);
            Team team = teamService.getTeamBySessionId(sessionId);

            if (team == null) {
                return "No team found for this session";
            }

            List<TeamMember> members = team.getMembers();
            log.info("[ListTeammatesTool] Listed {} teammates for session: {}", members.size(), sessionId);

            if (members.isEmpty()) {
                return "No team members found";
            }

            StringBuilder result = new StringBuilder();
            result.append(String.format("Team: %s\n", team.getTeamId()));
            result.append(String.format("Lead: %s\n", team.getLeadInstanceId()));
            result.append(String.format("Status: %s\n", team.getStatus()));
            result.append(String.format("Found %d members:\n\n", members.size()));

            for (TeamMember member : members) {
                result.append(String.format("Instance ID: %s\n", member.getInstanceId()));
                result.append(String.format("  Name: %s\n", member.getName()));
                result.append(String.format("  Role: %s\n", member.getRole()));
                result.append(String.format("  Status: %s\n", member.getStatus()));
                result.append("\n");
            }

            return result.toString();

        } catch (Exception e) {
            log.error("[ListTeammatesTool] Failed to list teammates", e);
            return "Error listing teammates: " + e.getMessage();
        }
    }

    private String extractSessionId(ToolContext toolContext) {
        if (toolContext != null && toolContext.getContext() != null) {
            Object sessionId = toolContext.getContext().get("sessionId");
            if (sessionId != null) {
                return sessionId.toString();
            }
        }
        throw new IllegalArgumentException("sessionId not found in tool context");
    }
}
