package com.agent.mcpserver.tool.support;

import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Edit 替换器，按 OpenCode edit.ts 的 9 层降级策略翻译。
 */
@Component
public class EditReplacer {

    private static final double SINGLE_CANDIDATE_SIMILARITY_THRESHOLD = 0.0;
    private static final double MULTIPLE_CANDIDATES_SIMILARITY_THRESHOLD = 0.3;

    public ReplaceResult replace(String content, String oldString, String newString, boolean replaceAll) {
        String source = content == null ? "" : content;
        String find = oldString == null ? "" : oldString;
        String replacement = newString == null ? "" : newString;

        if (find.equals(replacement)) {
            return ReplaceResult.failed("No changes to apply: oldString and newString are identical.");
        }

        boolean notFound = true;

        List<NamedReplacer> replacers = List.of(
                new NamedReplacer("SimpleReplacer", this::simpleReplacer),
                new NamedReplacer("LineTrimmedReplacer", this::lineTrimmedReplacer),
                new NamedReplacer("BlockAnchorReplacer", this::blockAnchorReplacer),
                new NamedReplacer("WhitespaceNormalizedReplacer", this::whitespaceNormalizedReplacer),
                new NamedReplacer("IndentationFlexibleReplacer", this::indentationFlexibleReplacer),
                new NamedReplacer("EscapeNormalizedReplacer", this::escapeNormalizedReplacer),
                new NamedReplacer("TrimmedBoundaryReplacer", this::trimmedBoundaryReplacer),
                new NamedReplacer("ContextAwareReplacer", this::contextAwareReplacer),
                new NamedReplacer("MultiOccurrenceReplacer", this::multiOccurrenceReplacer)
        );

        for (NamedReplacer replacer : replacers) {
            for (String search : replacer.replacer().apply(source, find)) {
                int index = source.indexOf(search);
                if (index == -1) {
                    continue;
                }
                notFound = false;

                if (replaceAll) {
                    int replacedCount = countOccurrences(source, search);
                    return ReplaceResult.matched(
                            source.replace(search, replacement),
                            replacedCount,
                            replacer.name()
                    );
                }

                int lastIndex = source.lastIndexOf(search);
                if (index != lastIndex) {
                    continue;
                }

                String updated = source.substring(0, index)
                        + replacement
                        + source.substring(index + search.length());
                return ReplaceResult.matched(updated, 1, replacer.name());
            }
        }

        if (notFound) {
            return ReplaceResult.failed(
                    "Could not find oldString in the file. It must match exactly, including whitespace, indentation, and line endings."
            );
        }

        return ReplaceResult.multiple();
    }

    private List<String> simpleReplacer(String content, String find) {
        return List.of(find);
    }

    private List<String> lineTrimmedReplacer(String content, String find) {
        String[] originalLines = splitLines(content);
        List<String> searchLines = new ArrayList<>(Arrays.asList(splitLines(find)));

        if (!searchLines.isEmpty() && searchLines.get(searchLines.size() - 1).isEmpty()) {
            searchLines.remove(searchLines.size() - 1);
        }

        if (searchLines.isEmpty() || originalLines.length < searchLines.size()) {
            return List.of();
        }

        List<String> matches = new ArrayList<>();

        for (int i = 0; i <= originalLines.length - searchLines.size(); i++) {
            boolean matched = true;

            for (int j = 0; j < searchLines.size(); j++) {
                String originalTrimmed = originalLines[i + j].trim();
                String searchTrimmed = searchLines.get(j).trim();
                if (!originalTrimmed.equals(searchTrimmed)) {
                    matched = false;
                    break;
                }
            }

            if (!matched) {
                continue;
            }

            int matchStartIndex = 0;
            for (int k = 0; k < i; k++) {
                matchStartIndex += originalLines[k].length() + 1;
            }

            int matchEndIndex = matchStartIndex;
            for (int k = 0; k < searchLines.size(); k++) {
                matchEndIndex += originalLines[i + k].length();
                if (k < searchLines.size() - 1) {
                    matchEndIndex += 1;
                }
            }

            matches.add(content.substring(matchStartIndex, matchEndIndex));
        }

        return matches;
    }

