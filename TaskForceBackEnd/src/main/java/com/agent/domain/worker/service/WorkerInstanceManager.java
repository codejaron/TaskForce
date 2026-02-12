package com.agent.domain.worker.service;

import com.agent.domain.context.assembly.ContextAssembler;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.domain.team.service.InboxService;
import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.repository.WorkerInstanceRepository;
import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.WorkerSpawnedEvent;
import com.agent.service.SessionExecutionTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Worker 实例管理器
 * 负责 Worker 实例的生命周期管理
 */
@Slf4j
@Service
public class WorkerInstanceManager {

    private final WorkerInstanceRepository workerRepository;
    private final TaskBoardService taskBoardService;
    private final ReactAgentFactory reactAgentFactory;
    private final EventBus eventBus;
    private final SessionExecutionTracker executionTracker;
    private final InboxService inboxService;
    private final ContextAssembler contextAssembler;

    public WorkerInstanceManager(
            WorkerInstanceRepository workerRepository,
            TaskBoardService taskBoardService,
            @Lazy ReactAgentFactory reactAgentFactory,
            EventBus eventBus,
            SessionExecutionTracker executionTracker,
            InboxService inboxService,
            ContextAssembler contextAssembler) {
        this.workerRepository = workerRepository;
        this.taskBoardService = taskBoardService;
        this.reactAgentFactory = reactAgentFactory;
        this.eventBus = eventBus;
        this.executionTracker = executionTracker;
        this.inboxService = inboxService;
        this.contextAssembler = contextAssembler;
    }

