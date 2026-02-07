package com.agent.domain.orchestration.graph.config;

import com.agent.domain.context.assembly.ContextAssembler;
import com.agent.domain.context.service.ContextService;
import com.agent.domain.orchestration.graph.node.DynamicExecutorNode;
import com.agent.domain.orchestration.state.StateManager;
import com.agent.infrastructure.agent.ReactAgentFactory;
import com.agent.infrastructure.event.EventBus;
import com.agent.infrastructure.prompt.PromptManager;
import com.agent.service.SessionStopService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 动态执行器配置
 */
@Configuration
@RequiredArgsConstructor
public class DynamicExecutorConfiguration {

    private final StateManager stateManager;
    private final EventBus eventBus;
    private final ContextService contextService;
    private final ContextAssembler contextAssembler;
    private final SessionStopService sessionStopService;
    private final ReactAgentFactory reactAgentFactory;
    private final PromptManager promptManager;

    @Bean
    public DynamicExecutorNode dynamicExecutorNode() {
        return new DynamicExecutorNode(
                stateManager,
                eventBus,
                contextService,
                contextAssembler,
                sessionStopService,
                reactAgentFactory,
                promptManager
        );
    }
}
