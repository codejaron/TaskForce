package com.agent.mcp.json;

import com.agent.mcp.McpToolRegistry;
import com.agent.model.McpServerDefinition;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JSON 配置热加载服务
 * 支持通过 JSON 配置文件配置 MCP 工具，并实现文件监听热加载
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JsonMcpConfigService {

    private final McpToolRegistry registry;
    private final ObjectMapper objectMapper;

    @Getter
    @Value("${mcp.json.config-path:./mcp-config.json}")
    private String configPath;

    @Value("${mcp.json.auto-reload:true}")
    private boolean autoReload;

    @Value("${mcp.json.polling-interval:5000}")
    private long pollingInterval;

    private WatchService watchService;
    private ScheduledExecutorService pollingExecutor;
    private FileTime lastModifiedTime;
    private String lastFileHash;

    @Getter
    private final Set<String> loadedServerIds = Collections.synchronizedSet(new HashSet<>());

    /**
     * 启动时加载配置
     */
    @PostConstruct
    public void init() {
        Path configFile = Path.of(configPath);
        if (!Files.exists(configFile)) {
            log.info("[JsonMcpConfig] MCP JSON config not found at: {}, skipping", configPath);
            return;
        }

        // 初始加载
        loadConfig(configFile);

        // 启动文件监听
        if (autoReload) {
            if (isRunningInDocker()) {
                log.info("[JsonMcpConfig] Docker environment detected, using polling mode");
                startPollingWatcher(configFile);
            } else {
                log.info("[JsonMcpConfig] Native environment detected, using WatchService mode");
                startFileWatcher(configFile);
            }
        }
    }

    /**
     * 加载配置文件
     */
    public void loadConfig(Path configFile) {
        try {
            String json = Files.readString(configFile);
            McpConfig config = objectMapper.readValue(json, McpConfig.class);

            log.info("[JsonMcpConfig] Loading MCP config from: {}", configFile);

            // 注销旧的服务器（已被移除的）
            Set<String> currentConfigServers = new HashSet<>();
            for (String key : config.getMcpServers().keySet()) {
                currentConfigServers.add("json::" + key);
            }

            synchronized (loadedServerIds) {
                for (String serverId : new HashSet<>(loadedServerIds)) {
                    if (!currentConfigServers.contains(serverId)) {
                        registry.unregisterServer(serverId);
                        loadedServerIds.remove(serverId);
                        log.info("[JsonMcpConfig] Unregistered removed server: {}", serverId);
                    }
                }
            }

            // 注册/更新服务器
            for (Map.Entry<String, McpConfig.ServerConfig> entry : config.getMcpServers().entrySet()) {
                String serverId = "json::" + entry.getKey();
                McpConfig.ServerConfig serverCfg = entry.getValue();

                if (!serverCfg.isEnabled()) {
                    registry.unregisterServer(serverId);
                    loadedServerIds.remove(serverId);
                    log.info("[JsonMcpConfig] Unregistered disabled server: {}", serverId);
                    continue;
                }

                try {
                    McpServerDefinition definition = buildDefinition(serverId, serverCfg);
                    registry.registerServer(definition);
                    loadedServerIds.add(serverId);

                    log.info("[JsonMcpConfig] Registered JSON MCP server: {} ({})",
                            serverId, serverCfg.getDescription());
                } catch (Exception e) {
                    log.error("[JsonMcpConfig] Failed to register server: {}", serverId, e);
                }
            }

            log.info("[JsonMcpConfig] Loaded {} MCP servers from JSON config", loadedServerIds.size());

        } catch (IOException e) {
            log.error("[JsonMcpConfig] Failed to load MCP JSON config: {}", e.getMessage(), e);
        }
    }

    /**
     * 构建 McpServerDefinition
     */
    private McpServerDefinition buildDefinition(String serverId, McpConfig.ServerConfig config) {
        // 解析环境变量
        Map<String, String> env = resolveEnv(config.getEnv());

        // 构建启动参数
        List<String> args = new ArrayList<>();
        args.add("-c");  // /bin/sh -c

        StringBuilder cmd = new StringBuilder();

        // 添加环境变量
        if (!env.isEmpty()) {
            for (Map.Entry<String, String> e : env.entrySet()) {
                cmd.append(e.getKey()).append("=").append(escapeShellArg(e.getValue())).append(" ");
            }
        }

        // 添加命令
        cmd.append(escapeShellArg(config.getCommand()));
        for (String arg : config.getArgs()) {
            cmd.append(" ").append(escapeShellArg(arg));
        }

        args.add(cmd.toString());

        return McpServerDefinition.builder()
                .id(serverId)
                .name(serverId.replace("json::", ""))
                .type(McpServerDefinition.McpServerType.STDIO)
                .command("/bin/sh")
                .args(args)
                .description(config.getDescription())
                .build();
    }

    /**
     * 解析环境变量（支持 ${VAR} 占位符）
     */
    private Map<String, String> resolveEnv(Map<String, String> env) {
        if (env == null) return Collections.emptyMap();

        Map<String, String> resolved = new HashMap<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            String value = entry.getValue();
            // 替换 ${VAR} 为系统环境变量
            if (value.startsWith("${") && value.endsWith("}")) {
                String varName = value.substring(2, value.length() - 1);
                value = System.getenv(varName);
                if (value == null) {
                    log.warn("[JsonMcpConfig] Environment variable not found: {}", varName);
                    continue;
                }
            }
            resolved.put(entry.getKey(), value);
        }
        return resolved;
    }

    /**
     * Shell 参数转义
     */
    private String escapeShellArg(String arg) {
        return "'" + arg.replace("'", "'\\''") + "'";
    }

    /**
     * 检测是否在 Docker 容器中运行
     */
    private boolean isRunningInDocker() {
        // 方法1: 检查 /.dockerenv 文件
        if (Files.exists(Path.of("/.dockerenv"))) {
            return true;
        }

        // 方法2: 检查 /proc/1/cgroup
        try {
            Path cgroupPath = Path.of("/proc/1/cgroup");
            if (Files.exists(cgroupPath)) {
                String content = Files.readString(cgroupPath);
                if (content.contains("docker") || content.contains("containerd")) {
                    return true;
                }
            }
        } catch (IOException e) {
            // 忽略错误
        }

        // 方法3: 检查环境变量
        return "true".equalsIgnoreCase(System.getenv("CONTAINER_ENV"));
    }

    /**
     * 启动文件监听（热加载）
     */
    private void startFileWatcher(Path configFile) {
        try {
            watchService = FileSystems.getDefault().newWatchService();
            Path dir = configFile.getParent();
            if (dir == null) {
                dir = Path.of(".");
            }
            dir.register(watchService, StandardWatchEventKinds.ENTRY_MODIFY);

            Thread watchThread = new Thread(() -> {
                while (true) {
                    try {
                        WatchKey key = watchService.take();
                        for (WatchEvent<?> event : key.pollEvents()) {
                            Path changed = (Path) event.context();
                            if (changed.equals(configFile.getFileName())) {
                                log.info("[JsonMcpConfig] MCP config file changed, reloading...");
                                Thread.sleep(500);  // 防抖
                                loadConfig(configFile);
                            }
                        }
                        key.reset();
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "mcp-json-config-watcher");
            watchThread.setDaemon(true);
            watchThread.start();

            log.info("[JsonMcpConfig] Started file watcher for MCP config: {}", configPath);

        } catch (Exception e) {
            log.error("[JsonMcpConfig] Failed to start file watcher", e);
        }
    }

    /**
     * 启动轮询监听（用于 Docker 环境）
     */
    private void startPollingWatcher(Path configFile) {
        try {
            // 记录初始修改时间和文件哈希
            lastModifiedTime = Files.getLastModifiedTime(configFile);
            lastFileHash = calculateFileHash(configFile);

            // 创建单线程调度器
            pollingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "mcp-json-config-polling");
                thread.setDaemon(true);
                return thread;
            });

            // 定时检查文件修改
            pollingExecutor.scheduleWithFixedDelay(() -> {
                try {
                    boolean fileChanged = false;

                    // 方法1: 检查修改时间
                    FileTime currentModifiedTime = Files.getLastModifiedTime(configFile);
                    if (currentModifiedTime.compareTo(lastModifiedTime) > 0) {
                        fileChanged = true;
                        lastModifiedTime = currentModifiedTime;
                    }

                    // 方法2: 检查文件内容哈希（Docker volume 挂载时修改时间可能不更新）
                    String currentHash = calculateFileHash(configFile);
                    if (!currentHash.equals(lastFileHash)) {
                        fileChanged = true;
                        lastFileHash = currentHash;
                    }

                    if (fileChanged) {
                        log.info("[JsonMcpConfig] MCP config file changed (polling detected), reloading...");
                        Thread.sleep(500);  // 防抖
                        loadConfig(configFile);
                    }
                } catch (Exception e) {
                    log.error("[JsonMcpConfig] Error checking config file", e);
                }
            }, pollingInterval, pollingInterval, TimeUnit.MILLISECONDS);

            log.info("[JsonMcpConfig] Started polling watcher for MCP config: {} (interval: {}ms)",
                     configPath, pollingInterval);

        } catch (Exception e) {
            log.error("[JsonMcpConfig] Failed to start polling watcher", e);
        }
    }

    /**
     * 计算文件内容的 SHA-256 哈希值
     */
    private String calculateFileHash(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fileBytes = Files.readAllBytes(file);
            byte[] hashBytes = digest.digest(fileBytes);

            // 转换为十六进制字符串
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("[JsonMcpConfig] Failed to calculate file hash", e);
            return "";
        }
    }

    /**
     * 添加单个工具到配置文件
     * @param toolKey 工具唯一标识（例如: "github"）
     * @param serverConfig 工具配置
     */
    public synchronized void addTool(String toolKey, McpConfig.ServerConfig serverConfig) throws IOException {
        Path configFile = Path.of(configPath);

        // 读取现有配置
        McpConfig config;
        if (Files.exists(configFile)) {
            String json = Files.readString(configFile);
            config = objectMapper.readValue(json, McpConfig.class);
        } else {
            config = new McpConfig();
        }

        // 添加新工具
        config.getMcpServers().put(toolKey, serverConfig);

        // 写回文件（格式化输出）
        String updatedJson = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(config);
        Files.writeString(configFile, updatedJson);

        log.info("[JsonMcpConfig] Added tool: {}", toolKey);
    }

    /**
     * 删除工具
     */
    public synchronized void removeTool(String toolKey) throws IOException {
        Path configFile = Path.of(configPath);
        if (!Files.exists(configFile)) {
            throw new IOException("Config file not found");
        }

        String json = Files.readString(configFile);
        McpConfig config = objectMapper.readValue(json, McpConfig.class);

        if (config.getMcpServers().remove(toolKey) != null) {
            String updatedJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config);
            Files.writeString(configFile, updatedJson);
            log.info("[JsonMcpConfig] Removed tool: {}", toolKey);
        } else {
            throw new IllegalArgumentException("Tool not found: " + toolKey);
        }
    }

    /**
     * 更新工具配置
     */
    public synchronized void updateTool(String toolKey, McpConfig.ServerConfig serverConfig) throws IOException {
        Path configFile = Path.of(configPath);
        if (!Files.exists(configFile)) {
            throw new IOException("Config file not found");
        }

        String json = Files.readString(configFile);
        McpConfig config = objectMapper.readValue(json, McpConfig.class);

        if (!config.getMcpServers().containsKey(toolKey)) {
            throw new IllegalArgumentException("Tool not found: " + toolKey);
        }

        config.getMcpServers().put(toolKey, serverConfig);

        String updatedJson = objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(config);
        Files.writeString(configFile, updatedJson);

        log.info("[JsonMcpConfig] Updated tool: {}", toolKey);
    }

    /**
     * 获取当前所有工具配置
     */
    public McpConfig getCurrentConfig() throws IOException {
        Path configFile = Path.of(configPath);
        if (!Files.exists(configFile)) {
            return new McpConfig();
        }

        String json = Files.readString(configFile);
        return objectMapper.readValue(json, McpConfig.class);
    }

    @PreDestroy
    public void cleanup() {
        // 关闭 WatchService
        if (watchService != null) {
            try {
                watchService.close();
                log.info("[JsonMcpConfig] WatchService closed");
            } catch (Exception e) {
                log.error("[JsonMcpConfig] Failed to close watch service", e);
            }
        }

        // 关闭轮询执行器
        if (pollingExecutor != null) {
            pollingExecutor.shutdown();
            try {
                if (!pollingExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    pollingExecutor.shutdownNow();
                }
                log.info("[JsonMcpConfig] Polling executor closed");
            } catch (InterruptedException e) {
                pollingExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