    private List<String> blockAnchorReplacer(String content, String find) {
        String[] originalLines = splitLines(content);
        List<String> searchLines = new ArrayList<>(Arrays.asList(splitLines(find)));

        if (searchLines.size() < 3) {
            return List.of();
        }

        if (!searchLines.isEmpty() && searchLines.get(searchLines.size() - 1).isEmpty()) {
            searchLines.remove(searchLines.size() - 1);
        }

        if (searchLines.isEmpty()) {
            return List.of();
        }

        String firstLineSearch = searchLines.get(0).trim();
        String lastLineSearch = searchLines.get(searchLines.size() - 1).trim();
        int searchBlockSize = searchLines.size();

        List<Candidate> candidates = new ArrayList<>();

        for (int i = 0; i < originalLines.length; i++) {
            if (!originalLines[i].trim().equals(firstLineSearch)) {
                continue;
            }

            for (int j = i + 2; j < originalLines.length; j++) {
                if (originalLines[j].trim().equals(lastLineSearch)) {
                    candidates.add(new Candidate(i, j));
                    break;
                }
            }
        }

        if (candidates.isEmpty()) {
            return List.of();
        }

        // 单候选路径
        if (candidates.size() == 1) {
            Candidate candidate = candidates.get(0);
            int startLine = candidate.startLine();
            int endLine = candidate.endLine();
            int actualBlockSize = endLine - startLine + 1;

            double similarity = 0;
            int linesToCheck = Math.min(searchBlockSize - 2, actualBlockSize - 2);

            if (linesToCheck > 0) {
                for (int j = 1; j < searchBlockSize - 1 && j < actualBlockSize - 1; j++) {
                    String originalLine = originalLines[startLine + j].trim();
                    String searchLine = searchLines.get(j).trim();
                    int maxLen = Math.max(originalLine.length(), searchLine.length());
                    if (maxLen == 0) {
                        continue;
                    }
                    int distance = levenshtein(originalLine, searchLine);
                    similarity += (1.0 - ((double) distance / maxLen)) / linesToCheck;

                    if (similarity >= SINGLE_CANDIDATE_SIMILARITY_THRESHOLD) {
                        break;
                    }
                }
            } else {
                similarity = 1.0;
            }

            if (similarity >= SINGLE_CANDIDATE_SIMILARITY_THRESHOLD) {
                return List.of(extractBlock(content, originalLines, startLine, endLine));
            }
            return List.of();
        }

        // 多候选路径
        Candidate bestMatch = null;
        double maxSimilarity = -1;

        for (Candidate candidate : candidates) {
            int startLine = candidate.startLine();
            int endLine = candidate.endLine();
            int actualBlockSize = endLine - startLine + 1;

            double similarity = 0;
            int linesToCheck = Math.min(searchBlockSize - 2, actualBlockSize - 2);

            if (linesToCheck > 0) {
                for (int j = 1; j < searchBlockSize - 1 && j < actualBlockSize - 1; j++) {
                    String originalLine = originalLines[startLine + j].trim();
                    String searchLine = searchLines.get(j).trim();
                    int maxLen = Math.max(originalLine.length(), searchLine.length());
                    if (maxLen == 0) {
                        continue;
                    }
                    int distance = levenshtein(originalLine, searchLine);
                    similarity += 1.0 - ((double) distance / maxLen);
                }
                similarity /= linesToCheck;
            } else {
                similarity = 1.0;
            }

            if (similarity > maxSimilarity) {
                maxSimilarity = similarity;
                bestMatch = candidate;
            }
        }

        if (maxSimilarity >= MULTIPLE_CANDIDATES_SIMILARITY_THRESHOLD && bestMatch != null) {
            return List.of(extractBlock(content, originalLines, bestMatch.startLine(), bestMatch.endLine()));
        }

        return List.of();
    }

    private List<String> whitespaceNormalizedReplacer(String content, String find) {
        String normalizedFind = normalizeWhitespace(find);
        List<String> matches = new ArrayList<>();

        String[] lines = splitLines(content);
        for (String line : lines) {
            if (normalizeWhitespace(line).equals(normalizedFind)) {
                matches.add(line);
            } else {
                String normalizedLine = normalizeWhitespace(line);
                if (normalizedLine.contains(normalizedFind)) {
                    String trimmed = find.trim();
                    if (!trimmed.isEmpty()) {
                        String[] words = trimmed.split("\\s+");
                        StringBuilder patternBuilder = new StringBuilder();
                        for (int i = 0; i < words.length; i++) {
                            if (i > 0) {
                                patternBuilder.append("\\\\s+");
                            }
                            patternBuilder.append(Pattern.quote(words[i]));
                        }
                        try {
                            Pattern pattern = Pattern.compile(patternBuilder.toString());
                            Matcher matcher = pattern.matcher(line);
                            if (matcher.find()) {
                                matches.add(matcher.group());
                            }
                        } catch (Exception ignored) {
                            // Invalid regex, skip
                        }
                    }
                }
            }
        }

        String[] findLines = splitLines(find);
        if (findLines.length > 1) {
            for (int i = 0; i <= lines.length - findLines.length; i++) {
                String block = joinLines(lines, i, i + findLines.length);
                if (normalizeWhitespace(block).equals(normalizedFind)) {
                    matches.add(block);
                }
            }
        }

        return matches;
    }