    // 线程池：用于运行 WorkerLoop
    private final ExecutorService workerExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r);
        thread.setName("worker-loop-" + thread.getId());
        thread.setDaemon(true);
        return thread;
    });

    // 跟踪运行中的 WorkerLoop
    private final Map<String, WorkerLoop> runningLoops = new ConcurrentHashMap<>();

    /**
     * 创建并启动 Worker 实例
     *
     * @param sessionId      会话 ID
     * @param name           Worker 名称
     * @param agentId        Agent ID
     * @param initialPrompt  初始 Prompt
     * @param assignedTaskId 指派的任务 ID
     * @return Worker 实例
     */
    public WorkerInstance spawn(String sessionId, String name, String agentId,
                                String initialPrompt, int assignedTaskId) {
        log.info("[WorkerInstanceManager] Spawning worker: sessionId={}, name={}, agentId={}, assignedTaskId={}",
                sessionId, name, agentId, assignedTaskId);

        int workerId = nextWorkerId(sessionId);

        String instanceId = buildInstanceId(sessionId, workerId);

        // 1. 创建 Worker 实例（带指派任务）
        WorkerInstance instance = WorkerInstance.createWithTask(
                sessionId,
                name,
                agentId,
                workerId,
                instanceId,
                assignedTaskId
        );
        workerRepository.save(instance);

        // 2. 在 spawn 的时候就把任务分配给这个 Worker
        if (assignedTaskId != 0) {
            taskBoardService.assignTask(sessionId, assignedTaskId, instance.getInstanceId());
        }

        // 3. 创建 WorkerLoop
        WorkerLoop workerLoop = new WorkerLoop(
                instance,
                workerRepository,
                taskBoardService,
                reactAgentFactory,
                eventBus,
                executionTracker,
                inboxService,
                contextAssembler
        );

        // 4. 启动 WorkerLoop
        runningLoops.put(instance.getInstanceId(), workerLoop);
        workerExecutor.submit(workerLoop);

        // 5. 发布 worker_spawned 事件
        eventBus.publish(sessionId, new WorkerSpawnedEvent(sessionId, instance.getInstanceId(), name, agentId));

        log.info("[WorkerInstanceManager] Worker spawned successfully: workerId={}, instanceId={}, assignedTaskId={}",
                instance.getWorkerId(), instance.getInstanceId(), assignedTaskId);
        return instance;
    }

    /**
     * 创建并启动 Worker 实例（兼容旧版本，无指派任务）
     *
     * @param sessionId     会话 ID
     * @param name          Worker 名称
     * @param agentId       Agent ID
     * @param initialPrompt 初始 Prompt
     * @return Worker 实例
     */
    public WorkerInstance spawn(String sessionId, String name, String agentId, String initialPrompt) {
        return spawn(sessionId, name, agentId, initialPrompt, 0);
    }

    /**
     * 优雅关闭 Worker 实例
     *
     * @param instanceId Worker 实例 ID
     * @return 是否成功关闭
     */
    public boolean shutdown(String instanceId) {
        log.info("[WorkerInstanceManager] Shutting down worker: instanceId={}", instanceId);

        // 1. 查找 WorkerLoop
        WorkerLoop workerLoop = runningLoops.get(instanceId);
        if (workerLoop == null) {
            log.warn("[WorkerInstanceManager] Worker loop not found: instanceId={}", instanceId);
            return false;
        }

        // 2. 请求关闭
        workerLoop.requestShutdown();

        // 3. 从跟踪中移除
        runningLoops.remove(instanceId);

        // 4. 更新数据库状态
        Optional<WorkerInstance> instanceOpt = workerRepository.findById(instanceId);
        if (instanceOpt.isPresent()) {
            WorkerInstance instance = instanceOpt.get();
            instance.shutdown();
            workerRepository.save(instance);
        }

        log.info("[WorkerInstanceManager] Worker shutdown requested: instanceId={}", instanceId);
        return true;
    }

    /**
     * 关闭会话的所有 Worker
     *
     * @param sessionId 会话 ID
     */
    public void shutdownAllBySession(String sessionId) {
        log.info("[WorkerInstanceManager] Shutting down all workers for session: sessionId={}", sessionId);

        List<WorkerInstance> workers = workerRepository.findBySessionId(sessionId);
        for (WorkerInstance worker : workers) {
            shutdown(worker.getInstanceId());
        }

        log.info("[WorkerInstanceManager] All workers shutdown for session: sessionId={}", sessionId);
    }

    /**
     * 查询运行中的 Worker
     *
     * @param sessionId 会话 ID
     * @return Worker 实例列表
     */
    public List<WorkerInstance> getRunningWorkers(String sessionId) {
        List<WorkerInstance> allWorkers = workerRepository.findBySessionId(sessionId);

        // 过滤出运行中的 Worker（非 SHUTDOWN 状态）
        return allWorkers.stream()
                .filter(worker -> !worker.isShutdown())
                .sorted(Comparator.comparingInt(WorkerInstance::getWorkerId))
                .collect(Collectors.toList());
    }

    /**
     * 查询会话内所有 Worker（含已关闭）
     */
    public List<WorkerInstance> getAllWorkers(String sessionId) {
        return workerRepository.findBySessionId(sessionId).stream()
                .sorted(Comparator.comparingInt(WorkerInstance::getWorkerId))
                .collect(Collectors.toList());
    }

    /**
     * 根据会话 + workerId 查找 Worker
     */
    public Optional<WorkerInstance> findBySessionAndWorkerId(String sessionId, int workerId) {
        if (workerId <= 0) {
            return Optional.empty();
        }

        return workerRepository.findBySessionId(sessionId).stream()
                .filter(worker -> resolveWorkerId(worker) == workerId)
                .findFirst();
    }

    /**
     * 根据会话 + instanceId 查找 Worker
     */
    public Optional<WorkerInstance> findBySessionAndInstanceId(String sessionId, String instanceId) {
        if (instanceId == null || instanceId.isBlank()) {
            return Optional.empty();
        }

        return workerRepository.findBySessionId(sessionId).stream()
                .filter(worker -> instanceId.equals(worker.getInstanceId()))
                .findFirst();
    }

    /**
     * 查询 Worker 状态
     *
     * @param instanceId Worker 实例 ID
     * @return Worker 实例（如果存在）
     */
    public Optional<WorkerInstance> getWorkerStatus(String instanceId) {
        return workerRepository.findById(instanceId);
    }

    /**
     * 检查 Worker 是否运行中
     *
     * @param instanceId Worker 实例 ID
     * @return 是否运行中
     */
    public boolean isRunning(String instanceId) {
        return runningLoops.containsKey(instanceId);
    }

    /**
     * 获取所有运行中的 Worker 数量
     *
     * @param sessionId 会话 ID
     * @return 运行中的 Worker 数量
     */
    public int getRunningWorkerCount(String sessionId) {
        return getRunningWorkers(sessionId).size();
    }

    /**
     * 清理已关闭的 Worker 实例
     *
     * @param sessionId 会话 ID
     */
    public void cleanupShutdownWorkers(String sessionId) {
        List<WorkerInstance> workers = workerRepository.findBySessionId(sessionId);

        for (WorkerInstance worker : workers) {
            if (worker.isShutdown() && !runningLoops.containsKey(worker.getInstanceId())) {
                workerRepository.delete(worker.getInstanceId());
                log.debug("[WorkerInstanceManager] Cleaned up shutdown worker: instanceId={}",
                        worker.getInstanceId());
            }
        }
    }

    private int nextWorkerId(String sessionId) {
        return workerRepository.findBySessionId(sessionId).stream()
                .mapToInt(this::resolveWorkerId)
                .max()
                .orElse(0) + 1;
    }

    private String buildInstanceId(String sessionId, int workerId) {
        return sessionId + "_w" + workerId;
    }

    private int resolveWorkerId(WorkerInstance worker) {
        if (worker.getWorkerId() > 0) {
            return worker.getWorkerId();
        }
        if (worker.getAssignedTaskId() > 0) {
            return worker.getAssignedTaskId();
        }
        return worker.getCurrentTaskId();
    }
}
