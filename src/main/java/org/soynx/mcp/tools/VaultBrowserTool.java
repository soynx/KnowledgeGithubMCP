package org.soynx.mcp.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHContent;
import org.soynx.mcp.service.VaultService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VaultBrowserTool {

    private final VaultService vaultService;

    @Tool(description = "List all files and folders at a given path in the KnowledgeGithubMCP vault. Use empty string for root.")
    public String listVaultContents(
            @ToolParam(description = "Path to list, e.g. 'Projects' or 'Area/Work'. Use empty string for vault root.") String path) {
        String effectivePath = path == null ? "" : path;
        log.info("[listVaultContents] path='{}'", effectivePath.isBlank() ? "/" : effectivePath);

        List<GHContent> entries = vaultService.listDirectory(effectivePath);

        if (entries.isEmpty()) {
            log.warn("[listVaultContents] No entries returned for path '{}'", effectivePath);
            return "No entries found at path '" + path + "'. The path may not exist or the directory is empty.";
        }

        List<GHContent> folders = entries.stream().filter(e -> "dir".equals(e.getType())).toList();
        List<GHContent> files   = entries.stream().filter(e -> "file".equals(e.getType())).toList();
        log.debug("[listVaultContents] Result: {} folder(s), {} file(s)", folders.size(), files.size());

        StringBuilder sb = new StringBuilder();
        sb.append("Contents of '").append(effectivePath.isBlank() ? "/" : effectivePath).append("':\n\n");

        if (!folders.isEmpty()) {
            sb.append("Folders (").append(folders.size()).append("):\n");
            folders.forEach(f -> sb.append("  [DIR]  ").append(f.getName()).append("/\n"));
            sb.append("\n");
        }
        if (!files.isEmpty()) {
            sb.append("Files (").append(files.size()).append("):\n");
            files.forEach(f -> sb.append("  [FILE] ").append(f.getName())
                    .append("  (").append(formatSize(f.getSize())).append(")\n"));
        }

        return sb.toString().trim();
    }

    @Tool(description = "Get the complete file tree of the entire vault in one call, formatted like 'tree' output. Use this first to understand the vault structure before navigating into specific folders.")
    public String getVaultFileTree() {
        log.info("[getVaultFileTree] Fetching full recursive file tree");
        String result = vaultService.getFullFileTree();
        if (result.startsWith("ERROR:")) {
            log.warn("[getVaultFileTree] Failed: {}", result);
        } else {
            log.debug("[getVaultFileTree] Tree returned successfully");
        }
        return result;
    }

    @Tool(description = "Get the full markdown content of a specific note by its file path.")
    public String getNoteContent(
            @ToolParam(description = "File path relative to vault root, e.g. 'Projects/MyProject.md'") String filePath) {
        log.info("[getNoteContent] filePath='{}'", filePath);
        if (filePath == null || filePath.isBlank()) {
            log.warn("[getNoteContent] Called with empty filePath");
            return "ERROR: filePath must not be empty.";
        }

        String content = vaultService.getFileContent(filePath);
        if (content.startsWith("ERROR:")) {
            log.warn("[getNoteContent] Returned error for '{}': {}", filePath, content);
        } else {
            log.debug("[getNoteContent] Returned {} chars for '{}'", content.length(), filePath);
        }
        return content;
    }

    @Tool(description = "Get metadata for a note: last modified date, file size, and direct GitHub URL.")
    public String getNoteMetadata(
            @ToolParam(description = "File path relative to vault root, e.g. 'Projects/MyProject.md'") String filePath) {
        log.info("[getNoteMetadata] filePath='{}'", filePath);
        if (filePath == null || filePath.isBlank()) {
            log.warn("[getNoteMetadata] Called with empty filePath");
            return "ERROR: filePath must not be empty.";
        }

        GHContent meta = vaultService.getFileMetadata(filePath);
        if (meta == null) {
            log.warn("[getNoteMetadata] File not found: '{}'", filePath);
            return "ERROR: File not found at path '" + filePath + "'. Please check the path and try again.";
        }

        log.debug("[getNoteMetadata] Metadata found: name='{}', size={} bytes, sha={}",
                meta.getName(), meta.getSize(), meta.getSha());

        StringBuilder sb = new StringBuilder();
        sb.append("Metadata for: ").append(filePath).append("\n");
        sb.append("  Name:     ").append(meta.getName()).append("\n");
        sb.append("  Path:     ").append(meta.getPath()).append("\n");
        sb.append("  Size:     ").append(formatSize(meta.getSize())).append("\n");
        sb.append("  SHA:      ").append(meta.getSha()).append("\n");
        try {
            sb.append("  URL:      ").append(meta.getHtmlUrl()).append("\n");
            sb.append("  Download: ").append(meta.getDownloadUrl()).append("\n");
        } catch (IOException e) {
            log.warn("[getNoteMetadata] Could not retrieve GitHub URLs for '{}': {}", filePath, e.getMessage());
            sb.append("  URL:      (unavailable)\n");
        }
        return sb.toString().trim();
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
