package org.soynx.mcp.knowledgebase.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.PagedSearchIterable;
import org.soynx.mcp.knowledgebase.service.VaultService;
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

/**
 * MCP tool class for navigating the vault's knowledge graph.
 * <p>
 * Provides three tools: extracting outgoing {@code [[wiki-links]]} from a note,
 * finding incoming backlinks to a note, and listing recently modified notes via git history.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VaultGraphTool {

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?]]");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final VaultService vaultService;

    /**
     * Reads a note's content, extracts all {@code [[wiki-links]]}, and verifies which linked notes
     * actually exist in the vault.
     *
     * @param filePath exact vault-relative path to the note, including the {@code .md} extension
     * @return formatted list of links marked {@code [EXISTS]} or {@code [BROKEN]},
     *         or an {@code "ERROR: ..."} string if the note cannot be read
     */
    @Tool(description = """
            Extract all [[wiki-links]] from a note and verify whether each linked note exists in the vault.
            Each link is returned as [EXISTS] with its resolved path, or [BROKEN] if the target note was not found.
            Use this to explore what a note connects to and to follow the knowledge graph outward from a starting note.""")
    public String getOutgoingLinks(
            @ToolParam(description = "Exact file path relative to vault root, e.g. 'Projects/MyProject.md'. Must include the .md extension.") String filePath) {

        log.trace("[getOutgoingLinks] Invoked with filePath='{}'", filePath);
        log.info("[getOutgoingLinks] Extracting outgoing links from '{}'", filePath);

        String content = vaultService.getFileContent(filePath);
        if (content.startsWith("ERROR:")) {
            log.warn("[getOutgoingLinks] Could not fetch '{}': {}", filePath, content);
            return content;
        }
        log.debug("[getOutgoingLinks] Fetched {} chars from '{}'", content.length(), filePath);

        List<String> linkTargets = extractWikiLinks(content);
        log.info("[getOutgoingLinks] Extracted {} unique wiki-link(s) from '{}'", linkTargets.size(), filePath);

        if (linkTargets.isEmpty()) {
            return "No wiki-links found in '" + filePath + "'.";
        }

        int existCount = 0, brokenCount = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Outgoing links in '").append(filePath).append("' (").append(linkTargets.size()).append("):\n\n");

        for (String target : linkTargets) {
            String mdPath = target.endsWith(".md") ? target : target + ".md";
            log.trace("[getOutgoingLinks] Resolving link '[[{}]]' -> '{}'", target, mdPath);
            GHContent meta = vaultService.getFileMetadata(mdPath);
            if (meta != null) {
                log.debug("[getOutgoingLinks] Link '[[{}]]' EXISTS at '{}'", target, meta.getPath());
                sb.append("  [EXISTS]   [[").append(target).append("]] → ").append(meta.getPath()).append("\n");
                existCount++;
            } else {
                log.debug("[getOutgoingLinks] Link '[[{}]]' is BROKEN (note not found)", target);
                sb.append("  [BROKEN]   [[").append(target).append("]] (note not found)\n");
                brokenCount++;
            }
        }

        log.info("[getOutgoingLinks] '{}' — {} valid link(s), {} broken link(s)", filePath, existCount, brokenCount);
        return sb.toString().trim();
    }

    /**
     * Finds all notes in the vault that contain a {@code [[wiki-link]]} pointing to the given note.
     *
     * @param noteName the target note name without folder path and without {@code .md} extension
     * @return formatted list of note paths that link to the given note,
     *         or an {@code "ERROR: ..."} string on failure
     */
    @Tool(description = """
            Find all notes that contain a [[wiki-link]] pointing to the given note — i.e. backlinks / incoming links.
            Use this to discover everything in the vault that references a particular topic or note.
            Pass the note name WITHOUT the .md extension and WITHOUT any folder path — e.g. 'MyProject', not 'Projects/MyProject.md'.""")
    public String getIncomingLinks(
            @ToolParam(description = "Note name only, without folder path and without .md extension. Example: 'MyProject' or 'Kubernetes'.") String noteName) {

        log.trace("[getIncomingLinks] Invoked with noteName='{}'", noteName);

        if (noteName == null || noteName.isBlank()) {
            log.warn("[getIncomingLinks] Called with null or blank noteName");
            return "ERROR: noteName must not be empty.";
        }

        String searchQuery = "[[" + noteName + "]]";
        log.info("[getIncomingLinks] Searching for backlinks to '[[{}]]'", noteName);
        log.debug("[getIncomingLinks] GitHub search query: '{}'", searchQuery);

        PagedSearchIterable<GHContent> results = vaultService.searchContent(searchQuery);
        if (results == null) {
            log.error("[getIncomingLinks] searchContent returned null for noteName='{}' — likely rate limited", noteName);
            return "ERROR: GitHub API rate limit exceeded. Please wait before retrying.";
        }

        List<String> paths = new ArrayList<>();
        try {
            for (GHContent item : results) {
                if (paths.size() >= 50) {
                    log.debug("[getIncomingLinks] Reached 50-result cap for noteName='{}'", noteName);
                    break;
                }
                if ("file".equals(item.getType()) && item.getName().endsWith(".md")) {
                    log.debug("[getIncomingLinks] Backlink found in: '{}'", item.getPath());
                    paths.add("- " + item.getPath());
                } else {
                    log.trace("[getIncomingLinks] Skipping non-md entry: '{}'", item.getPath());
                }
            }
        } catch (Exception e) {
            log.error("[getIncomingLinks] Error iterating backlink results for noteName='{}': {}", noteName, e.getMessage(), e);
            return "ERROR: Failed to retrieve backlinks. " + e.getMessage();
        }

        log.info("[getIncomingLinks] Found {} note(s) linking to '[[{}]]'", paths.size(), noteName);
        if (paths.isEmpty()) {
            return "No notes found linking to '[[" + noteName + "]]'.";
        }
        return "Notes that link to '[[" + noteName + "]]' (" + paths.size() + "):\n\n" + String.join("\n", paths);
    }

    /**
     * Lists all {@code .md} notes modified in the last {@code N} days, ordered by most recent
     * commit date. Determined by scanning the repository's git commit history.
     *
     * @param days number of days to look back; must be between 1 and 365
     * @return formatted list of note paths with timestamps, or an {@code "ERROR: ..."} string on failure
     */
    @Tool(description = """
            List all .md notes that were changed in the last N days, sorted by most recent first with timestamps.
            Based on git commit history — reflects actual edits, not just file metadata.
            Use this to find what the user has been actively working on recently.
            Valid range: 1–365 days.""")
    public String getRecentlyModified(
            @ToolParam(description = "Number of days to look back. Use 7 for the past week, 30 for the past month. Range: 1–365.") int days) {

        log.trace("[getRecentlyModified] Invoked with days={}", days);

        if (days < 1 || days > 365) {
            log.warn("[getRecentlyModified] Invalid days value: {}", days);
            return "ERROR: days must be between 1 and 365.";
        }

        Date since = Date.from(Instant.now().minusSeconds((long) days * 86400));
        log.info("[getRecentlyModified] Querying commits for last {} day(s) since {}", days, since);

        try {
            Map<String, Date> fileToDate = new LinkedHashMap<>();
            int commitCount = 0;

            for (GHCommit commit : vaultService.getRepository().queryCommits().since(since).list()) {
                commitCount++;
                Date commitDate = commit.getCommitDate();
                log.trace("[getRecentlyModified] Processing commit #{} at {}", commitCount, DATE_FMT.format(commitDate.toInstant()));
                for (GHCommit.File file : commit.listFiles()) {
                    String path = file.getFileName();
                    if (path.endsWith(".md") && !fileToDate.containsKey(path)) {
                        log.debug("[getRecentlyModified] Modified note: '{}' at {}", path, DATE_FMT.format(commitDate.toInstant()));
                        fileToDate.put(path, commitDate);
                    } else if (!path.endsWith(".md")) {
                        log.trace("[getRecentlyModified] Skipping non-md file: '{}'", path);
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
            log.error("[getRecentlyModified] Failed to fetch commits for last {} day(s): {}", days, e.getMessage(), e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.contains("rate limit")) {
                return "ERROR: GitHub API rate limit exceeded. Please wait before retrying.";
            }
            return "ERROR: Failed to retrieve recently modified notes. " + msg;
        }
    }

    /**
     * Extracts all unique {@code [[wiki-link]]} targets from the given markdown content.
     * Handles aliased links ({@code [[target|alias]]}) by returning only the target part.
     *
     * @param content raw markdown content to scan
     * @return ordered list of unique link target strings, without the {@code [[...]]} brackets
     */
    private List<String> extractWikiLinks(String content) {
        log.trace("[extractWikiLinks] Scanning content ({} chars) for wiki-links", content.length());
        List<String> links = new ArrayList<>();
        Matcher matcher = WIKI_LINK_PATTERN.matcher(content);
        while (matcher.find()) {
            String target = matcher.group(1).trim();
            if (!target.isBlank() && !links.contains(target)) {
                log.trace("[extractWikiLinks] Found link: '[[{}]]'", target);
                links.add(target);
            }
        }
        log.debug("[extractWikiLinks] Extracted {} unique link(s)", links.size());
        return links;
    }
}
