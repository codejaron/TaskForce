package com.agent.domain.execution;

import com.agent.api.controller.TeamController;
import com.agent.api.response.ApiResponse;
import com.agent.domain.execution.model.AgentExecutionStatus;
import com.agent.domain.execution.service.AgentExecutionStateService;
import com.agent.domain.execution.service.ExecutionWaitIntentService;
import com.agent.domain.execution.service.SessionOwnerService;
import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.model.TaskStatus;
import com.agent.domain.taskboard.repository.TaskBoardRepository;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.domain.team.context.TeamTaskContextService;
import com.agent.domain.team.lead.TeamLeadAgent;
import com.agent.domain.team.service.InboxService;
import com.agent.domain.team.service.TeamHistoryPersistenceService;
import com.agent.domain.team.service.TeamHistoryQueryService;
import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.repository.WorkerInstanceRepository;
import com.agent.domain.worker.service.WorkerInstanceManager;
import com.agent.domain.worker.service.WorkerRoundControlService;
import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.sandbox.SessionSandboxManager;
import com.agent.service.SessionExecutionTracker;
import com.agent.service.TeamOrchestrationService;
import com.agent.service.TeamOwnerForwardService;
import com.agent.service.TokenUsageService;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = TeamDistributedDeploymentSpringTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class TeamDistributedDeploymentSpringTest {

    @Value("${test.redis.host:${spring.data.redis.host:localhost}}")
    private String redisHost;

    @Value("${test.redis.port:${spring.data.redis.port:6379}}")
    private int redisPort;

    @Value("${test.redis.database:${spring.data.redis.database:0}}")
    private int redisDatabase;

    @Value("${test.redis.password:${spring.data.redis.password:}}")
    private String redisPassword;

    @org.springframework.beans.factory.annotation.Autowired
    private StringRedisTemplate redisTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void cleanupRedisState() {
        deleteByPattern("team:owner:dist-*");
        deleteByPattern("team:owner:lock:dist-*");
        deleteByPattern("worker:seq:dist-*");
        deleteByPattern("task:seq:dist-*");
        deleteByPattern("agent:state:dist-*");
    }

    @Test
    @Timeout(20)
    void concurrentOwnerAcquireShouldOnlySucceedOnce() throws Exception {
        assumeRedisAvailable();

        RedissonClient redissonClient = createRedissonClient();
        String sessionId = "dist-owner-" + UUID.randomUUID();
        SessionOwnerService nodeA = new SessionOwnerService(redissonClient, redisTemplate, "127.0.0.1:18080");
        SessionOwnerService nodeB = new SessionOwnerService(redissonClient, redisTemplate, "127.0.0.1:18081");

        try {
            CountDownLatch startGate = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            Future<Boolean> resultA = pool.submit(() -> {
                startGate.await();
                return nodeA.tryAcquireOwner(sessionId);
            });
            Future<Boolean> resultB = pool.submit(() -> {
                startGate.await();
                return nodeB.tryAcquireOwner(sessionId);
            });

            startGate.countDown();

            boolean acquiredA = resultA.get(10, TimeUnit.SECONDS);
            boolean acquiredB = resultB.get(10, TimeUnit.SECONDS);
            pool.shutdownNow();

            int successCount = (acquiredA ? 1 : 0) + (acquiredB ? 1 : 0);
            assertEquals(1, successCount, "exactly one node should acquire owner");

            String ownerNode = redisTemplate.opsForValue().get("team:owner:" + sessionId);
            assertNotNull(ownerNode, "owner should be written to redis");
            assertTrue(
                    ownerNode.equals("127.0.0.1:18080") || ownerNode.equals("127.0.0.1:18081"),
                    "owner should match one of the competing nodes"
            );
        } finally {
            nodeA.releaseOwner(sessionId);
            nodeB.releaseOwner(sessionId);
            nodeA.shutdown();
            nodeB.shutdown();
            redissonClient.shutdown();
        }
    }

    @Test
    @Timeout(25)
    void concurrentWorkerAndTaskSequenceShouldRemainUnique() throws Exception {
        assumeRedisAvailable();

        String workerSessionId = "dist-worker-seq-" + UUID.randomUUID();
        String taskSessionId = "dist-task-seq-" + UUID.randomUUID();
        int concurrency = 24;

        WorkerInstanceManager workerInstanceManager = buildWorkerManagerForSequenceTest();
        Method nextWorkerId = WorkerInstanceManager.class.getDeclaredMethod("nextWorkerId", String.class);
        nextWorkerId.setAccessible(true);

        List<Integer> workerIds = runConcurrently(concurrency, () -> invokeNextWorkerId(nextWorkerId, workerInstanceManager, workerSessionId));
        assertEquals(concurrency, Set.copyOf(workerIds).size(), "worker ids should be unique");

        InMemoryTaskBoardRepository taskRepository = new InMemoryTaskBoardRepository();
        EventBus eventBus = mock(EventBus.class);
        TaskBoardService taskBoardService = new TaskBoardService(taskRepository, redisTemplate, objectMapper, eventBus);

        List<Integer> taskIds = runConcurrently(concurrency, () ->
                taskBoardService.createTask(taskSessionId, "subject", "description", List.of()).getTaskId());
        assertEquals(concurrency, Set.copyOf(taskIds).size(), "task ids should be unique");
    }

    @Test
    @Timeout(20)
    void concurrentTransitionIfShouldOnlyAllowSingleCasWinner() throws Exception {
        assumeRedisAvailable();

        String instanceId = "dist-cas-" + UUID.randomUUID();
        AgentExecutionStateService stateService = new AgentExecutionStateService(redisTemplate, objectMapper);
        stateService.setStatus(instanceId, AgentExecutionStatus.IDLE, "seed");

        int concurrency = 24;
        List<Boolean> results = runConcurrently(concurrency, () ->
                stateService.transitionIf(
                        instanceId,
                        AgentExecutionStatus.IDLE,
                        AgentExecutionStatus.EXECUTING,
                        "parallel transition test"
                )
        );

        long successCount = results.stream().filter(Boolean::booleanValue).count();
        assertEquals(1, successCount, "only one transitionIf should succeed from same expected status");
        assertEquals(AgentExecutionStatus.EXECUTING, stateService.getStatus(instanceId));
    }

    @Test
    @Timeout(20)
    void nonOwnerStopAndLeadMessageShouldBeForwardedToOwnerNode() throws Exception {
        String sessionId = "dist-forward-" + UUID.randomUUID();
        AtomicBoolean leadMessageForwarded = new AtomicBoolean(false);
        AtomicBoolean stopForwarded = new AtomicBoolean(false);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/team/session/" + sessionId + "/lead/message", exchange -> {
            readRequestBody(exchange);
            leadMessageForwarded.set(true);
            writeJson(exchange, "{\"code\":200,\"message\":\"forwarded\",\"data\":null}");
        });
        server.createContext("/api/v2/team/session/" + sessionId + "/stop", exchange -> {
            readRequestBody(exchange);
            stopForwarded.set(true);
            writeJson(exchange, "{\"code\":200,\"message\":\"forwarded\",\"data\":null}");
        });
        server.start();

        String ownerNode = "127.0.0.1:" + server.getAddress().getPort();
        TeamOwnerForwardService forwardService = new TeamOwnerForwardService(WebClient.builder(), objectMapper);
        ReflectionTestUtils.setField(forwardService, "forwardTimeoutSeconds", 5L);

        SessionOwnerService sessionOwnerService = mock(SessionOwnerService.class);
        when(sessionOwnerService.getOwnerNode(sessionId)).thenReturn(Optional.of(ownerNode));
        when(sessionOwnerService.isCurrentNode(ownerNode)).thenReturn(false);

        TeamOrchestrationService orchestrationService = mock(TeamOrchestrationService.class);
        TeamController controller = new TeamController(
                orchestrationService,
                mock(WorkerInstanceManager.class),
                mock(TaskBoardService.class),
                mock(TeamHistoryPersistenceService.class),
                mock(TeamHistoryQueryService.class),
                mock(TeamLeadAgent.class),
                mock(AgentExecutionStateService.class),
                sessionOwnerService,
                forwardService,
                mock(EventBus.class),
                objectMapper
        );

        try {
            TeamController.MessageRequest messageRequest = new TeamController.MessageRequest();
            messageRequest.setMessage("forward me");

            ApiResponse<Void> messageResponse = controller.sendMessageToLead(sessionId, messageRequest);
            ApiResponse<Void> stopResponse = controller.stopTeamSession(sessionId);

            assertEquals(200, messageResponse.getCode());
            assertEquals(200, stopResponse.getCode());
            assertTrue(leadMessageForwarded.get(), "lead message should be forwarded to owner node");
            assertTrue(stopForwarded.get(), "stop should be forwarded to owner node");

            verify(orchestrationService, never()).sendMessageToLead(anyString(), anyString());
            verify(orchestrationService, never()).stopSession(anyString());
        } finally {
            server.stop(0);
        }
    }

    private WorkerInstanceManager buildWorkerManagerForSequenceTest() {
        WorkerInstanceRepository workerRepository = mock(WorkerInstanceRepository.class);
        when(workerRepository.findBySessionId(anyString())).thenReturn(List.of());
        return new WorkerInstanceManager(
                workerRepository,
                mock(TaskBoardService.class),
                mock(ReactAgentFactory.class),
                mock(EventBus.class),
                mock(SessionExecutionTracker.class),
                mock(InboxService.class),
                mock(TeamTaskContextService.class),
                mock(BaseCheckpointSaver.class),
                mock(AgentExecutionStateService.class),
                mock(ExecutionWaitIntentService.class),
                mock(WorkerRoundControlService.class),
                (SessionSandboxManager) null,
                mock(TokenUsageService.class),
                redisTemplate
        );
    }

    private Integer invokeNextWorkerId(Method method, WorkerInstanceManager manager, String sessionId) {
        try {
            return (Integer) method.invoke(manager, sessionId);
        } catch (Exception e) {
            throw new IllegalStateException("invoke nextWorkerId failed", e);
        }
    }

    private <T> List<T> runConcurrently(int concurrency, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(concurrency, 12));
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            futures.add(pool.submit(() -> {
                startGate.await();
                return task.call();
            }));
        }
        startGate.countDown();

        List<T> results = new ArrayList<>(concurrency);
        for (Future<T> future : futures) {
            results.add(future.get(10, TimeUnit.SECONDS));
        }
        pool.shutdown();
        if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
            pool.shutdownNow();
        }
        return results;
    }

    private void assumeRedisAvailable() {
        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            String pong = connection.ping();
            assumeTrue("PONG".equalsIgnoreCase(pong), "Redis ping failed");
        } catch (Exception e) {
            assumeTrue(false, "Redis unavailable: " + e.getMessage());
        }
    }

    private RedissonClient createRedissonClient() {
        Config config = new Config();
        SingleServerConfig singleServerConfig = config.useSingleServer()
                .setAddress("redis://" + redisHost + ":" + redisPort)
                .setDatabase(redisDatabase);
        if (redisPassword != null && !redisPassword.isBlank()) {
            singleServerConfig.setPassword(redisPassword);
        }
        return Redisson.create(config);
    }

    private void deleteByPattern(String pattern) {
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private static void readRequestBody(HttpExchange exchange) throws IOException {
        if (exchange.getRequestBody() != null) {
            exchange.getRequestBody().readAllBytes();
            exchange.getRequestBody().close();
        }
    }

    private static void writeJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getResponseBody().close();
        exchange.close();
    }

    @SpringBootConfiguration
    @Import(TestBeans.class)
    static class TestApplication {
    }

    @org.springframework.boot.test.context.TestConfiguration
    static class TestBeans {
        @Bean
        ObjectMapper objectMapper() {
            ObjectMapper mapper = new ObjectMapper();
            mapper.findAndRegisterModules();
            return mapper;
        }

        @Bean
        LettuceConnectionFactory lettuceConnectionFactory(
                @Value("${test.redis.host:${spring.data.redis.host:localhost}}") String host,
                @Value("${test.redis.port:${spring.data.redis.port:6379}}") int port,
                @Value("${test.redis.database:${spring.data.redis.database:0}}") int database,
                @Value("${test.redis.password:${spring.data.redis.password:}}") String password) {
            RedisStandaloneConfiguration conf = new RedisStandaloneConfiguration(host, port);
            conf.setDatabase(database);
            if (password != null && !password.isBlank()) {
                conf.setPassword(RedisPassword.of(password));
            }
            return new LettuceConnectionFactory(conf);
        }

        @Bean
        StringRedisTemplate stringRedisTemplate(LettuceConnectionFactory connectionFactory) {
            connectionFactory.afterPropertiesSet();
            return new StringRedisTemplate(connectionFactory);
        }
    }

    private static class InMemoryTaskBoardRepository implements TaskBoardRepository {
        private final Map<String, Map<Integer, Task>> store = new ConcurrentHashMap<>();

        @Override
        public void save(Task task) {
            store.computeIfAbsent(task.getSessionId(), ignored -> new ConcurrentHashMap<>())
                    .put(task.getTaskId(), task);
        }

        @Override
        public void saveAll(List<Task> tasks) {
            for (Task task : tasks) {
                save(task);
            }
        }

        @Override
        public Optional<Task> findById(String sessionId, int taskId) {
            return Optional.ofNullable(store.getOrDefault(sessionId, Map.of()).get(taskId));
        }

        @Override
        public List<Task> findBySessionId(String sessionId) {
            return new ArrayList<>(store.getOrDefault(sessionId, Map.of()).values());
        }

        @Override
        public List<Task> findBySessionIdAndStatus(String sessionId, TaskStatus status) {
            return findBySessionId(sessionId).stream()
                    .filter(task -> task.getStatus() == status)
                    .toList();
        }

        @Override
        public List<Task> findBySessionIdAndOwner(String sessionId, String owner) {
            return findBySessionId(sessionId).stream()
                    .filter(task -> owner != null && owner.equals(task.getOwner()))
                    .toList();
        }

        @Override
        public List<Task> findExecutableTasks(String sessionId) {
            return findBySessionId(sessionId).stream()
                    .filter(task -> task.getStatus() == TaskStatus.PENDING || task.getStatus() == TaskStatus.ASSIGNED)
                    .filter(task -> task.getBlockedBy() == null || task.getBlockedBy().isEmpty())
                    .toList();
        }

        @Override
        public void delete(String sessionId, int taskId) {
            Map<Integer, Task> tasks = store.get(sessionId);
            if (tasks != null) {
                tasks.remove(taskId);
            }
        }

        @Override
        public void deleteBySessionId(String sessionId) {
            store.remove(sessionId);
        }

        @Override
        public boolean exists(String sessionId, int taskId) {
            return store.containsKey(sessionId) && store.get(sessionId).containsKey(taskId);
        }
    }
}