    private List<String> indentationFlexibleReplacer(String content, String find) {
        String normalizedFind = removeIndentation(find);
        String[] contentLines = splitLines(content);
        String[] findLines = splitLines(find);

        if (findLines.length == 0 || contentLines.length < findLines.length) {
            return List.of();
        }

        List<String> matches = new ArrayList<>();
        for (int i = 0; i <= contentLines.length - findLines.length; i++) {
            String block = joinLines(contentLines, i, i + findLines.length);
            if (removeIndentation(block).equals(normalizedFind)) {
                matches.add(block);
            }
        }

        return matches;
    }

    private List<String> escapeNormalizedReplacer(String content, String find) {
        String unescapedFind = unescapeString(find);
        List<String> matches = new ArrayList<>();

        if (content.contains(unescapedFind)) {
            matches.add(unescapedFind);
        }

        String[] lines = splitLines(content);
        String[] findLines = splitLines(unescapedFind);

        for (int i = 0; i <= lines.length - findLines.length; i++) {
            String block = joinLines(lines, i, i + findLines.length);
            String unescapedBlock = unescapeString(block);
            if (unescapedBlock.equals(unescapedFind)) {
                matches.add(block);
            }
        }

        return matches;
    }

    private List<String> multiOccurrenceReplacer(String content, String find) {
        List<String> matches = new ArrayList<>();
        int startIndex = 0;

        while (true) {
            int index = content.indexOf(find, startIndex);
            if (index == -1) {
                break;
            }
            matches.add(find);
            startIndex = index + find.length();
        }

        return matches;
    }

    private List<String> trimmedBoundaryReplacer(String content, String find) {
        String trimmedFind = find.trim();
        if (trimmedFind.equals(find)) {
            return List.of();
        }

        List<String> matches = new ArrayList<>();
        if (content.contains(trimmedFind)) {
            matches.add(trimmedFind);
        }

        String[] lines = splitLines(content);
        String[] findLines = splitLines(find);

        for (int i = 0; i <= lines.length - findLines.length; i++) {
            String block = joinLines(lines, i, i + findLines.length);
            if (block.trim().equals(trimmedFind)) {
                matches.add(block);
            }
        }

        return matches;
    }

    private List<String> contextAwareReplacer(String content, String find) {
        List<String> findLinesList = new ArrayList<>(Arrays.asList(splitLines(find)));
        if (findLinesList.size() < 3) {
            return List.of();
        }

        if (!findLinesList.isEmpty() && findLinesList.get(findLinesList.size() - 1).isEmpty()) {
            findLinesList.remove(findLinesList.size() - 1);
        }

        if (findLinesList.size() < 3) {
            return List.of();
        }

        String[] contentLines = splitLines(content);
        String firstLine = findLinesList.get(0).trim();
        String lastLine = findLinesList.get(findLinesList.size() - 1).trim();

        List<String> matches = new ArrayList<>();

        for (int i = 0; i < contentLines.length; i++) {
            if (!contentLines[i].trim().equals(firstLine)) {
                continue;
            }

            for (int j = i + 2; j < contentLines.length; j++) {
                if (!contentLines[j].trim().equals(lastLine)) {
                    continue;
                }

                int blockSize = j - i + 1;
                if (blockSize == findLinesList.size()) {
                    int matchingLines = 0;
                    int totalNonEmptyLines = 0;

                    for (int k = 1; k < blockSize - 1; k++) {
                        String blockLine = contentLines[i + k].trim();
                        String findLine = findLinesList.get(k).trim();

                        if (!blockLine.isEmpty() || !findLine.isEmpty()) {
                            totalNonEmptyLines++;
                            if (blockLine.equals(findLine)) {
                                matchingLines++;
                            }
                        }
                    }

                    if (totalNonEmptyLines == 0 || ((double) matchingLines / totalNonEmptyLines) >= 0.5) {
                        matches.add(joinLines(contentLines, i, j + 1));
                        break;
                    }
                }
                break;
            }
        }

        return matches;
    }

    private String[] splitLines(String text) {
        return text.split("\\n", -1);
    }

