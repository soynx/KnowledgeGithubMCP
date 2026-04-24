package org.soynx.mcp.knowledgebase.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHContent;
import org.kohsuke.github.PagedSearchIterable;
import org.soynx.mcp.knowledgebase.service.VaultService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * MCP tool class exposing full-text and tag-based search over the Obsidian vault.
 * <p>
 * Both tools accept a list of search terms and apply OR logic — a note is returned if any
 * single term matches. {@code searchNotes} additionally checks file paths, so notes can be found
 * by folder/filename even if the content doesn't match. Results are deduplicated across all terms.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VaultSearchTool {

    private static final int MAX_QUERIES = 10;
    private static final int DEFAULT_MAX_RESULTS = 70;

    private final VaultService vaultService;

    /**
     * Searches vault notes by content and file path across multiple search terms.
     * <p>
     * Runs two phases:
     * <ol>
     *   <li><b>GitHub Code Search</b> — one API call per query term, scoped to this repository.</li>
     *   <li><b>Path matching</b> — fetches the full file tree and checks if any term is a
     *       case-insensitive substring of any file path not already found in phase 1.</li>
     * </ol>
     * Results are deduplicated by path. Each result includes a label indicating whether the match
     * was found in the content or the path, plus a contextual excerpt.
     * </p>
     *
     * @param queries    list of search terms (OR logic); max {@value MAX_QUERIES} terms
     * @param maxResults maximum number of results to return; defaults to {@value DEFAULT_MAX_RESULTS} when {@code <= 0}
     * @return formatted result string with matched paths and excerpts, or an {@code "ERROR: ..."} string
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
            To get a feeling about the folder structure and what notes could be releated/life in the same folder, use the 'getVaultFileTree' tool.""")
    public String searchNotes(
            @ToolParam(description = "List of search terms (OR logic). Use many synonyms and related concepts for best recall. Max 10 terms.") List<String> queries,
            @ToolParam(description = "Maximum number of results to return. Default 70. Set higher for broader searches.") int maxResults) {

        log.trace("[searchNotes] Invoked with queries={}, maxResults={}", queries, maxResults);

        if (queries == null || queries.isEmpty()) {
            log.warn("[searchNotes] Called with null or empty queries list");
            return "ERROR: queries list must not be empty.";
        }
        List<String> sanitized = queries.stream().filter(q -> q != null && !q.isBlank()).toList();
        if (sanitized.isEmpty()) {
            log.warn("[searchNotes] All provided queries were null or blank");
            return "ERROR: queries list must not be empty.";
        }
        if (sanitized.size() > MAX_QUERIES) {
            log.warn("[searchNotes] {} queries supplied, truncating to {}", sanitized.size(), MAX_QUERIES);
            sanitized = sanitized.subList(0, MAX_QUERIES);
        }

        int limit = maxResults <= 0 ? DEFAULT_MAX_RESULTS : maxResults;
        log.info("[searchNotes] Starting search — queries={}, limit={}", sanitized, limit);

        // path -> match label, insertion-order preserved for dedup
        LinkedHashMap<String, String> hits = new LinkedHashMap<>();
        // path -> GHContent for content matches, used for excerpt generation
        Map<String, GHContent> contentByPath = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        // Phase A: GitHub Code Search (one call per query term)
        log.debug("[searchNotes] Phase A: GitHub content search for {} term(s)", sanitized.size());
        for (String query : sanitized) {
            if (hits.size() >= limit) {
                log.debug("[searchNotes] Limit {} reached, stopping Phase A early", limit);
                break;
            }
            log.trace("[searchNotes] Phase A: searching for query='{}'", query);
            PagedSearchIterable<GHContent> results = vaultService.searchContent(query);
            if (results == null) {
                log.error("[searchNotes] searchContent returned null for query='{}' — likely rate limited", query);
                warnings.add("rate limit hit for query '" + query + "'");
                continue;
            }
            try {
                for (GHContent item : results) {
                    if (hits.size() >= limit) break;
                    if (!"file".equals(item.getType())) {
                        log.trace("[searchNotes] Skipping non-file entry: '{}'", item.getPath());
                        continue;
                    }
                    String path = item.getPath();
                    if (hits.containsKey(path)) {
                        log.trace("[searchNotes] Skipping duplicate path: '{}'", path);
                        continue;
                    }
                    log.debug("[searchNotes] Content match: path='{}' via query='{}'", path, query);
                    hits.put(path, "[content: '" + query + "']");
                    contentByPath.put(path, item);
                }
            } catch (Exception e) {
                log.error("[searchNotes] Error iterating results for query='{}': {}", query, e.getMessage(), e);
                warnings.add("error retrieving results for query '" + query + "': " + e.getMessage());
            }
        }
        log.debug("[searchNotes] Phase A complete — {} hit(s) so far", hits.size());

        // Phase B: path matching via full file tree
        if (hits.size() < limit) {
            log.debug("[searchNotes] Phase B: path matching (current hits={}, limit={})", hits.size(), limit);
            List<String> allPaths = vaultService.getAllFilePaths();
            if (allPaths.isEmpty()) {
                log.warn("[searchNotes] Phase B: getAllFilePaths returned empty, skipping path matching");
            } else {
                log.trace("[searchNotes] Phase B: checking {} file paths against {} queries", allPaths.size(), sanitized.size());
                outer:
                for (String path : allPaths) {
                    if (hits.size() >= limit) break;
                    if (hits.containsKey(path)) continue;
                    for (String query : sanitized) {
                        if (path.toLowerCase().contains(query.toLowerCase())) {
                            log.debug("[searchNotes] Path match: path='{}' via query='{}'", path, query);
                            hits.put(path, "[path: '" + query + "']");
                            continue outer;
                        }
                    }
                }
            }
        }
        log.debug("[searchNotes] Phase B complete — {} total hit(s)", hits.size());

        if (hits.isEmpty()) {
            log.info("[searchNotes] No results found for queries={}", sanitized);
            return "No notes found matching any of " + formatList(sanitized) + ".";
        }

        // Build output with excerpts
        log.debug("[searchNotes] Building output for {} result(s)", hits.size());
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : hits.entrySet()) {
            String path = entry.getKey();
            String label = entry.getValue();
            log.trace("[searchNotes] Generating excerpt for path='{}'", path);
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
            log.warn("[searchNotes] {} warning(s) collected during search: {}", warnings.size(), warnings);
            sb.append("WARNING: Results may be incomplete — ").append(String.join("; ", warnings)).append("\n\n");
        }
        sb.append("Found ").append(hits.size()).append(" note(s) matching any of ").append(formatList(sanitized)).append(":\n\n");
        sb.append(String.join("\n\n", lines));
        log.info("[searchNotes] Returning {} result(s) for queries={}", hits.size(), sanitized);
        return sb.toString();
    }

    /**
     * Finds all vault notes that contain any of the specified Obsidian tags.
     * <p>
     * Each tag is normalized to {@code #tagname} format before searching. One GitHub Code Search
     * call is made per tag. Results are deduplicated across all tags. Only {@code .md} files are
     * included in the output.
     * </p>
     *
     * @param tags list of Obsidian tags to search for (OR logic); may include or omit the leading {@code #};
     *             max {@value MAX_QUERIES} tags
     * @return formatted list of matching note paths, or an {@code "ERROR: ..."} string
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

        if (tags == null || tags.isEmpty()) {
            log.warn("[searchByTag] Called with null or empty tags list");
            return "ERROR: tags list must not be empty.";
        }
        List<String> sanitized = tags.stream().filter(t -> t != null && !t.isBlank()).toList();
        if (sanitized.isEmpty()) {
            log.warn("[searchByTag] All provided tags were null or blank");
            return "ERROR: tags list must not be empty.";
        }
        if (sanitized.size() > MAX_QUERIES) {
            log.warn("[searchByTag] {} tags supplied, truncating to {}", sanitized.size(), MAX_QUERIES);
            sanitized = sanitized.subList(0, MAX_QUERIES);
        }
        log.info("[searchByTag] Starting tag search — tags={}", sanitized);

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<String> normalizedTags = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (String tag : sanitized) {
            String normalized = "#" + (tag.startsWith("#") ? tag.substring(1) : tag);
            normalizedTags.add(normalized);
            log.trace("[searchByTag] Normalized '{}' -> '{}'", tag, normalized);
            log.debug("[searchByTag] Searching for tag='{}'", normalized);

            PagedSearchIterable<GHContent> results = vaultService.searchContent(normalized);
            if (results == null) {
                log.error("[searchByTag] searchContent returned null for tag='{}' — likely rate limited", normalized);
                warnings.add("rate limit hit for tag '" + normalized + "'");
                continue;
            }
            try {
                for (GHContent item : results) {
                    if (!"file".equals(item.getType()) || !item.getName().endsWith(".md")) {
                        log.trace("[searchByTag] Skipping non-md entry: '{}'", item.getPath());
                        continue;
                    }
                    if (seen.contains(item.getPath())) {
                        log.trace("[searchByTag] Skipping duplicate: '{}'", item.getPath());
                        continue;
                    }
                    log.debug("[searchByTag] Tagged note found: '{}' via tag='{}'", item.getPath(), normalized);
                    seen.add(item.getPath());
                }
            } catch (Exception e) {
                log.error("[searchByTag] Error iterating results for tag='{}': {}", normalized, e.getMessage(), e);
                warnings.add("error retrieving results for tag '" + normalized + "': " + e.getMessage());
            }
        }

        log.info("[searchByTag] Found {} note(s) for tags={}", seen.size(), normalizedTags);

        if (seen.isEmpty()) {
            return "No notes found with any of the tags " + formatList(normalizedTags) + ".";
        }

        StringBuilder sb = new StringBuilder();
        if (!warnings.isEmpty()) {
            log.warn("[searchByTag] {} warning(s) collected during search: {}", warnings.size(), warnings);
            sb.append("WARNING: Results may be incomplete — ").append(String.join("; ", warnings)).append("\n\n");
        }
        sb.append("Notes tagged with any of ").append(formatList(normalizedTags)).append(" (").append(seen.size()).append("):\n\n");
        seen.forEach(p -> sb.append("- ").append(p).append("\n"));
        return sb.toString();
    }

    /**
     * Reads the content of a {@link GHContent} file and returns a contextual excerpt around the
     * first query term found in the content (case-insensitive). Falls back to the first 200 chars
     * if no query term is found in the body.
     *
     * @param item    the GitHub content object to read
     * @param queries list of query terms; the first matching term determines the excerpt window
     * @return a text excerpt (up to ~200 chars) with ellipsis markers, or a fallback string on error
     */
    private String fetchExcerpt(GHContent item, List<String> queries) {
        log.trace("[fetchExcerpt] Reading content of '{}'", item.getPath());
        try {
            String content;
            try (var is = item.read()) {
                content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (content == null || content.isBlank()) {
                log.debug("[fetchExcerpt] '{}' is empty", item.getPath());
                return "(empty)";
            }
            for (String query : queries) {
                int idx = content.toLowerCase().indexOf(query.toLowerCase());
                if (idx >= 0) {
                    int start = Math.max(0, idx - 60);
                    int end = Math.min(content.length(), idx + 140);
                    String excerpt = content.substring(start, end).replace('\n', ' ').trim();
                    if (start > 0) excerpt = "…" + excerpt;
                    if (end < content.length()) excerpt = excerpt + "…";
                    log.trace("[fetchExcerpt] Excerpt found for query='{}' at idx={} in '{}'", query, idx, item.getPath());
                    return excerpt;
                }
            }
            log.debug("[fetchExcerpt] No query matched content of '{}', using head fallback", item.getPath());
            return content.length() > 200 ? content.substring(0, 200) + "…" : content;
        } catch (IOException e) {
            log.warn("[fetchExcerpt] Could not read content of '{}': {}", item.getPath(), e.getMessage());
            return "(content unavailable)";
        }
    }

    /**
     * Formats a list of strings as a bracketed, single-quoted comma-separated display string,
     * e.g. {@code ['a', 'b', 'c']}.
     *
     * @param items the list to format
     * @return the formatted string
     */
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
