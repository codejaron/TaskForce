package com.agent.domain.execution;

import com.agent.McpAgentApplication;
import com.agent.domain.execution.model.AgentExecutionStatus;
import com.agent.domain.execution.service.AgentExecutionStateService;
import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.model.TaskStatus;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.service.InboxService;
import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.service.WorkerInstanceManager;
import com.agent.infrastructure.agent.CheckpointThreadIds;
import com.agent.infrastructure.event.events.InboxMessageEvent;
import com.agent.infrastructure.llm.ChatModelFactory;
import com.agent.infrastructure.mcp.RemoteMcpClient;
import com.agent.infrastructure.persistence.entity.Agent;
import com.agent.infrastructure.persistence.entity.LLMProvider;
import com.agent.infrastructure.persistence.entity.Session;
import com.agent.infrastructure.persistence.entity.SessionAgent;
import com.agent.infrastructure.persistence.entity.ToolCall;
import com.agent.infrastructure.persistence.mapper.AgentMapper;
import com.agent.infrastructure.persistence.mapper.LLMProviderMapper;
import com.agent.infrastructure.persistence.mapper.SessionAgentMapper;
import com.agent.infrastructure.persistence.mapper.SessionMapper;
import com.agent.infrastructure.persistence.mapper.ToolCallMapper;
import com.agent.infrastructure.persistence.redis.RedisWorkerInstanceRepository;
import com.agent.service.SessionExecutionTracker;
import com.agent.service.TeamOrchestrationService;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SpringBootTest(
        classes = McpAgentApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("local")
@Import(TeamLeadWorkerE2ESpringTest.TestOverrides.class)
@TestPropertySource(properties = {
        "spring.cloud.discovery.enabled=false",
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.discovery.register-enabled=false",
        "spring.main.allow-bean-definition-overriding=true",
        "app.proxy.enabled=false"
})
class TeamLeadWorkerE2ESpringTest {

    @Autowired
    private TeamOrchestrationService teamOrchestrationService;

    @Autowired
    private AgentMapper agentMapper;

    @Autowired
    private LLMProviderMapper llmProviderMapper;

    @Autowired
    private SessionMapper sessionMapper;

    @Autowired
    private SessionAgentMapper sessionAgentMapper;

    @Autowired
    private RedisWorkerInstanceRepository workerRepository;

    @Autowired
    private TaskBoardService taskBoardService;

    @Autowired
    private AgentExecutionStateService executionStateService;

    @Autowired
    private InboxService inboxService;

    @Autowired
    private WorkerInstanceManager workerInstanceManager;

    @Autowired
    private BaseCheckpointSaver checkpointSaver;

    @Autowired
    private ToolCallMapper toolCallMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ScriptedModelRegistry scriptedModelRegistry;

    @Autowired
    private InboxEventCollector inboxEventCollector;

    private String activeSessionId;
    private Long seededProviderId;
    private Long seededLeadAgentId;
    private Long seededWorkerAgentId;
    private Long seededWorkerAgentId2;

    @BeforeEach
    void setUp() {
        inboxEventCollector.clear();
        scriptedModelRegistry.reset();
        seededProviderId = null;
        seededLeadAgentId = null;
        seededWorkerAgentId = null;
        seededWorkerAgentId2 = null;
    }

    @AfterEach
    void tearDown() {
        if (activeSessionId != null) {
            teamOrchestrationService.stopSession(activeSessionId);
            cleanupSessionRows(activeSessionId);
            cleanupSessionRedis(activeSessionId);
            activeSessionId = null;
        }
        cleanupSeededAgents();
    }

    @Test
    void shouldResumeWorkerFromCheckpointAfterLeadReply() throws Exception {
        // 关键链路：Worker 等待 -> 无线程占用 -> Inbox 唤醒 -> Checkpoint 恢复继续执行
        String sessionId = "team-e2e-" + UUID.randomUUID();
        activeSessionId = sessionId;

        long workerAgentId = seedLeadAndWorker(sessionId);
        scriptedModelRegistry.prepareScenario(workerAgentId);

        teamOrchestrationService.startTeamSession(sessionId, "验证 Lead/Worker 的等待-唤醒-恢复链路");

        String workerInstanceId = awaitWorkerSpawn(sessionId, Duration.ofSeconds(15));
        assertNotNull(workerInstanceId, "worker should be spawned by lead");

        awaitCondition(
                Duration.ofSeconds(15),
                () -> executionStateService.getStatus(workerInstanceId) == AgentExecutionStatus.WAITING_REPLY,
                "worker never entered WAITING_REPLY"
        );

        assertFalse(workerInstanceManager.isRunning(workerInstanceId), "worker thread should be returned while waiting");
        assertEquals(0, currentWorkerExecutorActiveCount(), "worker executor should have no active threads while waiting");

        RunnableConfig workerThread = RunnableConfig.builder()
                .threadId(CheckpointThreadIds.workerThreadId(workerInstanceId))
                .build();
        assertTrue(checkpointSaver.get(workerThread).isPresent(), "checkpoint should exist while worker is waiting");

        inboxService.send(TeamMessage.builder()
                .from("test-driver")
                .to(sessionId + "_lead")
                .type("MESSAGE")
                .text("lead can reply to worker now")
                .build());

        awaitCondition(
                Duration.ofSeconds(40),
                () -> taskBoardService.getTask(sessionId, 1).getStatus() == TaskStatus.COMPLETED,
                "task should be completed after worker resume"
        );
        awaitCondition(
                Duration.ofSeconds(40),
                () -> executionStateService.getStatus(sessionId + "_lead") == AgentExecutionStatus.COMPLETED,
                "lead did not reach COMPLETED"
        );

        Task task = taskBoardService.getTask(sessionId, 1);
        assertEquals(TaskStatus.COMPLETED, task.getStatus(), "task should be completed at the end");

        // 避免“刚 complete_task 就被 shutdown”触发中断竞态，给执行链路一个收口窗口
        Thread.sleep(1500);
        assertTrue(workerInstanceManager.shutdown(workerInstanceId), "worker should be shutdown successfully");
        awaitCondition(
                Duration.ofSeconds(15),
                () -> executionStateService.getStatus(workerInstanceId) == AgentExecutionStatus.COMPLETED,
                "worker should reach COMPLETED after shutdown"
        );
        awaitCondition(
                Duration.ofSeconds(15),
                () -> workerRepository.findById(workerInstanceId)
                        .map(WorkerInstance::isShutdown)
                        .orElse(false),
                "worker instance did not transition to SHUTDOWN"
        );

        awaitCondition(
                Duration.ofSeconds(15),
                () -> checkpointSaver.get(workerThread).isEmpty(),
                "worker checkpoint should be released after completion"
        );

        PromptSnapshot resumedCall = scriptedModelRegistry.workerModel()
                .firstCallContaining("From team-lead")
                .orElseThrow(() -> new AssertionError("worker resume call with lead reply not observed"));

        assertTrue(
                resumedCall.joined().contains("Found 1 running workers"),
                "resumed prompt should include previous tool result from checkpoint"
        );
        assertTrue(
                resumedCall.joined().contains("waiting for reply"),
                "resumed prompt should include prior send_message tool result from checkpoint"
        );

        Set<String> inboxTargets = inboxEventCollector.events().stream()
                .map(InboxMessageEvent::getTo)
                .collect(Collectors.toSet());
        assertTrue(inboxTargets.contains(sessionId + "_lead"), "lead inbox wakeup event should be published");
        assertTrue(inboxTargets.contains(workerInstanceId), "worker inbox wakeup event should be published");

        List<ToolCall> persistedToolCalls = toolCallMapper.selectBySessionId(sessionId);
        assertTrue(persistedToolCalls.stream().anyMatch(t -> "send_message".equals(t.getToolName())));
        assertTrue(persistedToolCalls.stream().anyMatch(t -> "complete_task".equals(t.getToolName())));
    }

    @Test
    void shouldRecoverTwoWaitingWorkersAfterSimulatedRestart() throws Exception {
        // 并发覆盖：Lead 同时 spawn 两个 Worker；两个 Worker 都进入 WAITING_REPLY
        // 恢复覆盖：清空内存态（模拟重启后 only Redis），通过 inbox 触发两者恢复执行
        String sessionId = "te2em-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        activeSessionId = sessionId;

        WorkerAgentIds workerAgentIds = seedLeadAndTwoWorkers(sessionId);
        scriptedModelRegistry.prepareTwoWorkerScenario(workerAgentIds.workerAAgentId(), workerAgentIds.workerBAgentId());

        teamOrchestrationService.startTeamSession(sessionId, "验证多 Worker 并发等待与重启后恢复");

        List<WorkerInstance> workers = awaitWorkerSpawnCount(sessionId, 2, Duration.ofSeconds(20));
        assertEquals(2, workers.size(), "lead should spawn two workers");
        String worker1 = workers.get(0).getInstanceId();
        String worker2 = workers.get(1).getInstanceId();

        awaitCondition(
                Duration.ofSeconds(20),
                () -> executionStateService.getStatus(worker1) == AgentExecutionStatus.WAITING_REPLY
                        && executionStateService.getStatus(worker2) == AgentExecutionStatus.WAITING_REPLY,
                "both workers should enter WAITING_REPLY"
        );

        assertFalse(workerInstanceManager.isRunning(worker1), "worker1 thread should be returned while waiting");
        assertFalse(workerInstanceManager.isRunning(worker2), "worker2 thread should be returned while waiting");
        assertEquals(0, currentWorkerExecutorActiveCount(), "worker executor should have no active threads while both waiting");

        RunnableConfig worker1Thread = RunnableConfig.builder()
                .threadId(CheckpointThreadIds.workerThreadId(worker1))
                .build();
        RunnableConfig worker2Thread = RunnableConfig.builder()
                .threadId(CheckpointThreadIds.workerThreadId(worker2))
                .build();
        assertTrue(checkpointSaver.get(worker1Thread).isPresent(), "worker1 checkpoint should exist while waiting");
        assertTrue(checkpointSaver.get(worker2Thread).isPresent(), "worker2 checkpoint should exist while waiting");

        // 模拟“进程重启”：内存中的运行态清空，仅保留 Redis 状态机与 checkpoint
        clearRunningLoopsForRestartSimulation();

        inboxService.send(TeamMessage.builder()
                .from("team-lead")
                .to(worker1)
                .type("INSTRUCTION")
                .text("Lead reply: continue and finish")
                .build());
        inboxService.send(TeamMessage.builder()
                .from("team-lead")
                .to(worker2)
                .type("INSTRUCTION")
                .text("Lead reply: continue and finish")
                .build());

        awaitCondition(
                Duration.ofSeconds(40),
                () -> taskBoardService.getTask(sessionId, 1).getStatus() == TaskStatus.COMPLETED
                        && taskBoardService.getTask(sessionId, 2).getStatus() == TaskStatus.COMPLETED,
                "both worker tasks should be completed after wakeup"
        );

        Thread.sleep(1500);
        assertTrue(workerInstanceManager.shutdown(worker1), "worker1 should be shutdown successfully");
        assertTrue(workerInstanceManager.shutdown(worker2), "worker2 should be shutdown successfully");

        awaitCondition(
                Duration.ofSeconds(15),
                () -> executionStateService.getStatus(worker1) == AgentExecutionStatus.COMPLETED
                        && executionStateService.getStatus(worker2) == AgentExecutionStatus.COMPLETED,
                "both workers should reach COMPLETED after shutdown"
        );
        awaitCondition(
                Duration.ofSeconds(15),
                () -> checkpointSaver.get(worker1Thread).isEmpty() && checkpointSaver.get(worker2Thread).isEmpty(),
                "both checkpoints should be released after completion"
        );

        PromptSnapshot worker1ResumeCall = scriptedModelRegistry.workerAModel()
                .firstCallContaining("From team-lead")
                .orElseThrow(() -> new AssertionError("worker1 resume call not observed"));
        PromptSnapshot worker2ResumeCall = scriptedModelRegistry.workerBModel()
                .firstCallContaining("From team-lead")
                .orElseThrow(() -> new AssertionError("worker2 resume call not observed"));
        assertTrue(worker1ResumeCall.joined().contains("waiting for reply"));
        assertTrue(worker2ResumeCall.joined().contains("waiting for reply"));

        Set<String> inboxTargets = inboxEventCollector.events().stream()
                .map(InboxMessageEvent::getTo)
                .collect(Collectors.toSet());
        assertTrue(inboxTargets.contains(worker1), "worker1 inbox wakeup event should be published");
        assertTrue(inboxTargets.contains(worker2), "worker2 inbox wakeup event should be published");
    }

    private long seedLeadAndWorker(String sessionId) {
        LLMProvider provider = LLMProvider.builder()
                .name("e2e-fake-provider-" + UUID.randomUUID())
                .type("CUSTOM")
                .baseUrl("http://localhost/fake")
                .apiKey("fake-key")
                .build();
        llmProviderMapper.insert(provider);
        seededProviderId = provider.getId();

        Agent lead = Agent.builder()
                .providerId(seededProviderId)
                .name("Lead-Test")
                .systemPrompt("team lead")
                .model("lead-script")
                .temperature(new BigDecimal("0.10"))
                .maxTokens(512)
                .description("planner")
                .roleType("PLANNER")
                .build();
        agentMapper.insert(lead);
        seededLeadAgentId = lead.getId();

        Agent worker = Agent.builder()
                .providerId(seededProviderId)
                .name("Worker-Test")
                .systemPrompt("worker")
                .model("worker-script")
                .temperature(new BigDecimal("0.10"))
                .maxTokens(512)
                .description("executor")
                .roleType("WORKER")
                .build();
        agentMapper.insert(worker);
        seededWorkerAgentId = worker.getId();

        Session session = Session.builder()
                .id(sessionId)
                .name("Team E2E")
                .type("TEAM")
                .status("RUNNING")
                .maxRounds(20)
                .currentRound(0)
                .build();
        sessionMapper.insert(session);

        SessionAgent sessionAgent = SessionAgent.builder()
                .sessionId(sessionId)
                .agentId(worker.getId())
                .isAdmin(false)
                .joinOrder(1)
                .build();
        sessionAgentMapper.insert(sessionAgent);

        return worker.getId();
    }

    private WorkerAgentIds seedLeadAndTwoWorkers(String sessionId) {
        LLMProvider provider = LLMProvider.builder()
                .name("e2e-fake-provider-" + UUID.randomUUID())
                .type("CUSTOM")
                .baseUrl("http://localhost/fake")
                .apiKey("fake-key")
                .build();
        llmProviderMapper.insert(provider);
        seededProviderId = provider.getId();

        Agent lead = Agent.builder()
                .providerId(seededProviderId)
                .name("Lead-Test-Multi")
                .systemPrompt("team lead")
                .model("lead-script")
                .temperature(new BigDecimal("0.10"))
                .maxTokens(512)
                .description("planner")
                .roleType("PLANNER")
                .build();
        agentMapper.insert(lead);
        seededLeadAgentId = lead.getId();

        Agent workerA = Agent.builder()
                .providerId(seededProviderId)
                .name("Worker-A")
                .systemPrompt("worker")
                .model("worker-script-a")
                .temperature(new BigDecimal("0.10"))
                .maxTokens(512)
                .description("executor-a")
                .roleType("WORKER")
                .build();
        agentMapper.insert(workerA);
        seededWorkerAgentId = workerA.getId();

        Agent workerB = Agent.builder()
                .providerId(seededProviderId)
                .name("Worker-B")
                .systemPrompt("worker")
                .model("worker-script-b")
                .temperature(new BigDecimal("0.10"))
                .maxTokens(512)
                .description("executor-b")
                .roleType("WORKER")
                .build();
        agentMapper.insert(workerB);
        seededWorkerAgentId2 = workerB.getId();

        Session session = Session.builder()
                .id(sessionId)
                .name("Team E2E Multi")
                .type("TEAM")
                .status("RUNNING")
                .maxRounds(30)
                .currentRound(0)
                .build();
        sessionMapper.insert(session);

        SessionAgent sessionAgentA = SessionAgent.builder()
                .sessionId(sessionId)
                .agentId(workerA.getId())
                .isAdmin(false)
                .joinOrder(1)
                .build();
        sessionAgentMapper.insert(sessionAgentA);

        SessionAgent sessionAgentB = SessionAgent.builder()
                .sessionId(sessionId)
                .agentId(workerB.getId())
                .isAdmin(false)
                .joinOrder(2)
                .build();
        sessionAgentMapper.insert(sessionAgentB);

        return new WorkerAgentIds(workerA.getId(), workerB.getId());
    }

    private String awaitWorkerSpawn(String sessionId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<WorkerInstance> workers = workerRepository.findBySessionId(sessionId);
            if (!workers.isEmpty()) {
                return workers.get(0).getInstanceId();
            }
            Thread.sleep(100);
        }
        return null;
    }

    private List<WorkerInstance> awaitWorkerSpawnCount(String sessionId, int expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<WorkerInstance> workers = workerRepository.findBySessionId(sessionId).stream()
                    .sorted((a, b) -> Integer.compare(a.getWorkerId(), b.getWorkerId()))
                    .toList();
            if (workers.size() >= expected) {
                return workers;
            }
            Thread.sleep(100);
        }
        return List.of();
    }

    private void awaitCondition(Duration timeout, BooleanSupplier condition, String timeoutMessage) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        fail(timeoutMessage);
    }

    private int currentWorkerExecutorActiveCount() {
        try {
            var field = WorkerInstanceManager.class.getDeclaredField("workerExecutor");
            field.setAccessible(true);
            ExecutorService executorService = (ExecutorService) field.get(workerInstanceManager);
            if (executorService instanceof ThreadPoolExecutor threadPoolExecutor) {
                return threadPoolExecutor.getActiveCount();
            }
            return -1;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to inspect worker executor", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void clearRunningLoopsForRestartSimulation() {
        try {
            var field = WorkerInstanceManager.class.getDeclaredField("runningLoops");
            field.setAccessible(true);
            ((Map<String, ?>) field.get(workerInstanceManager)).clear();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to clear in-memory running loops", e);
        }
    }

    private void cleanupSessionRows(String sessionId) {
        toolCallMapper.delete(new LambdaQueryWrapper<ToolCall>()
                .eq(ToolCall::getSessionId, sessionId));
        sessionAgentMapper.delete(new LambdaQueryWrapper<SessionAgent>()
                .eq(SessionAgent::getSessionId, sessionId));
        sessionMapper.deleteById(sessionId);
    }

    private record WorkerAgentIds(long workerAAgentId, long workerBAgentId) {
    }

    private void cleanupSeededAgents() {
        if (seededLeadAgentId != null) {
            agentMapper.deleteById(seededLeadAgentId);
        }
        if (seededWorkerAgentId != null) {
            agentMapper.deleteById(seededWorkerAgentId);
        }
        if (seededWorkerAgentId2 != null) {
            agentMapper.deleteById(seededWorkerAgentId2);
        }
        if (seededProviderId != null) {
            llmProviderMapper.deleteById(seededProviderId);
        }
    }

    private void cleanupSessionRedis(String sessionId) {
        deleteByPattern("inbox:" + sessionId + ":*");
        deleteByPattern("worker:" + sessionId + ":*");
        deleteByPattern("taskboard:" + sessionId);
        deleteByPattern("agent:state:" + sessionId + "*");
        deleteByPattern("agent:wait-intent:" + sessionId + "*");
        deleteByPattern("sse:stream:" + sessionId + "*");
        deleteByPattern("sse:notify:" + sessionId + "*");
        deleteByPattern("sse:worker:stream:" + sessionId + "*");
        deleteByPattern("sse:worker:notify:" + sessionId + "*");

        String teamIndexKey = "team:session:" + sessionId;
        String teamId = redisTemplate.opsForValue().get(teamIndexKey);
        if (teamId != null && !teamId.isBlank()) {
            redisTemplate.delete("team:" + teamId);
        }
        redisTemplate.delete(teamIndexKey);
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @TestConfiguration
    static class TestOverrides {

        @Bean
        ScriptedModelRegistry scriptedModelRegistry() {
            return new ScriptedModelRegistry();
        }

        @Bean
        InboxEventCollector inboxEventCollector() {
            return new InboxEventCollector();
        }

        @Bean
        @Primary
        ChatModelFactory chatModelFactory(ScriptedModelRegistry scriptedModelRegistry) {
            return new ChatModelFactory(null, null, null, ObservationRegistry.NOOP) {
                @Override
                public ChatModel createChatModel(Long providerId) {
                    return scriptedModelRegistry.resolve(null);
                }

                @Override
                public ChatModel createChatModel(Long providerId, String overrideModel) {
                    return scriptedModelRegistry.resolve(overrideModel);
                }

                @Override
                public void evictCache(Long providerId) {
                    // no-op in tests
                }

                @Override
                public void evictAllCache() {
                    // no-op in tests
                }
            };
        }

        @Bean
        @Primary
        RemoteMcpClient remoteMcpClient(ObjectMapperHolder objectMapperHolder,
                                        LoadBalancerClient loadBalancerClient,
                                        SessionExecutionTracker executionTracker) {
            return new RemoteMcpClient(objectMapperHolder.objectMapper(), loadBalancerClient, executionTracker) {
                @Override
                public List<com.agent.common.dto.ToolInfo> listTools() {
                    return List.of();
                }

                @Override
                public ToolCallback[] getToolCallbacks(List<String> toolIds) {
                    return new ToolCallback[0];
                }
            };
        }

        @Bean
        ObjectMapperHolder objectMapperHolder(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            return new ObjectMapperHolder(objectMapper);
        }
    }

    static final class ObjectMapperHolder {
        private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

        ObjectMapperHolder(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
        }

        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return objectMapper;
        }
    }

    static final class InboxEventCollector {
        private final List<InboxMessageEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void onInboxMessage(InboxMessageEvent event) {
            events.add(event);
        }

        List<InboxMessageEvent> events() {
            return List.copyOf(events);
        }

        void clear() {
            events.clear();
        }
    }

    static final class ScriptedModelRegistry {
        private final ScriptedChatModel leadModel = new ScriptedChatModel("lead");
        private final ScriptedChatModel workerModel = new ScriptedChatModel("worker");
        private final ScriptedChatModel workerModelA = new ScriptedChatModel("worker-a");
        private final ScriptedChatModel workerModelB = new ScriptedChatModel("worker-b");

        void reset() {
            leadModel.reset();
            workerModel.reset();
            workerModelA.reset();
            workerModelB.reset();
        }

        void prepareScenario(long workerAgentId) {
            AtomicInteger leadStage = new AtomicInteger(0);
            leadModel.setResponder(snapshot -> {
                String prompt = snapshot.joined();
                return switch (leadStage.get()) {
                    case 0 -> {
                        leadStage.compareAndSet(0, 1);
                        yield ScriptedChatModel.toolCall("create_task",
                                "{\"subject\":\"Collect data\",\"description\":\"Gather evidence for e2e test\",\"blockedBy\":[]}");
                    }
                    case 1 -> {
                        leadStage.compareAndSet(1, 2);
                        yield ScriptedChatModel.toolCall("spawn_worker",
                                "{\"name\":\"worker-e2e\",\"agentId\":\"" + workerAgentId + "\",\"assignedTaskId\":1}");
                    }
                    case 2 -> {
                        if (prompt.contains("lead can reply to worker now")
                                && prompt.contains("Need a lead confirmation before finishing")) {
                            leadStage.compareAndSet(2, 3);
                            yield ScriptedChatModel.toolCall("send_message",
                                    "{\"workerId\":1,\"content\":\"Lead reply: continue and finish\",\"messageType\":\"INSTRUCTION\",\"expectReply\":false}");
                        }
                        yield ScriptedChatModel.text("Lead waiting for explicit reply trigger.");
                    }
                    case 3 -> {
                        if (prompt.contains("Task #1 completed successfully")) {
                            leadStage.compareAndSet(3, 4);
                            yield ScriptedChatModel.text("Lead received final worker report.");
                        }
                        yield ScriptedChatModel.text("Lead waiting for worker completion.");
                    }
                    case 4 -> {
                        leadStage.compareAndSet(4, 5);
                        yield ScriptedChatModel.text("All tasks are completed.");
                    }
                    default -> ScriptedChatModel.text("Lead finished.");
                };
            });

            AtomicInteger workerStage = new AtomicInteger(0);
            workerModel.setResponder(snapshot -> {
                String prompt = snapshot.joined();
                return switch (workerStage.get()) {
                    case 0 -> {
                        workerStage.compareAndSet(0, 1);
                        yield ScriptedChatModel.toolCall("list_teammates", "{}");
                    }
                    case 1 -> {
                        workerStage.compareAndSet(1, 2);
                        yield ScriptedChatModel.toolCall("send_message",
                                "{\"workerId\":0,\"text\":\"Need a lead confirmation before finishing\",\"messageType\":\"MESSAGE\",\"expectReply\":true}");
                    }
                    case 2 -> {
                        if (prompt.contains("Lead reply: continue and finish")) {
                            workerStage.compareAndSet(2, 3);
                            yield ScriptedChatModel.toolCall("complete_task",
                                    "{\"taskId\":1,\"summary\":\"Task finished after lead confirmation.\"}");
                        }
                        yield ScriptedChatModel.text("Paused and waiting for lead confirmation.");
                    }
                    case 3 -> {
                        workerStage.compareAndSet(3, 4);
                        yield ScriptedChatModel.toolCall("send_message",
                                "{\"workerId\":0,\"text\":\"Task #1 completed successfully\",\"messageType\":\"MESSAGE\",\"expectReply\":false}");
                    }
                    case 4 -> {
                        workerStage.compareAndSet(4, 5);
                        yield ScriptedChatModel.text("Worker task completed.");
                    }
                    default -> ScriptedChatModel.text("Worker idle.");
                };
            });
        }

        void prepareTwoWorkerScenario(long workerAAgentId, long workerBAgentId) {
            AtomicInteger leadStage = new AtomicInteger(0);
            leadModel.setResponder(snapshot -> switch (leadStage.get()) {
                case 0 -> {
                    leadStage.compareAndSet(0, 1);
                    yield ScriptedChatModel.toolCall("create_task",
                            "{\"subject\":\"Collect data A\",\"description\":\"Gather evidence A\",\"blockedBy\":[]}");
                }
                case 1 -> {
                    leadStage.compareAndSet(1, 2);
                    yield ScriptedChatModel.toolCall("create_task",
                            "{\"subject\":\"Collect data B\",\"description\":\"Gather evidence B\",\"blockedBy\":[]}");
                }
                case 2 -> {
                    leadStage.compareAndSet(2, 3);
                    yield ScriptedChatModel.toolCall("spawn_worker",
                            "{\"name\":\"worker-a\",\"agentId\":\"" + workerAAgentId + "\",\"assignedTaskId\":1}");
                }
                case 3 -> {
                    leadStage.compareAndSet(3, 4);
                    yield ScriptedChatModel.toolCall("spawn_worker",
                            "{\"name\":\"worker-b\",\"agentId\":\"" + workerBAgentId + "\",\"assignedTaskId\":2}");
                }
                default -> ScriptedChatModel.text("Lead standby for worker updates.");
            });

            AtomicInteger workerAStage = new AtomicInteger(0);
            workerModelA.setResponder(snapshot -> {
                String prompt = snapshot.joined();
                return switch (workerAStage.get()) {
                    case 0 -> {
                        workerAStage.compareAndSet(0, 1);
                        yield ScriptedChatModel.toolCall("list_teammates", "{}");
                    }
                    case 1 -> {
                        workerAStage.compareAndSet(1, 2);
                        yield ScriptedChatModel.toolCall("send_message",
                                "{\"workerId\":0,\"text\":\"Need a lead confirmation before finishing (A)\",\"messageType\":\"MESSAGE\",\"expectReply\":true}");
                    }
                    case 2 -> {
                        if (prompt.contains("Lead reply: continue and finish")) {
                            workerAStage.compareAndSet(2, 3);
                            yield ScriptedChatModel.toolCall("complete_task",
                                    "{\"taskId\":1,\"summary\":\"Task A finished after lead confirmation.\"}");
                        }
                        yield ScriptedChatModel.text("Worker A waiting for lead confirmation.");
                    }
                    case 3 -> {
                        workerAStage.compareAndSet(3, 4);
                        yield ScriptedChatModel.toolCall("send_message",
                                "{\"workerId\":0,\"text\":\"Task #1 completed successfully\",\"messageType\":\"MESSAGE\",\"expectReply\":false}");
                    }
                    case 4 -> {
                        workerAStage.compareAndSet(4, 5);
                        yield ScriptedChatModel.text("Worker A completed.");
                    }
                    default -> ScriptedChatModel.text("Worker A idle.");
                };
            });

            AtomicInteger workerBStage = new AtomicInteger(0);
            workerModelB.setResponder(snapshot -> {
                String prompt = snapshot.joined();
                return switch (workerBStage.get()) {
                    case 0 -> {
                        workerBStage.compareAndSet(0, 1);
                        yield ScriptedChatModel.toolCall("list_teammates", "{}");
                    }
                    case 1 -> {
                        workerBStage.compareAndSet(1, 2);
                        yield ScriptedChatModel.toolCall("send_message",
                                "{\"workerId\":0,\"text\":\"Need a lead confirmation before finishing (B)\",\"messageType\":\"MESSAGE\",\"expectReply\":true}");
                    }
                    case 2 -> {
                        if (prompt.contains("Lead reply: continue and finish")) {
                            workerBStage.compareAndSet(2, 3);
                            yield ScriptedChatModel.toolCall("complete_task",
                                    "{\"taskId\":2,\"summary\":\"Task B finished after lead confirmation.\"}");
                        }
                        yield ScriptedChatModel.text("Worker B waiting for lead confirmation.");
                    }
                    case 3 -> {
                        workerBStage.compareAndSet(3, 4);
                        yield ScriptedChatModel.toolCall("send_message",
                                "{\"workerId\":0,\"text\":\"Task #2 completed successfully\",\"messageType\":\"MESSAGE\",\"expectReply\":false}");
                    }
                    case 4 -> {
                        workerBStage.compareAndSet(4, 5);
                        yield ScriptedChatModel.text("Worker B completed.");
                    }
                    default -> ScriptedChatModel.text("Worker B idle.");
                };
            });
        }

        ChatModel resolve(String overrideModel) {
            if ("lead-script".equalsIgnoreCase(overrideModel)) {
                return leadModel;
            }
            if ("worker-script".equalsIgnoreCase(overrideModel)) {
                return workerModel;
            }
            if ("worker-script-a".equalsIgnoreCase(overrideModel)) {
                return workerModelA;
            }
            if ("worker-script-b".equalsIgnoreCase(overrideModel)) {
                return workerModelB;
            }
            return leadModel;
        }

        ScriptedChatModel workerModel() {
            return workerModel;
        }

        ScriptedChatModel workerAModel() {
            return workerModelA;
        }

        ScriptedChatModel workerBModel() {
            return workerModelB;
        }
    }

    static final class ScriptedChatModel implements ChatModel {
        private final String name;
        private final AtomicInteger callCounter = new AtomicInteger(0);
        private final List<Function<PromptSnapshot, ChatResponse>> script = new CopyOnWriteArrayList<>();
        private volatile Function<PromptSnapshot, ChatResponse> responder;
        private final List<PromptSnapshot> seenPrompts = new CopyOnWriteArrayList<>();

        ScriptedChatModel(String name) {
            this.name = name;
        }

        void reset() {
            script.clear();
            responder = null;
            seenPrompts.clear();
            callCounter.set(0);
        }

        void setScript(List<Function<PromptSnapshot, ChatResponse>> steps) {
            reset();
            script.addAll(steps);
        }

        void setResponder(Function<PromptSnapshot, ChatResponse> responder) {
            reset();
            this.responder = responder;
        }

        Optional<PromptSnapshot> firstCallContaining(String text) {
            return seenPrompts.stream()
                    .filter(snapshot -> snapshot.joined().contains(text))
                    .findFirst();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            int index = callCounter.getAndIncrement();
            PromptSnapshot snapshot = PromptSnapshot.from(index, prompt);
            seenPrompts.add(snapshot);

            if (responder != null) {
                return responder.apply(snapshot);
            }

            if (index < script.size()) {
                return script.get(index).apply(snapshot);
            }

            return text("script-exhausted-" + name + "-" + index);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }

        public OpenAiChatOptions getDefaultOptions() {
            return OpenAiChatOptions.builder()
                    .model("fake-" + name)
                    .temperature(0.0)
                    .build();
        }

        static ChatResponse text(String text) {
            AssistantMessage message = new AssistantMessage(text);
            return new ChatResponse(List.of(new Generation(message)));
        }

        static ChatResponse toolCall(String toolName, String arguments) {
            AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(
                    UUID.randomUUID().toString(),
                    "function",
                    toolName,
                    arguments
            );
            AssistantMessage message = AssistantMessage.builder()
                    .content("")
                    .properties(Map.of())
                    .toolCalls(List.of(toolCall))
                    .build();
            return new ChatResponse(List.of(new Generation(message)));
        }
    }

    record PromptSnapshot(int callIndex, List<String> lines, String joined) {
        static PromptSnapshot from(int callIndex, Prompt prompt) {
            List<String> rendered = new ArrayList<>();
            for (Message message : prompt.getInstructions()) {
                rendered.add(renderMessage(message));
            }
            return new PromptSnapshot(callIndex, rendered, String.join("\n", rendered));
        }

        private static String renderMessage(Message message) {
            StringBuilder builder = new StringBuilder();
            builder.append(message.getMessageType()).append(": ");
            if (message instanceof AbstractMessage abstractMessage) {
                String content = abstractMessage.getText();
                if (content != null) {
                    builder.append(content.replace("\n", "\\n"));
                }
            }

            if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                String toolNames = assistantMessage.getToolCalls().stream()
                        .map(AssistantMessage.ToolCall::name)
                        .collect(Collectors.joining(","));
                builder.append(" | toolCalls=").append(toolNames);
            }

            if (message instanceof ToolResponseMessage toolResponseMessage) {
                String responses = toolResponseMessage.getResponses().stream()
                        .map(response -> response.name() + ":" + response.responseData())
                        .collect(Collectors.joining(" || "));
                builder.append(" | toolResponses=").append(responses.replace("\n", "\\n"));
            }

            return builder.toString();
        }
    }

}
