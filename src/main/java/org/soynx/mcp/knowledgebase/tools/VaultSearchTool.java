package org.soynx.mcp.knowledgebase.tools;

import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.PagedSearchIterable;
import org.soynx.mcp.knowledgebase.service.VaultService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * MCP tool class exposing full-text and tag-based search over the vault.
 * <p>
 * In multi-repo setups both tools run against all configured repositories and merge results.
 * Returned file paths carry a {@code repoKey/} prefix in multi-repo mode so they can be
 * passed directly to path-based tools.
 * </p>
 */
@Slf4j
public class VaultSearchTool {

    private static final int MAX_QUERIES = 10;
    private static final int DEFAULT_MAX_RESULTS = 70;

    private final List<VaultService> services;

    public VaultSearchTool(List<VaultService> services) {
        this.services = services;
    }

    // -------------------------------------------------------------------------
    // Tools
    // -------------------------------------------------------------------------

    /**
     * Multi-term OR full-text search across note content and file paths.
     */
    @Tool(description = """
            Search all notes by content AND file path/folder name using multiple search terms (OR logic).
            A note is returned if ANY of the provided terms matches anywhere in its content or its path.
            IMPORTANT: always supply as many related terms as possible — synonyms, abbreviations, sub-topics, \
            alternate spellings, related tools/concepts.
            Example — user asks about 'java logback': use ['logback', 'slf4j', 'log4j', 'logging', 'appender', \
            'logger', 'java', 'log'].
            The bigger the list the fewer relevant notes are missed. Maximum 10 terms.
            Results show whether each match was found in the content or the file path.
            To get a feeling about the folder structure and what notes could be related/live in the same folder, use the 'getVaultFileTree' tool.""")
    public String searchNotes(
            @ToolParam(description = "List of search terms (OR logic). Use many synonyms and related concepts for best recall. Max 10 terms.") List<String> queries,
            @ToolParam(description = "Maximum number of results to return. Default 70. Set higher for broader searches.") int maxResults) {

        log.trace("[searchNotes] Invoked with queries={}, maxResults={}", queries, maxResults);

        if (queries == null || queries.isEmpty()) return "ERROR: queries list must not be empty.";
        List<String> sanitized = queries.stream().filter(q -> q != null && !q.isBlank()).toList();
        if (sanitized.isEmpty()) return "ERROR: queries list must not be empty.";
        if (sanitized.size() > MAX_QUERIES) sanitized = sanitized.subList(0, MAX_QUERIES);

        int limit = maxResults <= 0 ? DEFAULT_MAX_RESULTS : maxResults;
        boolean multiRepo = MultiRepoPathUtil.isMultiRepo(services);
        log.info("[searchNotes] queries={}, limit={}, multiRepo={}", sanitized, limit, multiRepo);

        LinkedHashMap<String, String> hits = new LinkedHashMap<>();
        Map<String, GHContent> contentByKey = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        for (VaultService svc : services) {
            String keyPrefix = multiRepo ? svc.getRepoKey() + "/" : "";

            // Phase A: GitHub Code Search
            for (String query : sanitized) {
                if (hits.size() >= limit) break;
                PagedSearchIterable<GHContent> results = svc.searchContent(query);
                if (results == null) {
                    warnings.add("rate limit hit for query '" + query + "' in repo '" + svc.getRepoKey() + "'");
                    continue;
                }
                try {
                    for (GHContent item : results) {
                        if (hits.size() >= limit) break;
                        if (!"file".equals(item.getType())) continue;
                        String compositeKey = keyPrefix + item.getPath();
                        if (hits.containsKey(compositeKey)) continue;
                        hits.put(compositeKey, "[content: '" + query + "']");
                        contentByKey.put(compositeKey, item);
                    }
                } catch (Exception e) {
                    warnings.add("error for query '" + query + "' in repo '" + svc.getRepoKey() + "': " + e.getMessage());
                }
            }

            // Phase B: path matching
            if (hits.size() < limit) {
                List<String> allPaths = svc.getAllFilePaths();
                outer:
                for (String path : allPaths) {
                    if (hits.size() >= limit) break;
                    String compositeKey = keyPrefix + path;
                    if (hits.containsKey(compositeKey)) continue;
                    for (String query : sanitized) {
                        if (path.toLowerCase().contains(query.toLowerCase())) {
                            hits.put(compositeKey, "[path: '" + query + "']");
                            continue outer;
                        }
                    }
                }
            }
        }

        if (hits.isEmpty()) {
            return "No notes found matching any of " + formatList(sanitized) + ".";
        }

        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : hits.entrySet()) {
            String compositeKey = entry.getKey();
            String label = entry.getValue();
            GHContent ghContent = contentByKey.get(compositeKey);
            String excerpt;
            if (ghContent != null) {
                excerpt = fetchExcerpt(ghContent, sanitized);
            } else {
                // For path-only matches we need to fetch via the service
                // Determine which service owns this key
                String[] parts = MultiRepoPathUtil.splitPrefix(compositeKey, services);
                VaultService svc = MultiRepoPathUtil.findService(services, parts[0]);
                GHContent meta = svc != null ? svc.getFileMetadata(parts[1]) : null;
                excerpt = meta != null ? fetchExcerpt(meta, sanitized) : "(content unavailable)";
            }
            lines.add("- " + compositeKey + "  " + label + "\n  Excerpt: " + excerpt);
        }

