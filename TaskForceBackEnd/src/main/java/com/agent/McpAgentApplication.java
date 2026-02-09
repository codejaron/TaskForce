package com.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        io.agentscope.runtime.autoconfigure.A2aAutoConfiguration.class
})
@EnableScheduling
public class McpAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(McpAgentApplication.class, args);
    }
}
