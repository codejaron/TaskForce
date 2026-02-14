package com.agent.mcpserver.tool;

import com.agent.mcpserver.tool.support.OutputTruncator;
import com.agent.mcpserver.tool.support.WorkspacePathSupport;
import com.agent.mcpserver.tool.support.WorkspaceToolConfig;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grep 工具。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrepTool {

    private static final int MAX_RESULTS = 100;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final Pattern RG_LINE = Pattern.compile("^(.*?):(\\d+):(.*)$");

    private final WorkspaceToolConfig workspaceConfig;
    private final WorkspacePathSupport workspacePathSupport;
    private final OutputTruncator outputTruncator;

    @McpTool(
            name = "grep",
            descriptionResource = "classpath:description/grep.txt"
    )
    public Map<String, Object> grep(
            @JsonProperty(value = "pattern", required = true) String pattern,
            @JsonProperty("path") String path,
            @JsonProperty("include") String include
    ) {
        Map<String, Object> result = new LinkedHashMap<>();

        if (pattern == null || pattern.isBlank()) {
            result.put("success", false);
            result.put("error", "pattern is required");
            return result;
        }

        Path searchRoot;
        try {
            searchRoot = workspacePathSupport.resolvePath(workspaceConfig, path);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
            return result;
        }

        List<String> command = new ArrayList<>();
        command.add(workspaceConfig.getRipgrepPath());
        command.add("-nH");
        command.add("--hidden");
        command.add("--no-messages");
        command.add("--regexp");
        command.add(pattern);
        if (include != null && !include.isBlank()) {
            command.add("--glob");
            command.add(include);
        }
        command.add(searchRoot.toString());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);

        try {
            Process process = pb.start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                result.put("success", false);
                result.put("error", "grep timed out");
                return result;
            }

            List<String> lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))
                    .lines()
                    .toList();

            int exitCode = process.exitValue();
            if (exitCode == 1 || (exitCode == 2 && lines.isEmpty())) {
                result.put("success", true);
                result.put("matches", 0);
                result.put("output", "No files found");
                return result;
            }
            if (exitCode != 0 && exitCode != 2) {
                result.put("success", false);
                result.put("error", "ripgrep failed: " + String.join("\n", lines));
                return result;
            }

            boolean partialErrors = exitCode == 2;

            List<GrepMatch> matches = new ArrayList<>();
            for (String line : lines) {
                Matcher matcher = RG_LINE.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }

                Path filePath = Path.of(matcher.group(1)).toAbsolutePath().normalize();
                int lineNumber;
                try {
                    lineNumber = Integer.parseInt(matcher.group(2));
                } catch (NumberFormatException ignored) {
                    continue;
                }

                String content = matcher.group(3);
                if (content.length() > MAX_LINE_LENGTH) {
                    content = content.substring(0, MAX_LINE_LENGTH) + "...";
                }

                matches.add(new GrepMatch(filePath, lineNumber, content, fileLastModified(filePath)));
            }

            matches.sort(Comparator.comparingLong(GrepMatch::modifiedAt).reversed());
            boolean truncated = matches.size() > MAX_RESULTS;
            List<GrepMatch> finalMatches = truncated ? matches.subList(0, MAX_RESULTS) : matches;

            if (finalMatches.isEmpty()) {
                result.put("success", true);
                result.put("matches", 0);
                result.put("output", "No files found");
                return result;
            }

            StringBuilder output = new StringBuilder();
            for (GrepMatch match : finalMatches) {
                if (output.length() > 0) {
                    output.append('\n');
                }
                output.append(workspacePathSupport.relativePath(workspaceConfig, match.path()))
                        .append(':')
                        .append(match.line())
                        .append(':')
                        .append(match.content());
            }

            if (truncated) {
                output.append("\n(Results are truncated: showing first ")
                        .append(MAX_RESULTS)
                        .append(" matches.)");
            }
            if (partialErrors) {
                output.append("\n(Some paths were inaccessible and skipped)");
            }

            String finalOutput = outputTruncator.truncate(
                    output.toString(),
                    workspaceConfig.getMaxOutputLines(),
                    workspaceConfig.getMaxOutputBytes(),
                    "(Output truncated. Use a narrower path/pattern.)"
            );

            result.put("success", true);
            result.put("matches", matches.size());
            result.put("truncated", truncated);
            result.put("output", finalOutput);
            return result;
        } catch (IOException e) {
            if (isRipgrepMissing(e)) {
                result.put("success", false);
                result.put("error", "ripgrep not found. Install ripgrep and set workspace.ripgrep-path.");
                return result;
            }
            result.put("success", false);
            result.put("error", "grep failed: " + e.getMessage());
            return result;
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", "grep failed: " + e.getMessage());
            return result;
        }
    }

    private boolean isRipgrepMissing(IOException e) {
        String message = e.getMessage();
        if (message == null) {
            return false;
        }
        return message.contains("Cannot run program") && message.contains(workspaceConfig.getRipgrepPath());
    }

    private long fileLastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    private record GrepMatch(Path path, int line, String content, long modifiedAt) {
    }
}
