package com.agent.domain.taskboard.validator;

import com.agent.domain.taskboard.model.Task;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * 任务依赖关系校验器
 * 用于校验任务的依赖关系是否构成有效的 DAG（有向无环图）
 */
@Slf4j
public class TaskValidator {

    /**
     * 校验结果
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult success() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }

    /**
     * 校验任务列表的依赖关系
     *
     * @param tasks 任务列表
     * @return 校验结果
     */
    public static ValidationResult validate(List<Task> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return ValidationResult.failure("任务列表为空");
        }

        // 1. 构建任务映射
        Map<String, Task> taskMap = new HashMap<>();
        for (Task task : tasks) {
            taskMap.put(task.getTaskId(), task);
        }

        // 2. 检查依赖的任务是否存在
        for (Task task : tasks) {
            if (task.getBlockedBy() != null) {
                for (String blockedByTaskId : task.getBlockedBy()) {
                    if (!taskMap.containsKey(blockedByTaskId)) {
                        return ValidationResult.failure(
                                String.format("任务 '%s' 依赖的任务 ID '%s' 不存在",
                                        task.getTaskId(), blockedByTaskId)
                        );
                    }
                }
            }
        }

        // 3. 检测环
        if (hasCycle(tasks, taskMap)) {
            return ValidationResult.failure("检测到循环依赖");
        }

        log.info("[TaskValidator] 校验通过，共 {} 个任务", tasks.size());

        return ValidationResult.success();
    }

    /**
     * 检测是否存在环（使用 DFS）
     */
    private static boolean hasCycle(List<Task> tasks, Map<String, Task> taskMap) {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (Task task : tasks) {
            if (hasCycleDFS(task.getTaskId(), taskMap, visited, recursionStack)) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasCycleDFS(String taskId, Map<String, Task> taskMap,
                                       Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(taskId)) {
            return true; // 发现环
        }

        if (visited.contains(taskId)) {
            return false; // 已访问过，无环
        }

        visited.add(taskId);
        recursionStack.add(taskId);

        Task task = taskMap.get(taskId);
        if (task.getBlockedBy() != null) {
            for (String blockedByTaskId : task.getBlockedBy()) {
                if (hasCycleDFS(blockedByTaskId, taskMap, visited, recursionStack)) {
                    return true;
                }
            }
        }

        recursionStack.remove(taskId);
        return false;
    }

    /**
     * 校验单个任务的依赖关系是否有效
     *
     * @param task 任务
     * @param allTasks 所有任务列表
     * @return 校验结果
     */
    public static ValidationResult validateTask(Task task, List<Task> allTasks) {
        if (task == null) {
            return ValidationResult.failure("任务为空");
        }

        Map<String, Task> taskMap = new HashMap<>();
        for (Task t : allTasks) {
            taskMap.put(t.getTaskId(), t);
        }

        // 检查依赖的任务是否存在
        if (task.getBlockedBy() != null) {
            for (String blockedByTaskId : task.getBlockedBy()) {
                if (!taskMap.containsKey(blockedByTaskId)) {
                    return ValidationResult.failure(
                            String.format("依赖的任务 ID '%s' 不存在", blockedByTaskId)
                    );
                }
            }
        }

        return ValidationResult.success();
    }
}
