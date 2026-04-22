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
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VaultSearchTool {

    private static final int MAX_RESULTS_HARD_LIMIT = 30;

    private final VaultService vaultService;

    @Tool(description = "Search all notes in the KnowledgeGithubMCP vault for a keyword or phrase. Uses GitHub Code Search.")
    public String searchNotes(
            @ToolParam(description = "Search term or phrase to look for across all notes") String query,
            @ToolParam(description = "Maximum number of results to return (1-30, default 10)") int maxResults) {
        log.info("[searchNotes] query='{}', requestedMax={}", query, maxResults);

        if (query == null || query.isBlank()) {
            log.warn("[searchNotes] Called with empty query");
            return "ERROR: query must not be empty.";
        }
        int limit = Math.min(Math.max(maxResults, 1), MAX_RESULTS_HARD_LIMIT);
        log.debug("[searchNotes] Effective result limit: {}", limit);

        PagedSearchIterable<GHContent> results = vaultService.searchContent(query);
        if (results == null) {
            log.error("[searchNotes] searchContent returned null — likely rate limited");
            return "ERROR: GitHub API rate limit exceeded. Please wait before retrying.";
        }

        List<String> hits = new ArrayList<>();
        try {
            for (GHContent item : results) {
                if (hits.size() >= limit) break;
                if (!"file".equals(item.getType())) continue;
                log.debug("[searchNotes] Match: '{}'", item.getPath());
                String excerpt = fetchExcerpt(item, query);
                hits.add("- " + item.getPath() + "\n  Excerpt: " + excerpt);
            }
        } catch (Exception e) {
            log.error("[searchNotes] Error iterating search results for query '{}': {}", query, e.getMessage(), e);
            return "ERROR: Failed to retrieve search results. " + e.getMessage();
        }

        log.info("[searchNotes] Returning {} result(s) for query '{}'", hits.size(), query);
        if (hits.isEmpty()) {
            return "No notes found matching '" + query + "'.";
        }
        return "Found " + hits.size() + " note(s) matching '" + query + "':\n\n" + String.join("\n\n", hits);
    }

    @Tool(description = "Find all notes in the KnowledgeGithubMCP vault that contain a specific tag, e.g. #project or #todo.")
    public String searchByTag(
            @ToolParam(description = "Tag to search for, with or without leading '#', e.g. 'todo' or '#todo'") String tag) {
        log.info("[searchByTag] tag='{}'", tag);

        if (tag == null || tag.isBlank()) {
            log.warn("[searchByTag] Called with empty tag");
            return "ERROR: tag must not be empty.";
        }

        String normalized = tag.startsWith("#") ? tag.substring(1) : tag;
        String searchQuery = "#" + normalized;
        log.debug("[searchByTag] Normalized search query: '{}'", searchQuery);

        PagedSearchIterable<GHContent> results = vaultService.searchContent(searchQuery);
        if (results == null) {
            log.error("[searchByTag] searchContent returned null — likely rate limited");
            return "ERROR: GitHub API rate limit exceeded. Please wait before retrying.";
        }

        List<String> paths = new ArrayList<>();
        try {
            for (GHContent item : results) {
                if (paths.size() >= MAX_RESULTS_HARD_LIMIT) break;
                if ("file".equals(item.getType()) && item.getName().endsWith(".md")) {
                    log.debug("[searchByTag] Tagged note found: '{}'", item.getPath());
                    paths.add("- " + item.getPath());
                }
            }
        } catch (Exception e) {
            log.error("[searchByTag] Error iterating results for tag '{}': {}", tag, e.getMessage(), e);
            return "ERROR: Failed to retrieve tag search results. " + e.getMessage();
        }

        log.info("[searchByTag] Found {} note(s) with tag '{}'", paths.size(), searchQuery);
        if (paths.isEmpty()) {
            return "No notes found with tag '" + searchQuery + "'.";
        }
        return "Notes tagged with '" + searchQuery + "' (" + paths.size() + "):\n\n" + String.join("\n", paths);
    }

    private String fetchExcerpt(GHContent item, String query) {
        try {
            String content;
            try (var is = item.read()) {
                content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (content == null || content.isBlank()) return "(empty)";

            int idx = content.toLowerCase().indexOf(query.toLowerCase());
            if (idx < 0) {
                log.debug("[fetchExcerpt] Query not found in body of '{}', using head of content", item.getPath());
                return content.length() > 200 ? content.substring(0, 200) + "…" : content;
            }
            int start = Math.max(0, idx - 60);
            int end = Math.min(content.length(), idx + 140);
            String excerpt = content.substring(start, end).replace('\n', ' ').trim();
            if (start > 0) excerpt = "…" + excerpt;
            if (end < content.length()) excerpt = excerpt + "…";
            return excerpt;
        } catch (IOException e) {
            log.warn("[fetchExcerpt] Could not read content of '{}': {}", item.getPath(), e.getMessage());
            return "(content unavailable)";
        }
    }
}
