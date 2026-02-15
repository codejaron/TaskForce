package com.agent.infrastructure.persistence.redis;

import com.agent.domain.team.model.TeamMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StringRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Duration;

/**
 * 收件箱 Redis 仓储实现
 * 键结构：inbox:stream:{sessionId}:{instanceId}，使用 Stream 结构
 * XADD 写入消息，XREADGROUP 读取消息并 ACK + DELETE
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class RedisInboxRepository {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String STREAM_KEY_PREFIX = "inbox:stream:";
    private static final String LEGACY_KEY_PREFIX = "inbox:";
    private static final String GROUP_PREFIX = "inbox:group:";
    private static final String FIELD_MESSAGE = "message";
    private static final String FIELD_TYPE = "type";
    private static final String FIELD_FROM = "from";
    private static final String FIELD_TO = "to";
    private static final String FIELD_TIMESTAMP = "timestamp";
    private static final int DEFAULT_READ_LIMIT = 200;
    private static final Duration STREAM_TTL = Duration.ofHours(12);

    private final Set<String> initializedGroups = ConcurrentHashMap.newKeySet();
    private final Map<String, Object> groupLocks = new ConcurrentHashMap<>();

    /**
     * 发送消息
     */
    public void send(String sessionId, String instanceId, TeamMessage message) {
        try {
            String streamKey = buildStreamKey(sessionId, instanceId);
            String json = objectMapper.writeValueAsString(message);

            Map<String, String> fields = new HashMap<>();
            fields.put(FIELD_MESSAGE, json);
            fields.put(FIELD_TYPE, message.getType() == null ? "" : message.getType());
            fields.put(FIELD_FROM, message.getFrom() == null ? "" : message.getFrom());
            fields.put(FIELD_TO, message.getTo() == null ? "" : message.getTo());
            fields.put(FIELD_TIMESTAMP, message.getTimestamp() == null ? "" : message.getTimestamp().toString());

            StringRecord record = StringRecord.of(fields).withStreamKey(streamKey);
            redisTemplate.opsForStream().add(record);
            redisTemplate.expire(streamKey, STREAM_TTL);
            log.debug("Sent message to inbox stream: {}", streamKey);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize TeamMessage", e);
            throw new RuntimeException("Failed to send message", e);
        }
    }

    /**
     * 读取收件箱（读取所有消息并清空）
     */
    public List<TeamMessage> readInbox(String sessionId, String instanceId) {
        return readInboxInternal(sessionId, instanceId, Duration.ZERO, DEFAULT_READ_LIMIT);
    }

    /**
     * 阻塞读取收件箱（最多阻塞 blockTimeout）
     */
    public List<TeamMessage> readInboxBlocking(String sessionId,
                                               String instanceId,
                                               Duration blockTimeout,
                                               int limit) {
        return readInboxInternal(sessionId, instanceId, blockTimeout, limit);
    }

    private List<TeamMessage> readInboxInternal(String sessionId,
                                                String instanceId,
                                                Duration blockTimeout,
                                                int limit) {
        try {
            String streamKey = buildStreamKey(sessionId, instanceId);
            String groupName = buildGroupName(sessionId, instanceId);
            String consumerName = buildConsumerName(instanceId);
            int batchSize = Math.max(1, Math.min(limit <= 0 ? DEFAULT_READ_LIMIT : limit, DEFAULT_READ_LIMIT));

            ensureGroup(streamKey, groupName);

            // 先读取当前消费者的 pending，避免中断后消息悬挂
            List<MapRecord<String, Object, Object>> pendingRecords = readFromGroup(
                    streamKey,
                    groupName,
                    consumerName,
                    ReadOffset.from("0"),
                    Duration.ZERO,
                    batchSize
            );
            if (!pendingRecords.isEmpty()) {
                return decodeAndAck(streamKey, groupName, pendingRecords);
            }

            List<MapRecord<String, Object, Object>> records = readFromGroup(
                    streamKey,
                    groupName,
                    consumerName,
                    ReadOffset.lastConsumed(),
                    blockTimeout,
                    batchSize
            );

            List<TeamMessage> messages = decodeAndAck(streamKey, groupName, records);
            log.debug("Read {} messages from inbox stream: {}", messages.size(), streamKey);
            return messages;
        } catch (Exception e) {
            log.error("Failed to read inbox: {}:{}", sessionId, instanceId, e);
            return new ArrayList<>();
        }
    }

    /**
     * 检查是否有新消息
     */
    public boolean hasNewMessages(String sessionId, String instanceId) {
        try {
            String key = buildStreamKey(sessionId, instanceId);
            Long size = redisTemplate.opsForStream().size(key);
            return size != null && size > 0;
        } catch (Exception e) {
            log.error("Failed to check new messages: {}:{}", sessionId, instanceId, e);
            return false;
        }
    }

    /**
     * 清空收件箱
     */
    public void clearInbox(String sessionId, String instanceId) {
        try {
            String streamKey = buildStreamKey(sessionId, instanceId);
            String legacyKey = buildLegacyKey(sessionId, instanceId);
            redisTemplate.delete(streamKey);
            redisTemplate.delete(legacyKey);
            initializedGroups.remove(buildGroupCacheKey(streamKey, buildGroupName(sessionId, instanceId)));
            log.debug("Cleared inbox: {}, legacy: {}", streamKey, legacyKey);
        } catch (Exception e) {
            log.error("Failed to clear inbox: {}:{}", sessionId, instanceId, e);
            throw new RuntimeException("Failed to clear inbox", e);
        }
    }

    /**
     * 确保 Consumer Group 存在（从 0 开始，保证早到消息可见）
     */
    private void ensureGroup(String streamKey, String groupName) {
        String groupCacheKey = buildGroupCacheKey(streamKey, groupName);
        if (initializedGroups.contains(groupCacheKey)) {
            return;
        }

        Object lock = groupLocks.computeIfAbsent(groupCacheKey, key -> new Object());
        synchronized (lock) {
            if (initializedGroups.contains(groupCacheKey)) {
                return;
            }

            RecordId bootstrapId = null;
            if (!Boolean.TRUE.equals(redisTemplate.hasKey(streamKey))) {
                Map<String, String> bootstrap = new HashMap<>();
                bootstrap.put("bootstrap", "1");
                bootstrapId = redisTemplate.opsForStream()
                        .add(StringRecord.of(bootstrap).withStreamKey(streamKey));
            }

            try {
                redisTemplate.opsForStream().createGroup(streamKey, ReadOffset.from("0"), groupName);
                log.debug("Created inbox stream group: stream={}, group={}", streamKey, groupName);
            } catch (Exception e) {
                if (!isGroupAlreadyExists(e)) {
                    throw new RuntimeException("Failed to create inbox stream group", e);
                }
            } finally {
                if (bootstrapId != null) {
                    redisTemplate.opsForStream().delete(streamKey, bootstrapId);
                }
                redisTemplate.expire(streamKey, STREAM_TTL);
            }

            initializedGroups.add(groupCacheKey);
        }
    }

    private List<MapRecord<String, Object, Object>> readFromGroup(String streamKey,
                                                                  String groupName,
                                                                  String consumerName,
                                                                  ReadOffset readOffset,
                                                                  Duration blockTimeout,
                                                                  int limit) {
        StreamReadOptions options = StreamReadOptions.empty().count(limit);
        if (blockTimeout != null && !blockTimeout.isZero() && !blockTimeout.isNegative()) {
            options = options.block(blockTimeout);
        }
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                Consumer.from(groupName, consumerName),
                options,
                StreamOffset.create(streamKey, readOffset)
        );
        return records == null ? List.of() : records;
    }

    private List<TeamMessage> decodeAndAck(String streamKey,
                                           String groupName,
                                           List<MapRecord<String, Object, Object>> records) {
        if (records == null || records.isEmpty()) {
            return List.of();
        }

        List<TeamMessage> messages = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            TeamMessage message = parseRecord(record);
            if (message != null) {
                messages.add(message);
            }

            try {
                redisTemplate.opsForStream().acknowledge(streamKey, groupName, record.getId());
                redisTemplate.opsForStream().delete(streamKey, record.getId());
            } catch (Exception ackErr) {
                log.warn("Failed to ack/delete inbox record: stream={}, recordId={}",
                        streamKey, record.getId(), ackErr);
            }
        }
        return messages;
    }

    private TeamMessage parseRecord(MapRecord<String, Object, Object> record) {
        if (record == null || record.getValue() == null) {
            return null;
        }
        try {
            Object json = record.getValue().get(FIELD_MESSAGE);
            if (json != null) {
                return objectMapper.readValue(json.toString(), TeamMessage.class);
            }
        } catch (Exception e) {
            log.warn("Failed to parse inbox record as TeamMessage JSON: recordId={}", record.getId(), e);
        }

        TeamMessage fallback = new TeamMessage();
        fallback.setFrom(safeString(record.getValue().get(FIELD_FROM)));
        fallback.setTo(safeString(record.getValue().get(FIELD_TO)));
        fallback.setType(safeString(record.getValue().get(FIELD_TYPE)));
        fallback.setText(safeString(record.getValue().get(FIELD_MESSAGE)));
        return fallback;
    }

    private String safeString(Object value) {
        return value == null ? "" : value.toString();
    }

    private boolean isGroupAlreadyExists(Exception e) {
        return e.getMessage() != null && e.getMessage().contains("BUSYGROUP");
    }

    private String buildGroupCacheKey(String streamKey, String groupName) {
        return streamKey + "|" + groupName;
    }

    private String buildStreamKey(String sessionId, String instanceId) {
        return STREAM_KEY_PREFIX + sessionId + ":" + instanceId;
    }

    private String buildLegacyKey(String sessionId, String instanceId) {
        return LEGACY_KEY_PREFIX + sessionId + ":" + instanceId;
    }

    private String buildGroupName(String sessionId, String instanceId) {
        return GROUP_PREFIX + sessionId + ":" + instanceId;
    }

    private String buildConsumerName(String instanceId) {
        return instanceId;
    }
}
