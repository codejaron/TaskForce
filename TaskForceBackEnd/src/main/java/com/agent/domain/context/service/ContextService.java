package com.agent.domain.context.service;

import com.agent.domain.context.assembly.ContextAssembler;
import com.agent.domain.context.assembly.ContextConfig;
import com.agent.domain.context.storage.WorkspaceStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 上下文服务
 * 对外提供上下文管理能力
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextService {
    
    private final WorkspaceStorage storage;
    private final ContextAssembler assembler;
    private final ContextConfig config;
    
    /**
     * 初始化会话工作空间
     * 创建会话文件夹和必要的子目录
     * @param sessionId 会话ID
     */
    public void initializeWorkspace(String sessionId) {
        try {
            log.info("初始化会话工作空间: sessionId={}", sessionId);
            
            // 创建会话根目录和 artifacts 目录
            storage.createDirectory(sessionId);
            
            log.info("会话工作空间初始化完成: sessionId={}", sessionId);
        } catch (Exception e) {
            log.error("初始化会话工作空间失败: sessionId={}", sessionId, e);
            // 不抛出异常，允许继续执行
        }
    }
    
    /**
     * 组装上下文
     * @param sessionId 会话ID
     * @param stepIndex 当前步骤索引
     * @return 组装后的上下文
     */
    public String assemble(String sessionId, int stepIndex) {
        // 确保工作空间已初始化
        ensureWorkspaceInitialized(sessionId);
        return assembler.assemble(sessionId, stepIndex);
    }
    
    /**
     * 保存步骤输出
     * @param sessionId 会话ID
     * @param stepIndex 步骤索引
     * @param output LLM 输出内容
     */
    public void saveStepOutput(String sessionId, int stepIndex, String output) {
        ensureWorkspaceInitialized(sessionId);
        String path = String.format("step_%03d/output.md", stepIndex);
        storage.writeFile(sessionId, path, output);
        log.debug("保存步骤输出: sessionId={}, stepIndex={}", sessionId, stepIndex);
    }
    
    /**
     * 保存计划
     * @param sessionId 会话ID
     * @param plan 计划内容
     */
    public void savePlan(String sessionId, String plan) {
        ensureWorkspaceInitialized(sessionId);
        storage.writeFile(sessionId, "plan.md", plan);
        log.info("保存计划: sessionId={}", sessionId);
    }
    
    /**
     * 步骤完成后检查
     * 如果 LLM 忘记写 summary，自动生成兜底
     * @param sessionId 会话ID
     * @param stepIndex 步骤索引
     */
    public void checkStepComplete(String sessionId, int stepIndex) {
        if (!config.isSummaryFallbackEnabled()) {
            return;
        }
        
        ensureWorkspaceInitialized(sessionId);
        
        String summaryPath = String.format("step_%03d/summary.md", stepIndex);
        
        if (!storage.exists(sessionId, summaryPath)) {
            log.warn("步骤 {} 缺少 summary，生成兜底", stepIndex);
            
            // 从 output 提取第一段作为兜底
            String outputPath = String.format("step_%03d/output.md", stepIndex);
            if (storage.exists(sessionId, outputPath)) {
                String output = storage.readFile(sessionId, outputPath);
                String fallbackSummary = extractFirstParagraph(output);
                
                String markdown = String.format("""
                        # Step %d
                        
                        ## 结论
                        %s
                        
                        > ⚠️ 此摘要为自动生成，建议查看完整输出
                        """, stepIndex, fallbackSummary);
                
                storage.writeFile(sessionId, summaryPath, markdown);
                log.info("已生成兜底 summary: sessionId={}, stepIndex={}", sessionId, stepIndex);
            }
        }
    }
    
    /**
     * 提取第一段文本（作为兜底摘要）
     */
    private String extractFirstParagraph(String text) {
        if (text == null || text.isEmpty()) {
            return "步骤已完成";
        }
        
        // 移除 Markdown 标题
        text = text.replaceAll("^#+\\s+.*$", "").trim();
        
        // 提取第一段（以双换行分隔）
        Pattern pattern = Pattern.compile("^(.+?)(?:\\n\\n|\\z)", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            String paragraph = matcher.group(1).trim();
            // 限制长度
            if (paragraph.length() > 200) {
                paragraph = paragraph.substring(0, 200) + "...";
            }
            return paragraph;
        }
        
        return "步骤已完成";
    }
    
    /**
     * 保存 Artifact
     * @param sessionId 会话ID
     * @param fileName 文件名
     * @param content 内容
     */
    public void saveArtifact(String sessionId, String fileName, String content) {
        ensureWorkspaceInitialized(sessionId);
        String path = "artifacts/" + fileName;
        storage.writeFile(sessionId, path, content);
        log.info("保存 Artifact: sessionId={}, fileName={}", sessionId, fileName);
    }
    
    /**
     * 保存摘要（供 API 调用）
     * @param sessionId 会话ID
     * @param path 文件路径
     * @param content 摘要内容
     */
    public void saveSummary(String sessionId, String path, String content) {
        ensureWorkspaceInitialized(sessionId);
        storage.writeFile(sessionId, path, content);
        log.info("保存摘要: sessionId={}, path={}", sessionId, path);
    }
    
    /**
     * 确保工作空间已初始化
     * 检查会话目录是否存在，避免重复初始化
     */
    private void ensureWorkspaceInitialized(String sessionId) {
        // 检查会话目录是否存在
        if (!storage.directoryExists(sessionId)) {
            initializeWorkspace(sessionId);
        }
    }
}
