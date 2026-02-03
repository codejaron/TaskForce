package com.agent.domain.context.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 步骤摘要
 * 负责解析 summary.md 文件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StepSummary {
    
    private String stepTitle;
    private String conclusion;
    @Builder.Default
    private List<String> findings = new ArrayList<>();
    private String nextSuggestion;
    
    /**
     * 从 Markdown 内容解析摘要
     */
    public static StepSummary parse(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return new StepSummary();
        }
        
        StepSummary summary = new StepSummary();
        
        // 提取标题（第一个 # 标题）
        Pattern titlePattern = Pattern.compile("^#\\s+(.+)$", Pattern.MULTILINE);
        Matcher titleMatcher = titlePattern.matcher(markdown);
        if (titleMatcher.find()) {
            summary.setStepTitle(titleMatcher.group(1).trim());
        }
        
        // 提取结论（## 结论 后的内容）
        summary.setConclusion(extractSection(markdown, "结论"));
        
        // 提取关键发现（## 关键发现 后的列表项）
        String findingsSection = extractSection(markdown, "关键发现");
        if (findingsSection != null) {
            summary.setFindings(extractListItems(findingsSection));
        }
        
        // 提取下一步建议（## 下一步建议 后的内容）
        summary.setNextSuggestion(extractSection(markdown, "下一步建议"));
        
        return summary;
    }
    
    /**
     * 提取章节内容
     */
    private static String extractSection(String markdown, String sectionName) {
        Pattern pattern = Pattern.compile(
            "##\\s+" + Pattern.quote(sectionName) + "\\s*\\n([\\s\\S]*?)(?=\\n##|\\z)",
            Pattern.MULTILINE
        );
        Matcher matcher = pattern.matcher(markdown);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }
    
    /**
     * 提取列表项
     */
    private static List<String> extractListItems(String content) {
        List<String> items = new ArrayList<>();
        Pattern pattern = Pattern.compile("^[-*]\\s+(.+)$", Pattern.MULTILINE);
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            items.add(matcher.group(1).trim());
        }
        return items;
    }
}
