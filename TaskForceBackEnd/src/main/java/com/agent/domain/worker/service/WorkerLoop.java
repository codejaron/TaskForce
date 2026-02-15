package com.agent.domain.worker.service;

import com.agent.domain.execution.model.AgentExecutionStatus;
import com.agent.domain.execution.service.AgentExecutionStateService;
import com.agent.domain.execution.service.ExecutionWaitIntentService;
import com.agent.domain.taskboard.dto.TaskUpdateRequest;
import com.agent.domain.taskboard.model.Task;
import com.agent.domain.taskboard.model.TaskStatus;
import com.agent.domain.taskboard.service.TaskBoardService;
import com.agent.domain.team.context.TeamTaskContextService;
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

import java.util.ArrayDeque;
import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Worker 自主循环（模式一：Leader 指派）
 *
 * 流程：
 * 1. 启动后检查 assignedTaskId
 * 2. 如果任务被阻塞（blockedBy 非空），进入等待模式，轮询直到解锁
 * 3. 任务可执行时，认领并执行
 * 4. 执行完成后进入 IDLE，结束当前轮次
 * 5. 新消息到达时由事件驱动恢复下一轮执行
 */
@Slf4j
public class WorkerLoop implements Runnable {

    private static final int MAX_REACT_ITERATIONS = 20;
    private static final int BLOCKED_CHECK_INTERVAL_MS = 3000; // 等待阻塞解除的检查间隔
    private static final int INBOX_BATCH_LIMIT = 200;
    private static final String TASK_EXECUTION_INSTRUCTION = """
            你是团队模式下的 Worker，正在执行 Task Board 指派的任务。
            严格按照本轮用户输入中的任务说明执行，并使用可用工具完成目标。
            必须在任务完成时调用 complete_task，summary 必须是一句 completionNote。
            除非任务要求，否则不要回复与任务无关的内容。
            """;

    private final WorkerInstance workerInstance;
    private final WorkerInstanceRepository workerRepository;
    private final TaskBoardService taskBoardService;
    private final ReactAgentFactory reactAgentFactory;
    private final EventBus eventBus;
    private final SessionExecutionTracker executionTracker;
    private final InboxService inboxService;
    private final TeamTaskContextService teamTaskContextService;
    private final BaseCheckpointSaver checkpointSaver;
    private final AgentExecutionStateService executionStateService;
    private final ExecutionWaitIntentService waitIntentService;
    private final WorkerRoundControlService workerRoundControlService;
    private final String startupResumeInput;
    private final Runnable onStopped;

    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean pausedForReply = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final Deque<String> pendingTextInputs = new ArrayDeque<>();
    private volatile AgentExecutionStatus lastReportedStatus;
    private volatile Thread loopThread;

