package com.agent.infrastructure.memory;

import com.agent.infrastructure.persistence.entity.Message;
import com.agent.service.MessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据库支持的 ChatMemory 实现
 * 桥接 MessageService，让历史消息直接读写数据库
 */
@Slf4j
@Component
public class DbChatMemory implements ChatMemory {

    private final MessageService messageService;

    @Value("${chat.history.max-messages:20}")
    private int maxHistoryMessages;

    public DbChatMemory(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public void add(String conversationId, List<org.springframework.ai.chat.messages.Message> messages) {
        // 将 Spring AI 的 Message 转换为数据库 Message 实体并存储
        for (org.springframework.ai.chat.messages.Message msg : messages) {
            Message dbMessage = new Message();
            dbMessage.setSessionId(conversationId);
            dbMessage.setContent(msg.getText());

            if (msg instanceof UserMessage) {
                dbMessage.setRole("user");
                dbMessage.setMessageType("CHAT_USER_INPUT");
            } else if (msg instanceof AssistantMessage) {
                dbMessage.setRole("assistant");
                dbMessage.setMessageType("CHAT_ASSISTANT_REPLY");
            } else {
                // 跳过其他类型（如 SystemMessage）
                continue;
            }

            dbMessage.setStatus("COMPLETED");

            // 增强去重逻辑：检查最近 5 条消息中是否已存在相同内容和角色的消息
            List<Message> recent = messageService.getRecentMessages(conversationId, 5);
            boolean isDuplicate = recent.stream()
                    .anyMatch(m -> m.getRole().equals(dbMessage.getRole())
                            && m.getContent().equals(dbMessage.getContent()));

            if (isDuplicate) {
                log.debug("[DbChatMemory] Skip duplicate message: sessionId={}, role={}, content={}",
                    conversationId, dbMessage.getRole(),
                    dbMessage.getContent().substring(0, Math.min(50, dbMessage.getContent().length())));
                continue;
            }

            // 跳过空的 assistant 消息（工具调用中间状态）
            if ("assistant".equals(dbMessage.getRole()) &&
                (dbMessage.getContent() == null || dbMessage.getContent().trim().isEmpty())) {
                log.debug("[DbChatMemory] Skip empty assistant message: sessionId={}", conversationId);
                continue;
            }

            messageService.saveMessage(dbMessage);
            log.debug("[DbChatMemory] Saved message: sessionId={}, role={}", conversationId, dbMessage.getRole());
        }
    }

    @Override
    public List<org.springframework.ai.chat.messages.Message> get(String conversationId) {
        // 从数据库加载最近 N 条消息
        List<Message> dbMessages = messageService.getRecentMessages(conversationId, maxHistoryMessages);

        // 转换为 Spring AI 的 Message 对象
        List<org.springframework.ai.chat.messages.Message> messages = new ArrayList<>();
        for (Message msg : dbMessages) {
            if ("user".equals(msg.getRole())) {
                messages.add(new UserMessage(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                messages.add(new AssistantMessage(msg.getContent()));
            }
        }

        log.debug("[DbChatMemory] Loaded {} messages from DB: sessionId={}", messages.size(), conversationId);
        return messages;
    }

    @Override
    public void clear(String conversationId) {
        messageService.deleteSessionMessages(conversationId);
        log.info("[DbChatMemory] Cleared messages: sessionId={}", conversationId);
    }
}
