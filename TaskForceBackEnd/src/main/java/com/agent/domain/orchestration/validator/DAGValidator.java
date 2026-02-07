package com.agent.domain.orchestration.validator;

import com.agent.domain.orchestration.model.PlanStep;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * DAG 校验器
 * 用于校验执行计划的依赖关系是否构成有效的 DAG（有向无环图）
 */
@Slf4j
public class DAGValidator {

    /**
     * 校验结果
     */
    public static class ValidationResult {
        private final boolean valid;
        private final String errorMessage;
        private final Map<String, Integer> layerIndexMap; // stepId -> layerIndex

        private ValidationResult(boolean valid, String errorMessage, Map<String, Integer> layerIndexMap) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.layerIndexMap = layerIndexMap;
        }

        public static ValidationResult success(Map<String, Integer> layerIndexMap) {
            return new ValidationResult(true, null, layerIndexMap);
        }

        public static ValidationResult failure(String errorMessage) {
            return new ValidationResult(false, errorMessage, null);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public Map<String, Integer> getLayerIndexMap() {
            return layerIndexMap;
        }
    }

    /**
     * 校验执行计划的 DAG 结构
     *
     * @param steps 执行计划的步骤列表
     * @return 校验结果
     */
    public static ValidationResult validate(List<PlanStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return ValidationResult.failure("步骤列表为空");
        }

        // 1. 构建 stepIndex -> stepId 映射
        Map<Integer, String> indexToIdMap = new HashMap<>();
        Map<String, PlanStep> stepMap = new HashMap<>();
        for (PlanStep step : steps) {
            indexToIdMap.put(step.getStepIndex(), step.getStepId());
            stepMap.put(step.getStepId(), step);
        }

        // 2. 检查依赖索引是否有效
        for (PlanStep step : steps) {
            if (step.getDependsOn() != null) {
                for (String depStepId : step.getDependsOn()) {
                    if (!stepMap.containsKey(depStepId)) {
                        return ValidationResult.failure(
                                String.format("步骤 %d 依赖的步骤 ID '%s' 不存在",
                                        step.getStepIndex(), depStepId)
                        );
                    }
                }
            }
        }

        // 3. 检测环
        if (hasCycle(steps, stepMap)) {
            return ValidationResult.failure("检测到循环依赖");
        }

        // 4. 拓扑排序并计算层级
        Map<String, Integer> layerIndexMap = topologicalSort(steps, stepMap);
        if (layerIndexMap == null) {
            return ValidationResult.failure("拓扑排序失败");
        }

        log.info("[DAGValidator] 校验通过，共 {} 个步骤，{} 个层级",
                steps.size(), layerIndexMap.values().stream().max(Integer::compareTo).orElse(0) + 1);

        return ValidationResult.success(layerIndexMap);
    }

    /**
     * 检测是否存在环（使用 DFS）
     */
    private static boolean hasCycle(List<PlanStep> steps, Map<String, PlanStep> stepMap) {
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (PlanStep step : steps) {
            if (hasCycleDFS(step.getStepId(), stepMap, visited, recursionStack)) {
                return true;
            }
        }

        return false;
    }

    private static boolean hasCycleDFS(String stepId, Map<String, PlanStep> stepMap,
                                       Set<String> visited, Set<String> recursionStack) {
        if (recursionStack.contains(stepId)) {
            return true; // 发现环
        }

        if (visited.contains(stepId)) {
            return false; // 已访问过，无环
        }

        visited.add(stepId);
        recursionStack.add(stepId);

        PlanStep step = stepMap.get(stepId);
        if (step.getDependsOn() != null) {
            for (String depStepId : step.getDependsOn()) {
                if (hasCycleDFS(depStepId, stepMap, visited, recursionStack)) {
                    return true;
                }
            }
        }

        recursionStack.remove(stepId);
        return false;
    }

    /**
     * 拓扑排序并计算层级索引
     * 使用 Kahn 算法（BFS）
     *
     * @return stepId -> layerIndex 映射
     */
    private static Map<String, Integer> topologicalSort(List<PlanStep> steps, Map<String, PlanStep> stepMap) {
        Map<String, Integer> layerIndexMap = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjacencyList = new HashMap<>();

        // 初始化入度和邻接表
        for (PlanStep step : steps) {
            String stepId = step.getStepId();
            inDegree.put(stepId, 0);
            adjacencyList.put(stepId, new ArrayList<>());
        }

        // 构建邻接表和入度
        for (PlanStep step : steps) {
            String stepId = step.getStepId();
            if (step.getDependsOn() != null) {
                for (String depStepId : step.getDependsOn()) {
                    adjacencyList.get(depStepId).add(stepId);
                    inDegree.put(stepId, inDegree.get(stepId) + 1);
                }
            }
        }

        // BFS 拓扑排序
        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
                layerIndexMap.put(entry.getKey(), 0); // 第 0 层
            }
        }

        int processedCount = 0;
        while (!queue.isEmpty()) {
            String currentStepId = queue.poll();
            processedCount++;

            int currentLayer = layerIndexMap.get(currentStepId);

            for (String nextStepId : adjacencyList.get(currentStepId)) {
                inDegree.put(nextStepId, inDegree.get(nextStepId) - 1);

                // 更新层级（取所有依赖的最大层级 + 1）
                int nextLayer = Math.max(
                        layerIndexMap.getOrDefault(nextStepId, 0),
                        currentLayer + 1
                );
                layerIndexMap.put(nextStepId, nextLayer);

                if (inDegree.get(nextStepId) == 0) {
                    queue.offer(nextStepId);
                }
            }
        }

        // 检查是否所有节点都被处理（如果有环，会有节点未处理）
        if (processedCount != steps.size()) {
            log.error("[DAGValidator] 拓扑排序失败，处理了 {} 个节点，总共 {} 个节点",
                    processedCount, steps.size());
            return null;
        }

        return layerIndexMap;
    }

    /**
     * 降级为串行执行（清空所有 dependsOn）
     */
    public static void degradeToSequential(List<PlanStep> steps) {
        log.warn("[DAGValidator] 降级为串行执行，清空所有 dependsOn");
        for (PlanStep step : steps) {
            step.setDependsOn(null);
            step.setLayerIndex(0); // 所有步骤都在第 0 层
        }
    }
}
