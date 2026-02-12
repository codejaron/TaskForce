package com.agent.infrastructure.observation;

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

        var options = context.getRequest().getOptions();
        if (options != null) {
            sb.append("Model: ").append(options.getModel()).append("\n");
        }

        // 打印消息列表
        if (context.getRequest().getInstructions() != null) {
            for (var msg : context.getRequest().getInstructions()) {
                sb.append("\n[").append(msg.getMessageType()).append("]\n");
                sb.append(msg.getText()).append("\n");
            }
        }

        // 直接用接口获取 tools，不用反射
        if (options instanceof org.springframework.ai.model.tool.ToolCallingChatOptions toolOptions) {
            var callbacks = toolOptions.getToolCallbacks();
            if (callbacks != null && !callbacks.isEmpty()) {
                sb.append("\n[TOOLS] (").append(callbacks.size()).append(")\n");
                for (var cb : callbacks) {
                    var def = cb.getToolDefinition();
                    sb.append("  - ").append(def.name()).append(": ").append(def.description()).append("\n");
                }
            }
            var toolNames = toolOptions.getToolNames();
            if (toolNames != null && !toolNames.isEmpty()) {
                sb.append("[TOOL_NAMES] ").append(toolNames).append("\n");
            }
        }

        sb.append("\n===============================");
        log.info(sb.toString());
    }


}
