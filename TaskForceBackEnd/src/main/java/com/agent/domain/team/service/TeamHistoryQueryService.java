package com.agent.domain.team.service;

import com.agent.api.dto.TeamHistoryMessageDTO;
import com.agent.api.dto.TeamHistoryToolCallDTO;
import com.agent.api.dto.TeamSessionHistoryDTO;
import com.agent.infrastructure.persistence.entity.Message;
import com.agent.infrastructure.persistence.entity.ToolCall;
import com.agent.infrastructure.persistence.mapper.MessageMapper;
import com.agent.infrastructure.persistence.mapper.ToolCallMapper;
import com.agent.service.SessionService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Team 历史查询服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamHistoryQueryService {

    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final MessageMapper messageMapper;
    private final ToolCallMapper toolCallMapper;
    private final SessionService sessionService;

    public TeamSessionHistoryDTO getHistory(String sessionId, Integer limit, String before) {
        int safeLimit = sanitizeLimit(limit);
        if (!isTeamSession(sessionId)) {
            return TeamSessionHistoryDTO.builder()
                    .messages(List.of())
                    .toolCalls(List.of())
                    .nextBefore(null)
                    .build();
        }

        LocalDateTime beforeCursor = parseBefore(before);

        List<Message> messageRows = queryMessages(sessionId, safeLimit, beforeCursor);
        List<ToolCall> toolRows = queryToolCalls(sessionId, safeLimit, beforeCursor);

        boolean hasMoreMessages = messageRows.size() >= safeLimit;
        boolean hasMoreToolCalls = toolRows.size() >= safeLimit;

        List<Message> messageAsc = new ArrayList<>(messageRows);
        messageAsc.sort(Comparator.comparing(Message::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo)));

        List<ToolCall> toolAsc = new ArrayList<>(toolRows);
        toolAsc.sort(Comparator.comparing(
                call -> call.getStartedAt() == null ? call.getCreatedAt() : call.getStartedAt(),
                Comparator.nullsLast(LocalDateTime::compareTo)
        ));

        List<TeamHistoryMessageDTO> messages = messageAsc.stream()
                .map(this::toMessageDto)
                .toList();

        List<TeamHistoryToolCallDTO> toolCalls = toolAsc.stream()
                .map(this::toToolCallDto)
                .toList();

        String nextBefore = resolveNextBefore(messageAsc, toolAsc, hasMoreMessages, hasMoreToolCalls);

        return TeamSessionHistoryDTO.builder()
                .messages(messages)
                .toolCalls(toolCalls)
                .nextBefore(nextBefore)
                .build();
    }

    private List<Message> queryMessages(String sessionId, int limit, LocalDateTime beforeCursor) {
        LambdaQueryWrapper<Message> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Message::getSessionId, sessionId)
                .likeRight(Message::getMessageType, "TEAM_");
        if (beforeCursor != null) {
            wrapper.lt(Message::getCreatedAt, beforeCursor);
        }
        wrapper.orderByDesc(Message::getCreatedAt)
                .last("LIMIT " + limit);
        return messageMapper.selectList(wrapper);
    }

    private List<ToolCall> queryToolCalls(String sessionId, int limit, LocalDateTime beforeCursor) {
        LambdaQueryWrapper<ToolCall> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ToolCall::getSessionId, sessionId);
        if (beforeCursor != null) {
            wrapper.lt(ToolCall::getStartedAt, beforeCursor);
        }
        wrapper.orderByDesc(ToolCall::getStartedAt)
                .orderByDesc(ToolCall::getCreatedAt)
                .last("LIMIT " + limit);
        return toolCallMapper.selectList(wrapper);
    }

    private TeamHistoryMessageDTO toMessageDto(Message message) {
        return TeamHistoryMessageDTO.builder()
                .id(message.getId())
                .role(message.getRole())
                .messageType(message.getMessageType())
                .agentName(message.getAgentName())
                .content(message.getContent())
                .createdAt(formatDateTime(message.getCreatedAt()))
                .build();
    }

    private TeamHistoryToolCallDTO toToolCallDto(ToolCall toolCall) {
        return TeamHistoryToolCallDTO.builder()
                .toolCallId(toolCall.getToolCallId())
                .stepId(toolCall.getStepId())
                .sequence(toolCall.getSequence())
                .instanceId(toolCall.getInstanceId())
                .roundId(toolCall.getRoundId())
                .toolName(toolCall.getToolName())
                .serverName(toolCall.getServerName())
                .toolArgs(toolCall.getToolArgs())
                .toolResult(toolCall.getToolResult())
                .status(toolCall.getStatus())
                .errorMessage(toolCall.getErrorMessage())
                .durationMs(toolCall.getDurationMs())
                .startedAt(formatDateTime(toolCall.getStartedAt()))
                .completedAt(formatDateTime(toolCall.getCompletedAt()))
                .syncStatus(toolCall.getSyncStatus())
                .syncError(toolCall.getSyncError())
                .syncedAt(formatDateTime(toolCall.getSyncedAt()))
                .build();
    }

    private String resolveNextBefore(List<Message> messages, List<ToolCall> toolCalls,
                                     boolean hasMoreMessages, boolean hasMoreToolCalls) {
        if (!hasMoreMessages && !hasMoreToolCalls) {
            return null;
        }

        List<LocalDateTime> candidates = new ArrayList<>();
        if (hasMoreMessages && !messages.isEmpty()) {
            candidates.add(messages.get(0).getCreatedAt());
        }
        if (hasMoreToolCalls && !toolCalls.isEmpty()) {
            ToolCall first = toolCalls.get(0);
            candidates.add(first.getStartedAt() == null ? first.getCreatedAt() : first.getStartedAt());
        }

        return candidates.stream()
                .filter(value -> value != null)
                .min(LocalDateTime::compareTo)
                .map(this::formatDateTime)
                .orElse(null);
    }

    private int sanitizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private LocalDateTime parseBefore(String before) {
        if (before == null || before.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(before);
        } catch (DateTimeParseException ignored) {
            // ignore
        }
        try {
            Instant instant = Instant.parse(before);
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } catch (DateTimeParseException e) {
            log.warn("[TeamHistoryQueryService] Invalid before cursor: {}", before);
            return null;
        }
    }

    private String formatDateTime(LocalDateTime dateTime) {
        return dateTime == null ? null : DATE_TIME_FORMATTER.format(dateTime);
    }

    private boolean isTeamSession(String sessionId) {
        try {
            return "TEAM".equalsIgnoreCase(sessionService.getSessionById(sessionId).getType());
        } catch (Exception e) {
            log.debug("[TeamHistoryQueryService] Session unavailable: sessionId={}", sessionId);
            return false;
        }
    }
}
