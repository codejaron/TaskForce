package com.agent.domain.worker.service;

import com.agent.domain.context.assembly.ContextAssembler;
import com.agent.domain.execution.model.AgentExecutionStatus;
import com.agent.domain.execution.service.AgentExecutionStateService;
import com.agent.domain.execution.service.ExecutionWaitIntentService;
import com.agent.domain.taskboard.dto.TaskUpdateRequest;
import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.model.TaskStatus;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.domain.team.model.TeamMessage;
import com.agent.domain.team.service.InboxService;
import com.agent.domain.worker.model.WorkerInstance;
import com.agent.domain.worker.model.WorkerStatus;
import com.agent.domain.worker.repository.WorkerInstanceRepository;
import com.agent.infrastructure.agent.CheckpointThreadIds;
import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.event.events.WorkerOutputEvent;
import com.agent.service.SessionExecutionTracker;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker 自主循环（模式一：Leader 指派）
 *
 * 流程：
 * 1. 启动后检查 assignedTaskId
 * 2. 如果任务被阻塞（blockedBy 非空），进入等待模式，轮询直到解锁
 * 3. 任务可执行时，认领并执行
 * 4. 执行完成后通知 Leader，进入 IDLE 等待新指令
 * 5. 通过 Inbox 接收 Leader 的新任务指派、消息或 shutdown 指令
 */
@Slf4j
public class WorkerLoop implements Runnable {

    private static final int POLL_INTERVAL_MS = 2000;
    private static final int MAX_REACT_ITERATIONS = 20;
    private static final int BLOCKED_CHECK_INTERVAL_MS = 3000; // 等待阻塞解除的检查间隔

    private final WorkerInstance workerInstance;
    private final WorkerInstanceRepository workerRepository;
    private final TaskBoardService taskBoardService;
    private final ReactAgentFactory reactAgentFactory;
    private final EventBus eventBus;
    private final SessionExecutionTracker executionTracker;
    private final InboxService inboxService;
    private final ContextAssembler contextAssembler;
    private final BaseCheckpointSaver checkpointSaver;
    private final AgentExecutionStateService executionStateService;
    private final ExecutionWaitIntentService waitIntentService;
    private final String startupResumeInput;
    private final Runnable onStopped;

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean pausedForReply = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private volatile Thread loopThread;

    public WorkerLoop(
            WorkerInstance workerInstance,
            WorkerInstanceRepository workerRepository,
            TaskBoardService taskBoardService,
            ReactAgentFactory reactAgentFactory,
            EventBus eventBus,
            SessionExecutionTracker executionTracker,
            InboxService inboxService,
            ContextAssembler contextAssembler,
            BaseCheckpointSaver checkpointSaver,
            AgentExecutionStateService executionStateService,
            ExecutionWaitIntentService waitIntentService,
            String startupResumeInput,
            Runnable onStopped) {
        this.workerInstance = workerInstance;
        this.workerRepository = workerRepository;
        this.taskBoardService = taskBoardService;
        this.reactAgentFactory = reactAgentFactory;
        this.eventBus = eventBus;
        this.executionTracker = executionTracker;
        this.inboxService = inboxService;
        this.contextAssembler = contextAssembler;
        this.checkpointSaver = checkpointSaver;
        this.executionStateService = executionStateService;
        this.waitIntentService = waitIntentService;
        this.startupResumeInput = startupResumeInput;
        this.onStopped = onStopped == null ? () -> {} : onStopped;
    }

