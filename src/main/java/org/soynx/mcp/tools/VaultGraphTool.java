package org.soynx.mcp.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.PagedSearchIterable;
import org.soynx.mcp.service.VaultService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class VaultGraphTool {

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?]]");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final VaultService vaultService;

    @Tool(description = "Get all wiki-links [[...]] referenced in a specific note in the KnowledgeGithubMCP vault.")
    public String getOutgoingLinks(
            @ToolParam(description = "File path relative to vault root, e.g. 'Projects/MyProject.md'") String filePath) {
        log.info("[getOutgoingLinks] filePath='{}'", filePath);

        String content = vaultService.getFileContent(filePath);
        if (content.startsWith("ERROR:")) {
            log.warn("[getOutgoingLinks] Could not fetch '{}': {}", filePath, content);
            return content;
        }

        List<String> linkTargets = extractWikiLinks(content);
        log.debug("[getOutgoingLinks] Extracted {} wiki-link(s) from '{}'", linkTargets.size(), filePath);

        if (linkTargets.isEmpty()) {
            return "No wiki-links found in '" + filePath + "'.";
        }

        int existCount = 0, brokenCount = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Outgoing links in '").append(filePath).append("' (").append(linkTargets.size()).append("):\n\n");

        for (String target : linkTargets) {
            String mdPath = target.endsWith(".md") ? target : target + ".md";
            GHContent meta = vaultService.getFileMetadata(mdPath);
            if (meta != null) {
                sb.append("  [EXISTS]   [[").append(target).append("]] → ").append(meta.getPath()).append("\n");
                existCount++;
            } else {
                sb.append("  [BROKEN]   [[").append(target).append("]] (note not found)\n");
                brokenCount++;
            }
        }

        log.info("[getOutgoingLinks] '{}' — {} valid link(s), {} broken link(s)", filePath, existCount, brokenCount);
        return sb.toString().trim();
    }

    @Tool(description = "Find all notes that link TO a specific note (backlinks).")
    public String getIncomingLinks(
            @ToolParam(description = "Note name without extension, e.g. 'MyProject'") String noteName) {
        log.info("[getIncomingLinks] noteName='{}'", noteName);

        if (noteName == null || noteName.isBlank()) {
            log.warn("[getIncomingLinks] Called with empty noteName");
            return "ERROR: noteName must not be empty.";
        }

        String searchQuery = "[[" + noteName + "]]";
        log.debug("[getIncomingLinks] GitHub search query: '{}'", searchQuery);

        PagedSearchIterable<GHContent> results = vaultService.searchContent(searchQuery);
        if (results == null) {
            log.error("[getIncomingLinks] searchContent returned null — likely rate limited");
            return "ERROR: GitHub API rate limit exceeded. Please wait before retrying.";
        }

        List<String> paths = new ArrayList<>();
        try {
            for (GHContent item : results) {
                if (paths.size() >= 50) break;
                if ("file".equals(item.getType()) && item.getName().endsWith(".md")) {
                    log.debug("[getIncomingLinks] Backlink found in: '{}'", item.getPath());
                    paths.add("- " + item.getPath());
                }
            }
        } catch (Exception e) {
            log.error("[getIncomingLinks] Error iterating backlink results for '{}': {}", noteName, e.getMessage(), e);
            return "ERROR: Failed to retrieve backlinks. " + e.getMessage();
        }

        log.info("[getIncomingLinks] Found {} note(s) linking to '{}'", paths.size(), noteName);
        if (paths.isEmpty()) {
            return "No notes found linking to '[[" + noteName + "]]'.";
        }
        return "Notes that link to '[[" + noteName + "]]' (" + paths.size() + "):\n\n" + String.join("\n", paths);
    }

    @Tool(description = "List notes modified in the last N days, sorted by most recent first.")
    public String getRecentlyModified(
            @ToolParam(description = "Number of days to look back, e.g. 7") int days) {
        log.info("[getRecentlyModified] days={}", days);

        if (days < 1 || days > 365) {
            log.warn("[getRecentlyModified] Invalid days value: {}", days);
            return "ERROR: days must be between 1 and 365.";
        }

        Date since = Date.from(Instant.now().minusSeconds((long) days * 86400));
        log.debug("[getRecentlyModified] Querying commits since: {}", since);

        try {
            Map<String, Date> fileToDate = new LinkedHashMap<>();
            int commitCount = 0;

            for (GHCommit commit : vaultService.getRepository().queryCommits().since(since).list()) {
                commitCount++;
                Date commitDate = commit.getCommitDate();
                for (GHCommit.File file : commit.listFiles()) {
                    String path = file.getFileName();
                    if (path.endsWith(".md") && !fileToDate.containsKey(path)) {
                        log.debug("[getRecentlyModified] Modified note: '{}' at {}", path, DATE_FMT.format(commitDate.toInstant()));
                        fileToDate.put(path, commitDate);
                    }
                }
            }

            log.info("[getRecentlyModified] Scanned {} commit(s), found {} unique modified note(s)", commitCount, fileToDate.size());

            if (fileToDate.isEmpty()) {
                return "No notes modified in the last " + days + " day(s).";
            }

            List<Map.Entry<String, Date>> sorted = new ArrayList<>(fileToDate.entrySet());
            sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));

            StringBuilder sb = new StringBuilder();
            sb.append("Notes modified in the last ").append(days).append(" day(s) (").append(sorted.size()).append("):\n\n");
            for (Map.Entry<String, Date> entry : sorted) {
                String ts = DATE_FMT.format(entry.getValue().toInstant());
                sb.append("  ").append(ts).append("  ").append(entry.getKey()).append("\n");
            }
            return sb.toString().trim();

        } catch (IOException e) {
            log.error("[getRecentlyModified] Failed to fetch commits: {}", e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.contains("rate limit")) {
                return "ERROR: GitHub API rate limit exceeded. Please wait before retrying.";
            }
            return "ERROR: Failed to retrieve recently modified notes. " + msg;
        }
    }

    private List<String> extractWikiLinks(String content) {
        List<String> links = new ArrayList<>();
        Matcher matcher = WIKI_LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            if (!target.isBlank() && !links.contains(target)) {
                links.add(target);
            }
        }
        return links;
    }
}