        StringBuilder sb = new StringBuilder();
        if (!warnings.isEmpty()) {
            sb.append("WARNING: Results may be incomplete — ").append(String.join("; ", warnings)).append("\n\n");
        }
        sb.append("Found ").append(hits.size()).append(" note(s) matching any of ").append(formatList(sanitized)).append(":\n\n");
        sb.append(String.join("\n\n", lines));
        log.info("[searchNotes] Returning {} result(s)", hits.size());
        return sb.toString();
    }

    /**
     * Tag-based search across all notes.
     */
    @Tool(description = """
            Find all notes that contain any of the specified Obsidian tags (OR logic).
            Tags are inline markers in note content written as #tagname (e.g. #todo, #project, #java).
            A note is returned if it contains ANY of the provided tags.
            Tags may be passed with or without the leading '#' — both are normalized automatically.
            Tip: provide variant spellings or related tags — e.g. ['project', 'projects', 'proj', 'work'].
            The more tags you supply, the better the recall. Maximum 10 tags.""")
    public String searchByTag(
            @ToolParam(description = "List of Obsidian tags to search for (OR logic). With or without '#', e.g. ['todo', '#project']. Provide variants for best recall. Max 10 tags.") List<String> tags) {

        log.trace("[searchByTag] Invoked with tags={}", tags);

        if (tags == null || tags.isEmpty()) return "ERROR: tags list must not be empty.";
        List<String> sanitized = tags.stream().filter(t -> t != null && !t.isBlank()).toList();
        if (sanitized.isEmpty()) return "ERROR: tags list must not be empty.";
        if (sanitized.size() > MAX_QUERIES) sanitized = sanitized.subList(0, MAX_QUERIES);

        boolean multiRepo = MultiRepoPathUtil.isMultiRepo(services);
        log.info("[searchByTag] tags={}, multiRepo={}", sanitized, multiRepo);

        List<String> normalizedTags = sanitized.stream()
                .map(t -> "#" + (t.startsWith("#") ? t.substring(1) : t))
                .toList();

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();

        for (VaultService svc : services) {
            String keyPrefix = multiRepo ? svc.getRepoKey() + "/" : "";
            for (String normalized : normalizedTags) {
                PagedSearchIterable<GHContent> results = svc.searchContent(normalized);
                if (results == null) {
                    warnings.add("rate limit for tag '" + normalized + "' in repo '" + svc.getRepoKey() + "'");
                    continue;
                }
                try {
                    for (GHContent item : results) {
                        if (!"file".equals(item.getType()) || !item.getName().endsWith(".md")) continue;
                        seen.add(keyPrefix + item.getPath());
                    }
                } catch (Exception e) {
                    warnings.add("error for tag '" + normalized + "' in repo '" + svc.getRepoKey() + "': " + e.getMessage());
                }
            }
        }

        if (seen.isEmpty()) {
            return "No notes found with any of the tags " + formatList(normalizedTags) + ".";
        }

        StringBuilder sb = new StringBuilder();
        if (!warnings.isEmpty()) {
            sb.append("WARNING: Results may be incomplete — ").append(String.join("; ", warnings)).append("\n\n");
        }
        sb.append("Notes tagged with any of ").append(formatList(normalizedTags))
          .append(" (").append(seen.size()).append("):\n\n");
        seen.forEach(p -> sb.append("- ").append(p).append("\n"));
        log.info("[searchByTag] Found {} note(s)", seen.size());
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String fetchExcerpt(GHContent item, List<String> queries) {
        try {
            String content;
            try (var is = item.read()) {
                content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (content == null || content.isBlank()) return "(empty)";
            for (String query : queries) {
                int idx = content.toLowerCase().indexOf(query.toLowerCase());
                if (idx >= 0) {
                    int start = Math.max(0, idx - 60);
                    int end   = Math.min(content.length(), idx + 140);
                    String excerpt = content.substring(start, end).replace('\n', ' ').trim();
                    if (start > 0) excerpt = "…" + excerpt;
                    if (end < content.length()) excerpt = excerpt + "…";
                    return excerpt;
                }
            }
            return content.length() > 200 ? content.substring(0, 200) + "…" : content;
        } catch (IOException e) {
            return "(content unavailable)";
        }
    }

    private String formatList(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            sb.append("'").append(items.get(i)).append("'");
            if (i < items.size() - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }
}
