package org.soynx.mcp.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.PagedSearchIterable;
import org.soynx.mcp.service.VaultService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class VaultSearchTool {

    private static final int MAX_RESULTS_HARD_LIMIT = 30;
    private static final int MAX_QUERIES = 10;

    private final VaultService vaultService;

    @Tool(description = """
            Search all notes by content AND file path/folder name using multiple search terms (OR logic).
            A note is returned if ANY of the provided terms matches anywhere in its content or its path.
            IMPORTANT: always supply as many related terms as possible — synonyms, abbreviations, sub-topics, \
            alternate spellings, related tools/concepts.
            Example — user asks about 'java logback': use ['logback', 'slf4j', 'log4j', 'logging', 'appender', \
            'logger', 'java', 'log'].
            The bigger the list the fewer relevant notes are missed. Maximum 10 terms.
            Results show whether each match was found in the content or the file path.""")
    public String searchNotes(
            @ToolParam(description = "List of search terms (OR logic). Use many synonyms and related concepts for best recall. Max 10 terms.") List<String> queries,
            @ToolParam(description = "Maximum number of results to return. Range 1–30, default 10.") int maxResults) {

        if (queries == null || queries.isEmpty()) {
            log.warn("[searchNotes] Called with empty queries list");
            return "ERROR: queries list must not be empty.";
        }
        List<String> sanitized = queries.stream().filter(q -> q != null && !q.isBlank()).toList();
        if (sanitized.isEmpty()) {
            log.warn("[searchNotes] All queries were blank");
            return "ERROR: queries list must not be empty.";
        }
        if (sanitized.size() > MAX_QUERIES) {
            log.warn("[searchNotes] {} queries supplied, truncating to {}", sanitized.size(), MAX_QUERIES);
            sanitized = sanitized.subList(0, MAX_QUERIES);
        }

        int limit = Math.min(Math.max(maxResults, 1), MAX_RESULTS_HARD_LIMIT);
        log.info("[searchNotes] queries={}, limit={}", sanitized, limit);

        // path -> match label, insertion-order preserved, dedup key
        LinkedHashMap<String, String> hits = new LinkedHashMap<>();
        // path -> GHContent for content matches (used for excerpt generation)
        Map<String, GHContent> contentByPath = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        // Phase A: GitHub content search
        for (String query : sanitized) {
            if (hits.size() >= limit) break;
            PagedSearchIterable<GHContent> results = vaultService.searchContent(query);
            if (results == null) {
                log.error("[searchNotes] searchContent returned null for query '{}' — likely rate limited", query);
                warnings.add("rate limit hit for query '" + query + "'");
                continue;
            }
            try {
                for (GHContent item : results) {
                    if (hits.size() >= limit) break;
                    if (!"file".equals(item.getType())) continue;
                    String path = item.getPath();
                    if (hits.containsKey(path)) continue;
                    log.debug("[searchNotes] Content match: '{}' via query '{}'", path, query);
                    hits.put(path, "[content: '" + query + "']");
                    contentByPath.put(path, item);
                }
            } catch (Exception e) {
                log.error("[searchNotes] Error iterating results for query '{}': {}", query, e.getMessage(), e);
                warnings.add("error retrieving results for query '" + query + "': " + e.getMessage());
            }
        }

        // Phase B: path matching
        if (hits.size() < limit) {
            List<String> allPaths = vaultService.getAllFilePaths();
            if (allPaths.isEmpty()) {
                log.debug("[searchNotes] getAllFilePaths returned empty — skipping path matching");
            } else {
                outer:
                for (String path : allPaths) {
                    if (hits.size() >= limit) break;
                    if (hits.containsKey(path)) continue;
                    for (String query : sanitized) {
                        if (path.toLowerCase().contains(query.toLowerCase())) {
                            log.debug("[searchNotes] Path match: '{}' via query '{}'", path, query);
                            hits.put(path, "[path: '" + query + "']");
                            continue outer;
                        }
                    }
                }
            }
        }

        if (hits.isEmpty()) {
            log.info("[searchNotes] No results for queries {}", sanitized);
            return "No notes found matching any of " + formatList(sanitized) + ".";
        }

        // Build output with excerpts
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : hits.entrySet()) {
            String path = entry.getKey();
            String label = entry.getValue();
            GHContent content = contentByPath.get(path);
            String excerpt;
            if (content != null) {
                excerpt = fetchExcerpt(content, sanitized);
            } else {
                GHContent meta = vaultService.getFileMetadata(path);
                excerpt = meta != null ? fetchExcerpt(meta, sanitized) : "(content unavailable)";
            }
            lines.add("- " + path + "  " + label + "\n  Excerpt: " + excerpt);
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

    @Tool(description = """
            Find all notes that contain any of the specified Obsidian tags (OR logic).
            Tags are inline markers in note content written as #tagname (e.g. #todo, #project, #java).
            A note is returned if it contains ANY of the provided tags.
            Tags may be passed with or without the leading '#' — both are normalized automatically.
            Tip: provide variant spellings or related tags — e.g. ['project', 'projects', 'proj', 'work'].
            The more tags you supply, the better the recall. Maximum 10 tags.""")
    public String searchByTag(
            @ToolParam(description = "List of Obsidian tags to search for (OR logic). With or without '#', e.g. ['todo', '#project']. Provide variants for best recall. Max 10 tags.") List<String> tags) {

        if (tags == null || tags.isEmpty()) {
            log.warn("[searchByTag] Called with empty tags list");
            return "ERROR: tags list must not be empty.";
        }
        List<String> sanitized = tags.stream().filter(t -> t != null && !t.isBlank()).toList();
        if (sanitized.isEmpty()) {
            log.warn("[searchByTag] All tags were blank");
            return "ERROR: tags list must not be empty.";
        }
        if (sanitized.size() > MAX_QUERIES) {
            log.warn("[searchByTag] {} tags supplied, truncating to {}", sanitized.size(), MAX_QUERIES);
            sanitized = sanitized.subList(0, MAX_QUERIES);
        }
        log.info("[searchByTag] tags={}", sanitized);

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> normalizedTags = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String tag : sanitized) {
            String normalized = "#" + (tag.startsWith("#") ? tag.substring(1) : tag);
            normalizedTags.add(normalized);

            if (seen.size() >= MAX_RESULTS_HARD_LIMIT) break;

            PagedSearchIterable<GHContent> results = vaultService.searchContent(normalized);
            if (results == null) {
                log.error("[searchByTag] searchContent returned null for tag '{}' — likely rate limited", normalized);
                warnings.add("rate limit hit for tag '" + normalized + "'");
                continue;
            }
            try {
                for (GHContent item : results) {
                    if (seen.size() >= MAX_RESULTS_HARD_LIMIT) break;
                    if ("file".equals(item.getType()) && item.getName().endsWith(".md") && !seen.contains(item.getPath())) {
                        log.debug("[searchByTag] Tagged note found: '{}'", item.getPath());
                        seen.add(item.getPath());
                    }
                }
            } catch (Exception e) {
                log.error("[searchByTag] Error iterating results for tag '{}': {}", normalized, e.getMessage(), e);
                warnings.add("error retrieving results for tag '" + normalized + "': " + e.getMessage());
            }
        }

        log.info("[searchByTag] Found {} note(s) for tags {}", seen.size(), normalizedTags);

        if (seen.isEmpty()) {
            return "No notes found with any of the tags " + formatList(normalizedTags) + ".";
        }

        StringBuilder sb = new StringBuilder();
        if (!warnings.isEmpty()) {
            sb.append("WARNING: Results may be incomplete — ").append(String.join("; ", warnings)).append("\n\n");
        }
        sb.append("Notes tagged with any of ").append(formatList(normalizedTags)).append(" (").append(seen.size()).append("):\n\n");
        seen.forEach(p -> sb.append("- ").append(p).append("\n"));
        return sb.toString();
    }

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
                    int end = Math.min(content.length(), idx + 140);
                    String excerpt = content.substring(start, end).replace('\n', ' ').trim();
                    if (start > 0) excerpt = "…" + excerpt;
                    if (end < content.length()) excerpt = excerpt + "…";
                    return excerpt;
                }
            }
            return content.length() > 200 ? content.substring(0, 200) + "…" : content;
        } catch (IOException e) {
            log.warn("[fetchExcerpt] Could not read content of '{}': {}", item.getPath(), e.getMessage());
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