    public WorkerLoop(
            WorkerInstance workerInstance,
            WorkerInstanceRepository workerRepository,
            TaskBoardService taskBoardService,
            ReactAgentFactory reactAgentFactory,
            EventBus eventBus,
            SessionExecutionTracker executionTracker,
            InboxService inboxService,
            TeamTaskContextService teamTaskContextService,
            BaseCheckpointSaver checkpointSaver,
            AgentExecutionStateService executionStateService,
            ExecutionWaitIntentService waitIntentService,
            WorkerRoundControlService workerRoundControlService,
            String startupResumeInput,
            Runnable onStopped) {
        this.workerInstance = workerInstance;
        this.workerRepository = workerRepository;
        this.taskBoardService = taskBoardService;
        this.reactAgentFactory = reactAgentFactory;
        this.eventBus = eventBus;
        this.executionTracker = executionTracker;
        this.inboxService = inboxService;
        this.teamTaskContextService = teamTaskContextService;
        this.checkpointSaver = checkpointSaver;
        this.executionStateService = executionStateService;
        this.waitIntentService = waitIntentService;
        this.workerRoundControlService = workerRoundControlService;
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
        reportExecutionStatus(
                workerInstance.getInstanceId(),
                AgentExecutionStatus.EXECUTING,
                "worker loop started"
        );

        try {
            // ===== 当前轮：处理收件箱并执行所有已指派任务 =====
            String resumeInput = startupResumeInput;
            boolean resumeInputUsed = false;
            while (!shutdown.get() && !Thread.currentThread().isInterrupted()) {
                // 检查 Inbox，处理新指派、消息或 shutdown
                checkInbox();
                if (shutdown.get() || pausedForReply.get()) {
                    return;
                }

                String inboxInput = drainPendingTextInputs();
                int taskId = workerInstance.getAssignedTaskId();
                if (taskId != 0) {
                    String currentResumeInput = mergeInputs(
                            resumeInputUsed ? null : resumeInput,
                            inboxInput
                    );
                    resumeInputUsed = true;
                    executeAssignedTask(taskId, currentResumeInput);
                    if (shutdown.get() || pausedForReply.get()) {
                        return;
                    }
                    continue;
                }

                String instructionInput = mergeInputs(
                        resumeInputUsed ? null : resumeInput,
                        inboxInput
                );
                resumeInputUsed = true;
                if (instructionInput == null || instructionInput.isBlank()) {
                    reportExecutionStatus(workerInstance.getInstanceId(), AgentExecutionStatus.IDLE, "worker idle");
                    return;
                }

                TaskExecutionOutcome outcome = executeInstructionMessage(instructionInput);
                if (outcome == TaskExecutionOutcome.WAITING_FOR_REPLY) {
                    pausedForReply.set(true);
                    return;
                }
                if (shutdown.get() || pausedForReply.get()) {
                    return;
                }
            }
        } catch (InterruptedException e) {
            log.info("[WorkerLoop] Worker interrupted: instanceId={}",
                    workerInstance.getInstanceId());
        } catch (Exception e) {
            failed.set(true);
            reportExecutionStatus(
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
                reportExecutionStatus(
                        workerInstance.getInstanceId(),
                        AgentExecutionStatus.COMPLETED,
                        "worker shutdown"
                );
            } else if (pausedForReply.get()) {
                reportExecutionStatus(
                        workerInstance.getInstanceId(),
                        AgentExecutionStatus.WAITING_REPLY,
                        "waiting inbox reply"
                );
            } else if (failed.get()) {
                workerInstance.completeWork();
                workerRepository.save(workerInstance);
                reportExecutionStatus(
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
        checkInbox(Duration.ZERO);
    }

    private void checkInbox(Duration blockTimeout) {
        try {
            List<TeamMessage> messages;
            if (blockTimeout != null && !blockTimeout.isZero() && !blockTimeout.isNegative()) {
                messages = inboxService.readInboxBlocking(
                        workerInstance.getInstanceId(),
                        blockTimeout,
                        INBOX_BATCH_LIMIT
                );
            } else {
                messages = inboxService.readInbox(workerInstance.getInstanceId());
            }

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
            case "TASK_EVENT":
                log.info("[WorkerLoop] Received instruction: {}", message.getText());
                if (message.getText() != null && !message.getText().isBlank()) {
                    pendingTextInputs.addLast(formatInboundUserInput(message));
                }
                break;

            default:
                if (message.getText() != null && !message.getText().isBlank()) {
                    log.info("[WorkerLoop] Treating message as user input: type={}, from={}",
                            message.getType(), message.getFrom());
                    pendingTextInputs.addLast(formatInboundUserInput(message));
                } else {
                    log.debug("[WorkerLoop] Unhandled message type: {}", message.getType());
                }
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
                checkInbox(Duration.ofMillis(BLOCKED_CHECK_INTERVAL_MS));

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
        reportExecutionStatus(workerInstance.getInstanceId(), AgentExecutionStatus.EXECUTING, "worker executing task");

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
                return TaskExecutionOutcome.COMPLETED;
            } else if (task.isFailed()) {
                workerInstance.completeWork();
                workerRepository.save(workerInstance);
                return TaskExecutionOutcome.FAILED;
            } else {
                throw new IllegalStateException(
                        "Unsupported task status for execution: " + task.getStatus() + ", taskId=" + task.getTaskId());
            }

            // 3. 构建任务说明（作为本轮 user 输入主体）
            String taskInstruction = buildTaskInstruction(task);

            // 4. 构建 ReactAgent
            ReactAgent reactAgent = reactAgentFactory.buildWorkerReactAgent(
                    Long.valueOf(workerInstance.getAgentId()),
                    TASK_EXECUTION_INSTRUCTION,
                    MAX_REACT_ITERATIONS,
                    workerInstance.getSessionId(),
                    String.valueOf(task.getTaskId()),
                    null, // stepIndex 在 Team 模式下不适用
                    workerInstance.getInstanceId() // Worker 实例 ID，用于 ToolContext
            );

            // 5. 配置 RunnableConfig（传递 sessionId 和 instanceId 到 metadata）
            RunnableConfig config = buildWorkerConfig(task.getTaskId());
            String agentInput = buildTaskRunInput(taskInstruction, resumeMessage);
            long streamStartTimeMs = System.currentTimeMillis();
            long[] firstChunkLatencyMs = new long[]{-1L};
            AtomicBoolean firstChunkObserved = new AtomicBoolean(false);
            AtomicInteger streamedChunkCount = new AtomicInteger(0);
            log.info("[WorkerLoop] LLM stream started: instanceId={}, taskId={}, inputChars={}",
                    workerInstance.getInstanceId(), task.getTaskId(), agentInput.length());

            // 6. 执行任务（流式）
            Disposable disposable = reactAgent.stream(agentInput, config)
                    .doOnNext(nodeOutput -> {
                        if (nodeOutput instanceof StreamingOutput streamingOutput) {
                            String chunk = streamingOutput.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                if (firstChunkObserved.compareAndSet(false, true)) {
                                    firstChunkLatencyMs[0] = System.currentTimeMillis() - streamStartTimeMs;
                                    log.info("[WorkerLoop] LLM first token: instanceId={}, taskId={}, latencyMs={}",
                                            workerInstance.getInstanceId(), task.getTaskId(), firstChunkLatencyMs[0]);
                                }
                                streamedChunkCount.incrementAndGet();
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
                        long totalStreamMs = System.currentTimeMillis() - streamStartTimeMs;
                        log.info("[WorkerLoop] Task execution completed: taskId={}, totalMs={}, firstTokenMs={}, chunkCount={}",
                                task.getTaskId(), totalStreamMs, firstChunkLatencyMs[0], streamedChunkCount.get());
                    })
                    .doOnError(e -> {
                        long totalStreamMs = System.currentTimeMillis() - streamStartTimeMs;
                        log.error("[WorkerLoop] Task execution error: taskId={}, elapsedMs={}, firstTokenMs={}, chunkCount={}",
                                task.getTaskId(), totalStreamMs, firstChunkLatencyMs[0], streamedChunkCount.get(), e);
                    })
                    .subscribe();

            // 注册 Disposable
            executionTracker.registerDisposable(workerInstance.getSessionId(), disposable);
            workerRoundControlService.register(workerInstance.getInstanceId(), disposable);

            try {
                // 等待执行完成
                while (!disposable.isDisposed() && !shutdown.get()) {
                    Thread.sleep(100);
                    // complete_task 会在工具执行期间把任务状态改为 COMPLETED。
                    // 一旦任务进入终态，立即结束本轮流，避免继续产生重复 send_message。
                    if (isTaskTerminal(task.getTaskId())) {
                        if (!disposable.isDisposed()) {
                            disposable.dispose();
                            log.info("[WorkerLoop] Disposed running stream after task became terminal: taskId={}",
                                    task.getTaskId());
                        }
                        break;
                    }
                }
            } finally {
                workerRoundControlService.clear(workerInstance.getInstanceId(), disposable);
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
                return TaskExecutionOutcome.INTERRUPTED;
            } else {
                boolean shouldWaitReply = waitIntentService.consumeWaitingReply(workerInstance.getInstanceId());
                Task updatedTask = taskBoardService.getTask(workerInstance.getSessionId(), task.getTaskId());
                if (updatedTask.isCompleted()) {
                    log.info("[WorkerLoop] Task completed by worker: taskId={}", task.getTaskId());
                    workerInstance.completeWork();
                    workerRepository.save(workerInstance);
                    return TaskExecutionOutcome.COMPLETED;
                } else if (updatedTask.isFailed()) {
                    log.info("[WorkerLoop] Task already marked as failed: taskId={}", task.getTaskId());
                    workerInstance.completeWork();
                    workerRepository.save(workerInstance);
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
                    return TaskExecutionOutcome.FAILED;
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("[WorkerLoop] Task interrupted: taskId={}, shutdown={}", task.getTaskId(), shutdown.get());

            try {
                TaskUpdateRequest updateRequest = TaskUpdateRequest.builder()
                        .status(TaskStatus.PENDING)
                        .owner(null)
                        .build();
                taskBoardService.updateTask(workerInstance.getSessionId(), task.getTaskId(), updateRequest);
            } catch (Exception ex) {
                log.warn("[WorkerLoop] Failed to reset interrupted task to PENDING: taskId={}", task.getTaskId(), ex);
            }

            workerInstance.completeWork();
            workerRepository.save(workerInstance);
            return TaskExecutionOutcome.INTERRUPTED;
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
            return TaskExecutionOutcome.FAILED;
        }
    }

    private TaskExecutionOutcome executeInstructionMessage(String instructionMessage) {
        log.info("[WorkerLoop] Executing direct instruction: instanceId={}", workerInstance.getInstanceId());
        reportExecutionStatus(
                workerInstance.getInstanceId(),
                AgentExecutionStatus.EXECUTING,
                "worker executing direct instruction"
        );

        try {
            workerInstance.startWorking(0);
            workerRepository.save(workerInstance);

            String instruction = """
                    你是团队模式下的 Worker，正在处理 Team Lead 的直接指令（非任务板任务）。
                    必须按输入内容执行，并在完成后通过 send_message 向 Lead 回复结果。
                    send_message 的目标为 Lead（workerId=0），回复内容应可直接用于 Lead 决策。
                    完成回复后结束本轮，不要重复调用 send_message。
                    """;
            ReactAgent reactAgent = reactAgentFactory.buildWorkerReactAgent(
                    Long.valueOf(workerInstance.getAgentId()),
                    instruction,
                    MAX_REACT_ITERATIONS,
                    workerInstance.getSessionId(),
                    "direct_instruction",
                    null,
                    workerInstance.getInstanceId()
            );

            RunnableConfig config = buildWorkerConfig(0);
            long streamStartTimeMs = System.currentTimeMillis();
            long[] firstChunkLatencyMs = new long[]{-1L};
            AtomicBoolean firstChunkObserved = new AtomicBoolean(false);
            AtomicInteger streamedChunkCount = new AtomicInteger(0);
            int inputChars = instructionMessage == null ? 0 : instructionMessage.length();
            log.info("[WorkerLoop] LLM direct stream started: instanceId={}, inputChars={}",
                    workerInstance.getInstanceId(), inputChars);
            Disposable disposable = reactAgent.stream(instructionMessage, config)
                    .doOnNext(nodeOutput -> {
                        if (nodeOutput instanceof StreamingOutput streamingOutput) {
                            String chunk = streamingOutput.chunk();
                            if (chunk != null && !chunk.isEmpty()) {
                                if (firstChunkObserved.compareAndSet(false, true)) {
                                    firstChunkLatencyMs[0] = System.currentTimeMillis() - streamStartTimeMs;
                                    log.info("[WorkerLoop] LLM direct first token: instanceId={}, latencyMs={}",
                                            workerInstance.getInstanceId(), firstChunkLatencyMs[0]);
                                }
                                streamedChunkCount.incrementAndGet();
                                try {
                                    WorkerOutputEvent event = new WorkerOutputEvent(
                                            workerInstance.getSessionId(),
                                            workerInstance.getInstanceId(),
                                            0,
                                            chunk
                                    );
                                    eventBus.publishToWorker(
                                            workerInstance.getSessionId(),
                                            workerInstance.getInstanceId(),
                                            event
                                    );
                                } catch (Exception e) {
                                    log.warn("[WorkerLoop] Failed to publish direct-instruction output event", e);
                                }
                            }
                        }
                    })
                    .doOnComplete(() -> {
                        long totalStreamMs = System.currentTimeMillis() - streamStartTimeMs;
                        log.info("[WorkerLoop] Direct instruction execution completed: instanceId={}, totalMs={}, firstTokenMs={}, chunkCount={}",
                                workerInstance.getInstanceId(), totalStreamMs, firstChunkLatencyMs[0], streamedChunkCount.get());
                    })
                    .doOnError(e -> log.error(
                            "[WorkerLoop] Direct instruction execution error: instanceId={}, elapsedMs={}, firstTokenMs={}, chunkCount={}",
                            workerInstance.getInstanceId(),
                            System.currentTimeMillis() - streamStartTimeMs,
                            firstChunkLatencyMs[0],
                            streamedChunkCount.get(),
                            e
                    ))
                    .subscribe();

            executionTracker.registerDisposable(workerInstance.getSessionId(), disposable);
            workerRoundControlService.register(workerInstance.getInstanceId(), disposable);

            try {
                while (!disposable.isDisposed() && !shutdown.get()) {
                    Thread.sleep(100);
                }
            } finally {
                workerRoundControlService.clear(workerInstance.getInstanceId(), disposable);
            }

            if (shutdown.get()) {
                workerInstance.completeWork();
                workerRepository.save(workerInstance);
                return TaskExecutionOutcome.INTERRUPTED;
            }

            boolean shouldWaitReply = waitIntentService.consumeWaitingReply(workerInstance.getInstanceId());
            if (shouldWaitReply) {
                workerInstance.startWaitingReply(0);
                workerRepository.save(workerInstance);
                return TaskExecutionOutcome.WAITING_FOR_REPLY;
            }

            workerInstance.completeWork();
            workerRepository.save(workerInstance);
            return TaskExecutionOutcome.COMPLETED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workerInstance.completeWork();
            workerRepository.save(workerInstance);
            return TaskExecutionOutcome.INTERRUPTED;
        } catch (Exception e) {
            log.error("[WorkerLoop] Failed to execute direct instruction: instanceId={}",
                    workerInstance.getInstanceId(), e);
            workerInstance.completeWork();
            workerRepository.save(workerInstance);
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

    private String buildTaskRunInput(String taskInstruction, String resumeMessage) {
        String normalizedTask = taskInstruction == null ? "" : taskInstruction.trim();
        String normalizedResume = resumeMessage == null ? "" : resumeMessage.trim();

        if (normalizedTask.isBlank() && normalizedResume.isBlank()) {
            throw new IllegalStateException("Task run input is empty");
        }
        if (normalizedTask.isBlank()) {
            return normalizedResume;
        }
        if (normalizedResume.isBlank()) {
            return normalizedTask;
        }
        if (normalizedTask.equals(normalizedResume)) {
            return normalizedTask;
        }
        return normalizedTask + "\n\n" + normalizedResume;
    }

    private boolean isTaskTerminal(int taskId) {
        try {
            Task latestTask = taskBoardService.getTask(workerInstance.getSessionId(), taskId);
            return latestTask.isCompleted() || latestTask.isFailed();
        } catch (Exception e) {
            log.debug("[WorkerLoop] Failed to check task terminal state: taskId={}", taskId, e);
            return false;
        }
    }

    private String drainPendingTextInputs() {
        if (pendingTextInputs.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        while (!pendingTextInputs.isEmpty()) {
            String text = pendingTextInputs.pollFirst();
            if (text == null || text.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(text);
        }
        return builder.toString();
    }

    private String formatInboundUserInput(TeamMessage message) {
        if (message == null || message.getText() == null) {
            return "";
        }
        String text = message.getText().trim();
        if (text.isBlank()) {
            return "";
        }
        String from = message.getFrom();
        if (from == null || from.isBlank()) {
            return text;
        }
        return "From " + from + ": " + text;
    }

    private String mergeInputs(String first, String second) {
        boolean firstBlank = first == null || first.isBlank();
        boolean secondBlank = second == null || second.isBlank();
        if (firstBlank && secondBlank) {
            return null;
        }
        if (firstBlank) {
            return second;
        }
        if (secondBlank) {
            return first;
        }
        if (first.trim().equals(second.trim())) {
            return first;
        }
        return first + "\n\n" + second;
    }

    private void releaseWorkerCheckpoint() {
        // Team 模式按 worker instance 保留长期记忆，仅在 worker shutdown 时清理 checkpoint。
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
        return teamTaskContextService.buildTaskInstruction(workerInstance.getSessionId(), task);
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

    private void reportExecutionStatus(String instanceId, AgentExecutionStatus status, String detail) {
        if (status == null) {
            return;
        }
        if (status == lastReportedStatus) {
            return;
        }
        executionStateService.setStatus(instanceId, status, detail);
        lastReportedStatus = status;
    }
}
