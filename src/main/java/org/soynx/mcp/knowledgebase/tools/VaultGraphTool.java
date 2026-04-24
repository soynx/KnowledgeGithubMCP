package org.soynx.mcp.knowledgebase.tools;

import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHCommit;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.PagedSearchIterable;
import org.soynx.mcp.knowledgebase.service.VaultService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

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
 * In multi-repo setups, outgoing link resolution checks all repositories in the setup
 * (cross-repo links are supported), and backlink + recency queries aggregate results
 * across all repos with {@code repoKey/} prefixed paths.
 * </p>
 */
@Slf4j
public class VaultGraphTool {

    private static final Pattern WIKI_LINK_PATTERN = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|[^\\]]*)?]]");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private final List<VaultService> services;

    public VaultGraphTool(List<VaultService> services) {
        this.services = services;
    }

    // -------------------------------------------------------------------------
    // Tools
    // -------------------------------------------------------------------------

    /**
     * Extracts outgoing {@code [[wiki-links]]} from a note and verifies existence.
     */
    @Tool(description = """
            Extract all [[wiki-links]] from a note and verify whether each linked note exists in the vault.
            Each link is returned as [EXISTS] with its resolved path, or [BROKEN] if the target note was not found.
            Use this to explore what a note connects to and to follow the knowledge graph outward from a starting note.
            In a multi-repo setup the filePath must include the repo key prefix, e.g. 'vault/Projects/MyProject.md'.
            Cross-repo links are resolved — a note in one repo can link to a note in another repo in the same setup.""")
    public String getOutgoingLinks(
            @ToolParam(description = "Exact file path relative to vault root. In multi-repo setups include the repo key prefix: 'vault/Projects/MyProject.md'.") String filePath) {

        if (filePath == null || filePath.isBlank()) return "ERROR: filePath must not be empty.";

        boolean multiRepo = MultiRepoPathUtil.isMultiRepo(services);
        log.info("[getOutgoingLinks] Extracting links from '{}', multiRepo={}", filePath, multiRepo);

        String[] parts = MultiRepoPathUtil.splitPrefix(filePath, services);
        VaultService sourceSvc = MultiRepoPathUtil.findService(services, parts[0]);
        if (sourceSvc == null) {
            return "ERROR: Unknown repo key '" + parts[0] + "'. Path must start with a known repo key.";
        }

        String content = sourceSvc.getFileContent(parts[1]);
        if (content.startsWith("ERROR:")) return content;

        List<String> linkTargets = extractWikiLinks(content);
        if (linkTargets.isEmpty()) return "No wiki-links found in '" + filePath + "'.";

        int existCount = 0, brokenCount = 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Outgoing links in '").append(filePath).append("' (").append(linkTargets.size()).append("):\n\n");

        for (String target : linkTargets) {
            String mdPath = target.endsWith(".md") ? target : target + ".md";

            // Cross-repo resolution: check all services until found
            GHContent found = null;
            VaultService foundSvc = null;
            for (VaultService svc : services) {
                GHContent meta = svc.getFileMetadata(mdPath);
                if (meta != null) {
                    found = meta;
                    foundSvc = svc;
                    break;
                }
            }

            if (found != null) {
                String resolvedPath = MultiRepoPathUtil.prefixPath(found.getPath(), foundSvc.getRepoKey(), multiRepo);
                sb.append("  [EXISTS]   [[").append(target).append("]] → ").append(resolvedPath).append("\n");
                existCount++;
            } else {
                sb.append("  [BROKEN]   [[").append(target).append("]] (note not found)\n");
                brokenCount++;
            }
        }

        log.info("[getOutgoingLinks] '{}' — {} valid, {} broken", filePath, existCount, brokenCount);
        return sb.toString().trim();
    }

    /**
     * Finds all notes containing a backlink to the given note name.
     */
    @Tool(description = """
            Find all notes that contain a [[wiki-link]] pointing to the given note — i.e. backlinks / incoming links.
            Use this to discover everything in the vault that references a particular topic or note.
            Pass the note name WITHOUT the .md extension and WITHOUT any folder path — e.g. 'MyProject', not 'Projects/MyProject.md'.
            In a multi-repo setup, ALL repos in the setup are searched and results show the repo key prefix.""")
    public String getIncomingLinks(
            @ToolParam(description = "Note name only, without folder path and without .md extension. Example: 'MyProject' or 'Kubernetes'.") String noteName) {

        if (noteName == null || noteName.isBlank()) return "ERROR: noteName must not be empty.";

        boolean multiRepo = MultiRepoPathUtil.isMultiRepo(services);
        String searchQuery = "[[" + noteName + "]]";
        log.info("[getIncomingLinks] Searching for backlinks to '[[{}]]', multiRepo={}", noteName, multiRepo);

        List<String> paths = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (VaultService svc : services) {
            String keyPrefix = multiRepo ? svc.getRepoKey() + "/" : "";
            PagedSearchIterable<GHContent> results = svc.searchContent(searchQuery);
            if (results == null) {
                warnings.add("rate limit in repo '" + svc.getRepoKey() + "'");
                continue;
            }
            try {
                for (GHContent item : results) {
                    if (paths.size() >= 50) break;
                    if ("file".equals(item.getType()) && item.getName().endsWith(".md")) {
                        paths.add("- " + keyPrefix + item.getPath());
                    }
                }
            } catch (Exception e) {
                warnings.add("error in repo '" + svc.getRepoKey() + "': " + e.getMessage());
            }
        }

        if (paths.isEmpty()) {
            return "No notes found linking to '[[" + noteName + "]]'.";
        }

        StringBuilder sb = new StringBuilder();
        if (!warnings.isEmpty()) {
            sb.append("WARNING: Results may be incomplete — ").append(String.join("; ", warnings)).append("\n\n");
        }
        sb.append("Notes that link to '[[").append(noteName).append("]]' (").append(paths.size()).append("):\n\n");
        sb.append(String.join("\n", paths));
        log.info("[getIncomingLinks] Found {} backlink(s) for '[[{}]]'", paths.size(), noteName);
        return sb.toString();
    }

    /**
     * Lists notes modified in the last N days, sorted by most recent.
     */
    @Tool(description = """
            List all .md notes that were changed in the last N days, sorted by most recent first with timestamps.
            Based on git commit history — reflects actual edits, not just file metadata.
            Use this to find what the user has been actively working on recently.
            In a multi-repo setup, results from all repos are merged and sorted together.
            Valid range: 1–365 days.""")
    public String getRecentlyModified(
            @ToolParam(description = "Number of days to look back. Use 7 for the past week, 30 for the past month. Range: 1–365.") int days) {

        if (days < 1 || days > 365) return "ERROR: days must be between 1 and 365.";

        boolean multiRepo = MultiRepoPathUtil.isMultiRepo(services);
        Date since = Date.from(Instant.now().minusSeconds((long) days * 86400));
        log.info("[getRecentlyModified] Querying last {} day(s), multiRepo={}", days, multiRepo);

        Map<String, Date> fileToDate = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        for (VaultService svc : services) {
            String keyPrefix = multiRepo ? svc.getRepoKey() + "/" : "";
            try {
                int commitCount = 0;
                for (GHCommit commit : svc.getRepository().queryCommits().since(since).list()) {
                    commitCount++;
                    Date commitDate = commit.getCommitDate();
                    for (GHCommit.File file : commit.listFiles()) {
                        String path = file.getFileName();
                        if (path.endsWith(".md")) {
                            String compositeKey = keyPrefix + path;
                            if (!fileToDate.containsKey(compositeKey)) {
                                fileToDate.put(compositeKey, commitDate);
                            }
                        }
                    }
                }
                log.debug("[getRecentlyModified] Scanned {} commit(s) for repo '{}'", commitCount, svc.getRepoKey());
            } catch (IOException e) {
                log.error("[getRecentlyModified] Failed for repo '{}': {}", svc.getRepoKey(), e.getMessage(), e);
                errors.add("repo '" + svc.getRepoKey() + "': " + e.getMessage());
            }
        }

        if (fileToDate.isEmpty()) {
            String suffix = errors.isEmpty() ? "" : " Errors: " + String.join("; ", errors);
            return "No notes modified in the last " + days + " day(s)." + suffix;
        }

        List<Map.Entry<String, Date>> sorted = new ArrayList<>(fileToDate.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        StringBuilder sb = new StringBuilder();
        if (!errors.isEmpty()) {
            sb.append("WARNING: Some repos could not be queried — ").append(String.join("; ", errors)).append("\n\n");
        }
        sb.append("Notes modified in the last ").append(days).append(" day(s) (").append(sorted.size()).append("):\n\n");
        for (Map.Entry<String, Date> entry : sorted) {
            sb.append("  ").append(DATE_FMT.format(entry.getValue().toInstant()))
              .append("  ").append(entry.getKey()).append("\n");
        }
        log.info("[getRecentlyModified] Returning {} modified note(s)", sorted.size());
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