    private String joinLines(String[] lines, int start, int endExclusive) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < endExclusive; i++) {
            if (i > start) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }
        return sb.toString();
    }

    private String extractBlock(String content, String[] lines, int startLine, int endLine) {
        int matchStartIndex = 0;
        for (int k = 0; k < startLine; k++) {
            matchStartIndex += lines[k].length() + 1;
        }

        int matchEndIndex = matchStartIndex;
        for (int k = startLine; k <= endLine; k++) {
            matchEndIndex += lines[k].length();
            if (k < endLine) {
                matchEndIndex += 1;
            }
        }

        return content.substring(matchStartIndex, matchEndIndex);
    }

    private String normalizeWhitespace(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private String removeIndentation(String text) {
        String[] lines = splitLines(text);

        List<String> nonEmptyLines = Arrays.stream(lines)
                .filter(line -> !line.trim().isEmpty())
                .toList();
        if (nonEmptyLines.isEmpty()) {
            return text;
        }

        int minIndent = nonEmptyLines.stream()
                .mapToInt(line -> {
                    Matcher matcher = Pattern.compile("^(\\\\s*)").matcher(line);
                    if (matcher.find()) {
                        return matcher.group(1).length();
                    }
                    return 0;
                })
                .min()
                .orElse(0);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append('\n');
            }
            String line = lines[i];
            if (line.trim().isEmpty()) {
                sb.append(line);
            } else {
                int cut = Math.min(minIndent, line.length());
                sb.append(line.substring(cut));
            }
        }
        return sb.toString();
    }

    private String unescapeString(String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char ch = text.charAt(i);
            if (ch != '\\' || i + 1 >= text.length()) {
                sb.append(ch);
                i++;
                continue;
            }

            char next = text.charAt(i + 1);
            switch (next) {
                case 'n' -> {
                    sb.append('\n');
                    i += 2;
                }
                case 't' -> {
                    sb.append('\t');
                    i += 2;
                }
                case 'r' -> {
                    sb.append('\r');
                    i += 2;
                }
                case '\'' -> {
                    sb.append('\'');
                    i += 2;
                }
                case '"' -> {
                    sb.append('"');
                    i += 2;
                }
                case '`' -> {
                    sb.append('`');
                    i += 2;
                }
                case '\\' -> {
                    sb.append('\\');
                    i += 2;
                }
                case '$' -> {
                    sb.append('$');
                    i += 2;
                }
                case '\n' -> {
                    sb.append('\n');
                    i += 2;
                }
                default -> {
                    sb.append(ch);
                    i++;
                }
            }
        }
        return sb.toString();
    }

    private int countOccurrences(String content, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int start = 0;
        while (true) {
            int idx = content.indexOf(needle, start);
            if (idx < 0) {
                break;
            }
            count++;
            start = idx + needle.length();
        }
        return count;
    }

    private int levenshtein(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return Math.max(a.length(), b.length());
        }

        int[][] matrix = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            matrix[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            matrix[0][j] = j;
        }

        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                matrix[i][j] = Math.min(
                        Math.min(matrix[i - 1][j] + 1, matrix[i][j - 1] + 1),
                        matrix[i - 1][j - 1] + cost
                );
            }
        }

        return matrix[a.length()][b.length()];
    }

    private interface Replacer {
        List<String> apply(String content, String find);
    }

    private record NamedReplacer(String name, Replacer replacer) {
    }

    private record Candidate(int startLine, int endLine) {
    }

    @Getter
    public static class ReplaceResult {
        private final boolean matched;
        private final boolean multipleMatches;
        private final String updatedContent;
        private final String strategy;
        private final int replacedCount;
        private final String message;

        private ReplaceResult(
                boolean matched,
                boolean multipleMatches,
                String updatedContent,
                String strategy,
                int replacedCount,
                String message
        ) {
            this.matched = matched;
            this.multipleMatches = multipleMatches;
            this.updatedContent = updatedContent;
            this.strategy = strategy;
            this.replacedCount = replacedCount;
            this.message = message;
        }

        private static ReplaceResult matched(String updatedContent, int replacedCount, String strategy) {
            return new ReplaceResult(true, false, updatedContent, strategy, replacedCount, null);
        }

        private static ReplaceResult multiple() {
            return new ReplaceResult(
                    false,
                    true,
                    null,
                    null,
                    0,
                    "Found multiple matches for oldString. Provide more surrounding context to make the match unique."
            );
        }

        private static ReplaceResult failed(String message) {
            return new ReplaceResult(false, false, null, null, 0, message);
        }
    }
}
