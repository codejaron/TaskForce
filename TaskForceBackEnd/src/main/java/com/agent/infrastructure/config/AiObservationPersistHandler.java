package com.agent.infrastructure.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AiObservationPersistHandler implements ObservationHandler<ChatModelObservationContext> {

    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof ChatModelObservationContext;
    }

    @Override
    public void onStart(ChatModelObservationContext context) {
        if (context.getRequest() == null) return;

        StringBuilder sb = new StringBuilder();
        sb.append("\n========== AI PROMPT ==========\n");

        if (context.getRequest().getOptions() != null) {
            sb.append("Model: ").append(context.getRequest().getOptions().getModel()).append("\n");
        }

        if (context.getRequest().getInstructions() != null) {
            for (var msg : context.getRequest().getInstructions()) {
                sb.append("\n[").append(msg.getMessageType()).append("]\n");
                sb.append(msg.getText()).append("\n");
            }
        }

        // ★ 打印工具定义
        try {
            var options = context.getRequest().getOptions();
            if (options != null) {
                var method = options.getClass().getMethod("getToolNames");
                @SuppressWarnings("unchecked")
                var toolNames = (java.util.Set<String>) method.invoke(options);
                if (toolNames != null && !toolNames.isEmpty()) {
                    sb.append("\n[TOOLS]\n");
                    for (String name : toolNames) {
                        sb.append("  - ").append(name).append("\n");
                    }
                }
            }
        } catch (Exception ignored) {}

        sb.append("\n===============================");
        log.info(sb.toString());
    }

}
