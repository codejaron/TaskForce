package com.agent.domain.worker.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Worker 当前回合执行控制器。
 * 允许外部（如 complete_task 工具）主动终止指定 Worker 的当前执行流。
 */
@Slf4j
@Service
public class WorkerRoundControlService {

    private final ConcurrentMap<String, Disposable> activeRounds = new ConcurrentHashMap<>();

    public void register(String instanceId, Disposable disposable) {
        if (instanceId == null || disposable == null) {
            return;
        }
        activeRounds.put(instanceId, disposable);
    }

    public void clear(String instanceId, Disposable expected) {
        if (instanceId == null || expected == null) {
            return;
        }
        activeRounds.remove(instanceId, expected);
    }

    public boolean stopCurrentRound(String instanceId, String reason) {
        if (instanceId == null || instanceId.isBlank()) {
            return false;
        }
        Disposable disposable = activeRounds.get(instanceId);
        if (disposable == null || disposable.isDisposed()) {
            return false;
        }
        disposable.dispose();
        log.info("[WorkerRoundControlService] Stopped current worker round: instanceId={}, reason={}",
                instanceId, reason);
        return true;
    }
}

