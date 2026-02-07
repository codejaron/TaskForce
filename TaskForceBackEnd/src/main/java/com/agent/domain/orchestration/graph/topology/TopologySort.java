package com.agent.domain.orchestration.graph.topology;

import com.agent.domain.orchestration.model.PlanStep;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 拓扑排序工具
 * 将 PlanSteps 按依赖关系分层
 */
@Slf4j
public class TopologySort {

    /**
     * 对步骤进行拓扑排序，返回分层结果
     * 每一层的步骤可以并行执行
     *
     * @param steps 所有步骤
     * @return 分层的步骤列表，每层内的步骤可以并行执行
     */
    public static List<List<PlanStep>> sort(List<PlanStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return List.of();
        }

        // 构建步骤索引映射和 ID -> Index 映射
        Map<Integer, PlanStep> stepMap = steps.stream()
                .collect(Collectors.toMap(PlanStep::getStepIndex, s -> s));

        Map<String, Integer> idToIndexMap = steps.stream()
                .collect(Collectors.toMap(PlanStep::getStepId, PlanStep::getStepIndex));

        // 计算每个步骤的入度（依赖数量）
        Map<Integer, Integer> inDegree = new HashMap<>();
        Map<Integer, List<Integer>> dependents = new HashMap<>(); // 依赖关系图

        for (PlanStep step : steps) {
            int stepIndex = step.getStepIndex();
            inDegree.putIfAbsent(stepIndex, 0);
            dependents.putIfAbsent(stepIndex, new ArrayList<>());

            List<String> dependsOn = step.getDependsOn();
            if (dependsOn != null && !dependsOn.isEmpty()) {
                inDegree.put(stepIndex, dependsOn.size());

                // 构建反向依赖图（将步骤ID转换为索引）
                for (String depId : dependsOn) {
                    Integer depIndex = idToIndexMap.get(depId);
                    if (depIndex != null) {
                        dependents.computeIfAbsent(depIndex, k -> new ArrayList<>()).add(stepIndex);
                    } else {
                        log.warn("[TopologySort] Dependency not found: stepIndex={}, dependsOn={}",
                                stepIndex, depId);
                    }
                }
            }
        }

        // Kahn 算法进行拓扑排序
        List<List<PlanStep>> layers = new ArrayList<>();
        Queue<Integer> queue = new LinkedList<>();

        // 找到所有入度为 0 的节点（第一层）
        for (Map.Entry<Integer, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.offer(entry.getKey());
            }
        }

        while (!queue.isEmpty()) {
            int layerSize = queue.size();
            List<PlanStep> currentLayer = new ArrayList<>();

            // 处理当前层的所有节点
            for (int i = 0; i < layerSize; i++) {
                int stepIndex = queue.poll();
                PlanStep step = stepMap.get(stepIndex);
                if (step != null) {
                    currentLayer.add(step);

                    // 更新依赖此节点的其他节点的入度
                    List<Integer> deps = dependents.get(stepIndex);
                    if (deps != null) {
                        for (Integer depIndex : deps) {
                            int newInDegree = inDegree.get(depIndex) - 1;
                            inDegree.put(depIndex, newInDegree);

                            // 如果入度变为 0，加入下一层
                            if (newInDegree == 0) {
                                queue.offer(depIndex);
                            }
                        }
                    }
                }
            }

            if (!currentLayer.isEmpty()) {
                layers.add(currentLayer);
            }
        }

        // 检查是否有环
        long processedCount = layers.stream().mapToLong(List::size).sum();
        if (processedCount < steps.size()) {
            log.error("[TopologySort] Circular dependency detected! Processed: {}, Total: {}",
                    processedCount, steps.size());
            throw new IllegalStateException("Circular dependency detected in plan steps");
        }

        log.info("[TopologySort] Sorted {} steps into {} layers", steps.size(), layers.size());
        for (int i = 0; i < layers.size(); i++) {
            List<Integer> stepIndices = layers.get(i).stream()
                    .map(PlanStep::getStepIndex)
                    .toList();
            log.info("[TopologySort] Layer {}: steps {}", i, stepIndices);
        }

        return layers;
    }
}
