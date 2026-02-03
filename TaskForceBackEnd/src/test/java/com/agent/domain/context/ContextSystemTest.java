package com.agent.domain.context;

import com.agent.domain.context.assembly.ContextAssembler;
import com.agent.domain.context.model.StepSummary;
import com.agent.domain.context.service.ContextService;
import com.agent.domain.context.storage.WorkspaceStorage;
import com.agent.domain.context.tool.ToolFileManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上下文系统集成测试
 */
@SpringBootTest
public class ContextSystemTest {
    
    @Autowired
    private WorkspaceStorage storage;
    
    @Autowired
    private ToolFileManager toolFileManager;
    
    @Autowired
    private ContextService contextService;
    
    @Autowired
    private ContextAssembler assembler;
    
    private String testSessionId;
    
    @BeforeEach
    void setUp() {
        testSessionId = "test-session-" + System.currentTimeMillis();
    }
    
    @Test
    void testWorkspaceStorage() {
        // 测试写入和读取
        String content = "# Test Content\n\nThis is a test.";
        storage.writeFile(testSessionId, "test.md", content);
        
        assertTrue(storage.exists(testSessionId, "test.md"));
        
        String read = storage.readFile(testSessionId, "test.md");
        assertEquals(content, read);
        
        // 测试删除
        storage.deleteFile(testSessionId, "test.md");
        assertFalse(storage.exists(testSessionId, "test.md"));
    }
    
    @Test
    void testToolFileManager() {
        // 测试工具结果保存
        Map<String, Object> args = new HashMap<>();
        args.put("query", "产品经理简历模板");
        
        String result = "{\"results\": [\"template1\", \"template2\"]}";
        
        String path = toolFileManager.saveToolResult(
                testSessionId, 1, "search", args, result
        );
        
        assertEquals("step_001/tools/search_产品经理简历模板.json", path);
        assertTrue(storage.exists(testSessionId, path));
        
        String saved = storage.readFile(testSessionId, path);
        assertEquals(result, saved);
    }
    
    @Test
    void testStepSummaryParsing() {
        // 测试 summary.md 解析
        String markdown = """
                # Step 1: 搜索简历模板
                
                ## 结论
                找到 3 个高质量模板。
                
                ## 关键发现
                - 模板 A 结构清晰
                - 模板 B 内容丰富
                - 模板 C 设计美观
                
                ## 下一步建议
                选择模板 A 作为基础。
                """;
        
        StepSummary summary = StepSummary.parse(markdown);
        
        assertEquals("Step 1: 搜索简历模板", summary.getStepTitle());
        assertEquals("找到 3 个高质量模板。", summary.getConclusion());
        assertEquals(3, summary.getFindings().size());
        assertEquals("模板 A 结构清晰", summary.getFindings().get(0));
        assertEquals("选择模板 A 作为基础。", summary.getNextSuggestion());
    }
    
    @Test
    void testContextAssembly() {
        // 准备测试数据
        String plan = """
                目标：优化用户简历
                步骤：
                1. 搜索模板
                2. 分析简历
                3. 修改简历
                """;
        contextService.savePlan(testSessionId, plan);
        
        // 保存第一步的摘要
        storage.writeFile(testSessionId, "step_001/summary.md", """
                # Step 1: 搜索简历模板
                
                ## 结论
                找到 3 个高质量模板。
                """);
        
        // 保存工具结果
        storage.writeFile(testSessionId, "step_001/tools/search_result.json", 
                "{\"count\": 3}");
        
        // 组装上下文
        String context = assembler.assemble(testSessionId, 2);
        
        // 验证
        assertNotNull(context);
        assertTrue(context.contains("【执行计划】"));
        assertTrue(context.contains("优化用户简历"));
        assertTrue(context.contains("【历史步骤】"));
        assertTrue(context.contains("Step 1: 搜索简历模板"));
        assertTrue(context.contains("找到 3 个高质量模板"));
        assertTrue(context.contains("search_result.json"));
        assertTrue(context.contains("【当前步骤】"));
        assertTrue(context.contains("Step 2"));
    }
    
    @Test
    void testSummaryFallback() {
        // 测试兜底机制
        String output = """
                我已经完成了搜索，找到了 3 个高质量的简历模板。
                
                这些模板都很不错，建议选择第一个。
                """;
        
        contextService.saveStepOutput(testSessionId, 1, output);
        
        // 触发兜底检查
        contextService.checkStepComplete(testSessionId, 1);
        
        // 验证生成了 summary
        assertTrue(storage.exists(testSessionId, "step_001/summary.md"));
        
        String summary = storage.readFile(testSessionId, "step_001/summary.md");
        assertTrue(summary.contains("## 结论"));
        assertTrue(summary.contains("我已经完成了搜索"));
    }
    
    @Test
    void testFileNaming() {
        // 测试各种工具的文件命名
        Map<String, Object> testCases = Map.of(
            "search", Map.of("query", "Java教程"),
            "browser", Map.of("url", "https://www.example.com/page"),
            "shell", Map.of("command", "ls -la /home/user"),
            "file_read", Map.of("path", "/home/user/document.txt")
        );
        
        for (Map.Entry<String, Map<String, Object>> entry : testCases.entrySet()) {
            String toolName = entry.getKey();
            Map<String, Object> args = (Map<String, Object>) entry.getValue();
            
            String path = toolFileManager.saveToolResult(
                    testSessionId, 1, toolName, args, "test result"
            );
            
            assertNotNull(path);
            assertTrue(path.startsWith("step_001/tools/" + toolName + "_"));
            assertTrue(storage.exists(testSessionId, path));
        }
    }
}