    @Override
    public void run() {
        loopThread = Thread.currentThread();
        log.info("[WorkerLoop] Starting worker loop: instanceId={}, name={}, assignedTaskId={}",
                workerInstance.getInstanceId(), workerInstance.getName(),
                workerInstance.getAssignedTaskId());

        waitIntentService.clear(workerInstance.getInstanceId());
        executionStateService.setStatus(
                workerInstance.getInstanceId(),
                AgentExecutionStatus.RUNNING,
                "worker loop started"
        );

        try {
            // ===== 阶段一：执行初始指派任务 =====
            int assignedTaskId = workerInstance.getAssignedTaskId();
            if (assignedTaskId != 0) {
                executeAssignedTask(assignedTaskId, startupResumeInput);
                if (pausedForReply.get() || shutdown.get()) {
                    return;
                }
            }

            // ===== 阶段二：进入待命循环，等待 Leader 指令 =====
            while (!shutdown.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // 检查 Inbox，处理新指派、消息或 shutdown
                    checkInbox();

                    // 如果 Leader 通过 inbox 指派了新任务，执行它
                    int newTaskId = workerInstance.getAssignedTaskId();
                    if (newTaskId != 0) {
                        executeAssignedTask(newTaskId, null);
                        if (pausedForReply.get()) {
                            return;
                        }
                    }

                    Thread.sleep(POLL_INTERVAL_MS);

                } catch (InterruptedException e) {
                    log.info("[WorkerLoop] Worker loop interrupted: instanceId={}",
                            workerInstance.getInstanceId());
                    break;
                } catch (Exception e) {
                    log.error("[WorkerLoop] Error in worker loop: instanceId={}",
                            workerInstance.getInstanceId(), e);
                }
            }
        } catch (InterruptedException e) {
            log.info("[WorkerLoop] Worker interrupted: instanceId={}",
                    workerInstance.getInstanceId());
        } catch (Exception e) {
            failed.set(true);
            executionStateService.setStatus(
                    workerInstance.getInstanceId(),
                    AgentExecutionStatus.FAILED,
                    "worker loop exception: " + e.getMessage()
            );
            log.error("[WorkerLoop] Worker failed: instanceId={}", workerInstance.getInstanceId(), e);
        } finally {
            if (shutdown.get()) {
                releaseWorkerCheckpoint();
                workerInstance.shutdown();
                workerRepository.save(workerInstance);
                executionStateService.setStatus(
                        workerInstance.getInstanceId(),
                        AgentExecutionStatus.COMPLETED,
                        "worker shutdown"
                );
            } else if (pausedForReply.get()) {
                executionStateService.setStatus(
                        workerInstance.getInstanceId(),
                        AgentExecutionStatus.WAITING_REPLY,
                        "waiting inbox reply"
                );
            } else if (failed.get()) {
                releaseWorkerCheckpoint();
                workerInstance.completeWork();
                workerRepository.save(workerInstance);
                executionStateService.setStatus(
                        workerInstance.getInstanceId(),
                        AgentExecutionStatus.FAILED,
                        "worker loop stopped with failure"
                );
            }
            onStopped.run();
            log.info("[WorkerLoop] Worker loop stopped: instanceId={}, pausedForReply={}, shutdown={}",
                    workerInstance.getInstanceId(), pausedForReply.get(), shutdown.get());
        }
    }

    /**
     * 检查 Inbox 并处理消息
     */
    private void checkInbox() {
        try {
            // 读取收件箱消息
            List<TeamMessage> messages = inboxService.readInbox(workerInstance.getInstanceId());

            if (messages.isEmpty()) {
                return;
            }

            log.info("[WorkerLoop] Received {} messages: instanceId={}",
                    messages.size(), workerInstance.getInstanceId());

            // 处理每条消息
            for (TeamMessage message : messages) {
                handleMessage(message);
            }

        } catch (Exception e) {
            log.error("[WorkerLoop] Error checking inbox: instanceId={}",
                    workerInstance.getInstanceId(), e);
        }
    }

    /**
     * 处理单条消息
     */
    private void handleMessage(TeamMessage message) {
        log.info("[WorkerLoop] Handling message: type={}, from={}",
                message.getType(), message.getFrom());

        switch (message.getType()) {
            case "SHUTDOWN_REQUEST":
                log.info("[WorkerLoop] Received shutdown request from: {}", message.getFrom());
                requestShutdown();
                break;

            case "ASSIGN_TASK":
                // Leader 通过 inbox 指派新任务
                try {
                    int newTaskId = Integer.parseInt(message.getText());
                    log.info("[WorkerLoop] Received task assignment: taskId={}", newTaskId);
                    workerInstance.assignTask(newTaskId);
                    workerRepository.save(workerInstance);
                } catch (NumberFormatException e) {
                    log.error("[WorkerLoop] Invalid task ID in ASSIGN_TASK message: {}", message.getText());
                }
                break;

            case "USER_MESSAGE":
            case "INSTRUCTION":
            case "MESSAGE":
                log.info("[WorkerLoop] Received instruction: {}", message.getText());
                break;

            default:
                log.debug("[WorkerLoop] Unhandled message type: {}", message.getType());
                break;
        }
    }

    /**
     * 执行指派的任务
     * 处理阻塞等待 → 验证 owner → 执行 → 完成的完整流程
     */
    private void executeAssignedTask(int taskId, String resumeMessage) throws InterruptedException {
        log.info("[WorkerLoop] Preparing to execute assigned task: taskId={}, instanceId={}",
                taskId, workerInstance.getInstanceId());

        // 1. 等待任务解除阻塞
        Task task = waitForTaskUnblocked(taskId);
        if (task == null) {
            // shutdown 或任务不存在
            if (!shutdown.get()) {
                workerInstance.clearAssignedTask();
                workerRepository.save(workerInstance);
            }
            return;
        }

        // 2. 任务已经在 spawn 时被 assign 给了这个 Worker
        //    不需要再认领，直接验证一下 owner 是自己
        if (!workerInstance.getInstanceId().equals(task.getOwner())) {
            log.error("[WorkerLoop] Task owner mismatch: taskId={}, expected={}, actual={}",
                    taskId, workerInstance.getInstanceId(), task.getOwner());
            workerInstance.clearAssignedTask();
            workerRepository.save(workerInstance);
            releaseWorkerCheckpoint();
            return;
        }

        log.info("[WorkerLoop] Starting assigned task: taskId={}, subject={}",
                task.getTaskId(), task.getSubject());

        // 3. 直接执行
        TaskExecutionOutcome outcome = executeTask(task, resumeMessage);
        if (outcome == TaskExecutionOutcome.WAITING_FOR_REPLY) {
            pausedForReply.set(true);
            return;
        }
        if (outcome.isTerminal()) {
            workerInstance.clearAssignedTask();
            workerRepository.save(workerInstance);
        }
    }

    /**
     * 等待任务的 blockedBy 全部完成
     * 轮询检查 + 同时检查 Inbox（以便接收 shutdown 等指令）
     */
    private Task waitForTaskUnblocked(int taskId) throws InterruptedException {
        while (!shutdown.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Task task = taskBoardService.getTask(workerInstance.getSessionId(), taskId);

                if (task.canStart()) {
                    // 没有阻塞或阻塞已解除，可以开始
                    log.info("[WorkerLoop] Task is ready (no blockers): taskId={}", taskId);
                    return task;
                }

                // 任务仍被阻塞
                if (workerInstance.getStatus() != WorkerStatus.WAITING) {
                    workerInstance.startWaiting();
                    workerRepository.save(workerInstance);
                    log.info("[WorkerLoop] Task blocked, entering WAITING state: taskId={}, blockedBy={}",
                            taskId, task.getBlockedBy());
                }

                // 等待期间也检查 Inbox（可能收到 shutdown）
                checkInbox();

                Thread.sleep(BLOCKED_CHECK_INTERVAL_MS);

            } catch (IllegalArgumentException e) {
                // 任务不存在
                log.error("[WorkerLoop] Assigned task not found: taskId={}", taskId);
                return null;
            }
        }
        return null; // shutdown
    }

    /**
     * 执行任务
     */
    private TaskExecutionOutcome executeTask(Task task, String resumeMessage) {
        log.info("[WorkerLoop] Executing task: taskId={}, subject={}",
                task.getTaskId(), task.getSubject());

        try {
            // 1. 更新 Worker 状态
            workerInstance.startWorking(task.getTaskId());
            workerRepository.save(workerInstance);

            // 2. 更新任务状态为 IN_PROGRESS
            if (task.getStatus() == TaskStatus.ASSIGNED) {
                taskBoardService.startTask(workerInstance.getSessionId(), task.getTaskId());
            } else if (task.getStatus() == TaskStatus.IN_PROGRESS) {
                log.info("[WorkerLoop] Resuming in-progress task from checkpoint: taskId={}", task.getTaskId());
            } else if (task.isCompleted()) {
                workerInstance.completeWork();
                workerRepository.save(workerInstance);
                releaseWorkerCheckpoint();
                return TaskExecutionOutcome.COMPLETED;
            } else if (task.isFailed()) {
                workerInstance.completeWork();
                workerRepository.save(workerInstance);
                releaseWorkerCheckpoint();
                return TaskExecutionOutcome.FAILED;
            } else {
                throw new IllegalStateException(
                        "Unsupported task status for execution: " + task.getStatus() + ", taskId=" + task.getTaskId());
            }

            // 3. 构建执行指令
            String instruction = buildTaskInstruction(task);

            // 4. 构建 ReactAgent
            ReactAgent reactAgent = reactAgentFactory.buildWorkerReactAgent(
                    Long.valueOf(workerInstance.getAgentId()),
                    instruction,
                    MAX_REACT_ITERATIONS,
                    workerInstance.getSessionId(),
                    String.valueOf(task.getTaskId()),
                    null, // stepIndex 在 Team 模式下不适用
                    workerInstance.getInstanceId() // Worker 实例 ID，用于 InboxCheckHook
            );

            // 5. 配置 RunnableConfig（传递 sessionId 和 instanceId 到 metadata）
            RunnableConfig config = buildWorkerConfig(task.getTaskId());
            String agentInput = buildAgentInput(resumeMessage);

            // 6. 执行任务（流式）
            Disposable disposable = reactAgent.stream(agentInput, config)
                    .doOnNext(nodeOutput -> {
                        if (nodeOutput instanceof StreamingOutput streamingOutput) {
                            String chunk = streamingOutput.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                // 发布实时事件给前端
                                try {
                                    WorkerOutputEvent event = new WorkerOutputEvent(
                                            workerInstance.getSessionId(),
                                            workerInstance.getInstanceId(),
                                            task.getTaskId(),
                                            chunk
                                    );
                                    eventBus.publishToWorker(
                                            workerInstance.getSessionId(),
                                            workerInstance.getInstanceId(),
                                            event
                                    );
                                } catch (Exception e) {
                                    log.warn("[WorkerLoop] Failed to publish output event", e);
                                }
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        log.info("[WorkerLoop] Task execution completed: taskId={}", task.getTaskId());
                    })
                    .doOnError(e -> {
                        log.error("[WorkerLoop] Task execution error: taskId={}", task.getTaskId(), e);
                    })
                    .subscribe();

            // 注册 Disposable
            executionTracker.registerDisposable(workerInstance.getSessionId(), disposable);

            // 等待执行完成
            while (!disposable.isDisposed() && !shutdown.get()) {
                Thread.sleep(100);
            }

            // 7. 更新任务状态
            if (shutdown.get()) {
                log.info("[WorkerLoop] Task interrupted by shutdown: taskId={}", task.getTaskId());
                TaskUpdateRequest updateRequest = TaskUpdateRequest.builder()
                        .status(TaskStatus.PENDING)
                        .owner(null)
                        .build();
                taskBoardService.updateTask(workerInstance.getSessionId(), task.getTaskId(), updateRequest);
                workerInstance.completeWork();
                workerRepository.save(workerInstance);
                releaseWorkerCheckpoint();
                return TaskExecutionOutcome.INTERRUPTED;
            } else {
                boolean shouldWaitReply = waitIntentService.consumeWaitingReply(workerInstance.getInstanceId());
                Task updatedTask = taskBoardService.getTask(workerInstance.getSessionId(), task.getTaskId());
                if (updatedTask.isCompleted()) {
                    log.info("[WorkerLoop] Task completed by worker: taskId={}", task.getTaskId());
                    workerInstance.completeWork();
                    workerRepository.save(workerInstance);
                    releaseWorkerCheckpoint();
                    return TaskExecutionOutcome.COMPLETED;
                } else if (updatedTask.isFailed()) {
                    log.info("[WorkerLoop] Task already marked as failed: taskId={}", task.getTaskId());
                    workerInstance.completeWork();
                    workerRepository.save(workerInstance);
                    releaseWorkerCheckpoint();
                    return TaskExecutionOutcome.FAILED;
                } else if (shouldWaitReply) {
                    log.info("[WorkerLoop] Task paused waiting for inbox reply: taskId={}", task.getTaskId());
                    workerInstance.startWaitingReply(task.getTaskId());
                    workerRepository.save(workerInstance);
                    return TaskExecutionOutcome.WAITING_FOR_REPLY;
                } else {
                    log.warn("[WorkerLoop] Task ended without complete_task and no wait intent, marking failed: taskId={}",
                            task.getTaskId());
                    taskBoardService.failTask(workerInstance.getSessionId(), task.getTaskId());
                    workerInstance.completeWork();
                    workerRepository.save(workerInstance);
                    releaseWorkerCheckpoint();
                    return TaskExecutionOutcome.FAILED;
                }
            }

        } catch (Exception e) {
            log.error("[WorkerLoop] Failed to execute task: taskId={}", task.getTaskId(), e);

            // 标记任务失败
            try {
                taskBoardService.failTask(workerInstance.getSessionId(), task.getTaskId());
            } catch (Exception ex) {
                log.error("[WorkerLoop] Failed to mark task as failed: taskId={}", task.getTaskId(), ex);
            }

            // 恢复 Worker 状态
            workerInstance.completeWork();
            workerRepository.save(workerInstance);
            releaseWorkerCheckpoint();
            return TaskExecutionOutcome.FAILED;
        }
    }

    private RunnableConfig buildWorkerConfig(int currentTaskId) {
        return RunnableConfig.builder()
                .threadId(CheckpointThreadIds.workerThreadId(workerInstance.getInstanceId()))
                .addMetadata("sessionId", workerInstance.getSessionId())
                .addMetadata("instanceId", workerInstance.getInstanceId())
                .addMetadata("currentTaskId", currentTaskId)
                .build();
    }

    private String buildAgentInput(String resumeMessage) {
        if (resumeMessage == null || resumeMessage.isBlank()) {
            return "";
        }
        return resumeMessage;
    }

    private void releaseWorkerCheckpoint() {
        String threadId = CheckpointThreadIds.workerThreadId(workerInstance.getInstanceId());
        try {
            checkpointSaver.release(RunnableConfig.builder().threadId(threadId).build());
            log.info("[WorkerLoop] Released checkpoint: threadId={}", threadId);
        } catch (IllegalStateException e) {
            log.debug("[WorkerLoop] No checkpoint found to release: threadId={}", threadId);
        } catch (Exception e) {
            log.warn("[WorkerLoop] Failed to release checkpoint: threadId={}", threadId, e);
        }
    }

    private enum TaskExecutionOutcome {
        COMPLETED(true),
        FAILED(true),
        INTERRUPTED(true),
        WAITING_FOR_REPLY(false);

        private final boolean terminal;

        TaskExecutionOutcome(boolean terminal) {
            this.terminal = terminal;
        }

        boolean isTerminal() {
            return terminal;
        }
    }

    /**
     * 构建任务执行指令
     */
    private String buildTaskInstruction(Task task) {
        StringBuilder instruction = new StringBuilder();

        // 只保留当前任务信息，去掉 initialPrompt 和完整任务板
        instruction.append("## 当前任务\n\n");
        instruction.append("**").append(task.getSubject()).append("**\n\n");
        instruction.append(task.getDescription()).append("\n\n");
        instruction.append("你当前执行的任务ID是 ").append(task.getTaskId()).append("。\n");
        instruction.append("完成后请调用 complete_task 标记完成，taskId 必须使用当前任务ID。\n");

        // 如果有依赖任务的输出，只附加相关上下文
        try {
            String taskContext = contextAssembler.assembleForTask(
                    workerInstance.getSessionId(),
                    task.getTaskId()
            );
            // 只取依赖输出部分，不要整个任务板
            instruction.append("\n").append(taskContext);
        } catch (Exception e) {
            log.warn("[WorkerLoop] Context assembly failed, using basic info");
        }

        return instruction.toString();
    }


    /**
     * 请求关闭
     */
    public void requestShutdown() {
        log.info("[WorkerLoop] Shutdown requested: instanceId={}", workerInstance.getInstanceId());
        shutdown.set(true);

        // 中断循环线程
        if (loopThread != null) {
            loopThread.interrupt();
        }
    }

    /**
     * 是否已关闭
     */
    public boolean isShutdown() {
        return shutdown.get();
    }
}
