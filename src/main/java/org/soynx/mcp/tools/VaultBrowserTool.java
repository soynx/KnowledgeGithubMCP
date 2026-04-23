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

/**
 * MCP tool class for browsing the vault's file system structure and reading note content.
 * <p>
 * Provides four tools: listing a directory, fetching the full file tree as JSON,
 * reading a note's raw markdown content, and retrieving file metadata.
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VaultBrowserTool {

    private final VaultService vaultService;

    /**
     * Lists the immediate contents of a vault directory (one level deep).
     * <p>
     * Returns separate sections for folders and files, with file sizes. For a complete overview
     * of the entire vault, {@code getVaultFileTree} is more efficient.
     * </p>
     *
     * @param path vault-relative folder path, or blank for the vault root
     * @return formatted directory listing, or an error string if the path does not exist
     */
    @Tool(description = """
            List the immediate files and folders inside a specific vault directory.
            Use this to explore a known folder's contents in detail (includes file sizes).
            For a full vault overview use getVaultFileTree instead — it is cheaper and shows everything at once.
            Pass an empty string to list the vault root.""")
    public String listVaultContents(
            @ToolParam(description = "Folder path relative to vault root, e.g. 'Projects' or 'Areas/Work'. Pass empty string for the vault root.") String path) {

        String effectivePath = path == null ? "" : path;
        log.trace("[listVaultContents] Invoked with path='{}'", effectivePath);
        log.info("[listVaultContents] Listing path='{}'", effectivePath.isBlank() ? "/" : effectivePath);

        List<GHContent> entries = vaultService.listDirectory(effectivePath);

        if (entries.isEmpty()) {
            log.warn("[listVaultContents] No entries returned for path='{}'", effectivePath);
            return "No entries found at path '" + path + "'. The path may not exist or the directory is empty.";
        }

        List<GHContent> folders = entries.stream().filter(e -> "dir".equals(e.getType())).toList();
        List<GHContent> files   = entries.stream().filter(e -> "file".equals(e.getType())).toList();
        log.debug("[listVaultContents] path='{}' — {} folder(s), {} file(s)",
                effectivePath.isBlank() ? "/" : effectivePath, folders.size(), files.size());

        StringBuilder sb = new StringBuilder();
        sb.append("Contents of '").append(effectivePath.isBlank() ? "/" : effectivePath).append("':\n\n");

        if (!folders.isEmpty()) {
            sb.append("Folders (").append(folders.size()).append("):\n");
            folders.forEach(f -> {
                log.trace("[listVaultContents] Dir entry: '{}'", f.getName());
                sb.append("  [DIR]  ").append(f.getName()).append("/\n");
            });
            sb.append("\n");
        }
        if (!files.isEmpty()) {
            sb.append("Files (").append(files.size()).append("):\n");
            files.forEach(f -> {
                log.trace("[listVaultContents] File entry: '{}' ({} bytes)", f.getName(), f.getSize());
                sb.append("  [FILE] ").append(f.getName())
                        .append("  (").append(formatSize(f.getSize())).append(")\n");
            });
        }

        return sb.toString().trim();
    }

    /**
     * Fetches the complete vault file tree as a single-line JSON object.
     * <p>
     * Format: {@code {"total": N, "files": ["path/a.md", ...]}}. Uses a single recursive git tree
     * API call — much cheaper than traversing directories one by one.
     * </p>
     *
     * @return compact JSON string, or an {@code "ERROR: ..."} string on failure
     */
    @Tool(description = """
            Get every file path in the entire vault as compact JSON: {"total": N, "files": ["path/to/note.md", ...]}.
            Call this first whenever you don't know where to look — the full sorted path list reveals the folder \
            structure and lets you identify relevant files before reading them.
            The paths returned are the exact values to pass into getNoteContent, getOutgoingLinks, getNoteMetadata, etc.""")
    public String getVaultFileTree() {
        log.trace("[getVaultFileTree] Invoked");
        log.info("[getVaultFileTree] Fetching full recursive file tree");
        String result = vaultService.getFullFileTree();
        if (result.startsWith("ERROR:")) {
            log.error("[getVaultFileTree] Failed to retrieve file tree: {}", result);
        } else {
            log.debug("[getVaultFileTree] File tree returned successfully ({} chars)", result.length());
        }
        return result;
    }

    /**
     * Fetches the full raw markdown content of a single note.
     *
     * @param filePath exact vault-relative file path including the {@code .md} extension
     * @return the note's raw markdown text, or an {@code "ERROR: ..."} string if not found
     */
    @Tool(description = """
            Fetch the complete raw markdown content of a note, including frontmatter, tags, body text, and [[wiki-links]].
            Requires an exact file path — obtain it from getVaultFileTree or from searchNotes results.
            Use this when you need to read the full content of a specific note.""")
    public String getNoteContent(
            @ToolParam(description = "Exact file path relative to vault root, e.g. 'Projects/MyProject.md'. Must include the .md extension.") String filePath) {

        log.trace("[getNoteContent] Invoked with filePath='{}'", filePath);
        if (filePath == null || filePath.isBlank()) {
            log.warn("[getNoteContent] Called with null or blank filePath");
            return "ERROR: filePath must not be empty.";
        }
        log.info("[getNoteContent] Fetching content for '{}'", filePath);

        String content = vaultService.getFileContent(filePath);
        if (content.startsWith("ERROR:")) {
            log.warn("[getNoteContent] Error returned for '{}': {}", filePath, content);
        } else {
            log.debug("[getNoteContent] Returned {} chars for '{}'", content.length(), filePath);
        }
        return content;
    }

    /**
     * Retrieves file metadata for a note: name, path, size, SHA, and GitHub URLs.
     *
     * @param filePath exact vault-relative file path including the {@code .md} extension
     * @return formatted metadata string, or an {@code "ERROR: ..."} string if not found
     */
    @Tool(description = """
            Get file metadata for a note: name, path, size, SHA hash, and direct GitHub HTML/download URLs.
            Use this when you need the GitHub link to share or verify a note, or to check its size before reading.
            Does NOT return note content — use getNoteContent for that.""")
    public String getNoteMetadata(
            @ToolParam(description = "Exact file path relative to vault root, e.g. 'Projects/MyProject.md'. Must include the .md extension.") String filePath) {

        log.trace("[getNoteMetadata] Invoked with filePath='{}'", filePath);
        if (filePath == null || filePath.isBlank()) {
            log.warn("[getNoteMetadata] Called with null or blank filePath");
            return "ERROR: filePath must not be empty.";
        }
        log.info("[getNoteMetadata] Fetching metadata for '{}'", filePath);

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
            log.trace("[getNoteMetadata] GitHub URLs resolved for '{}'", filePath);
        } catch (IOException e) {
            log.warn("[getNoteMetadata] Could not resolve GitHub URLs for '{}': {}", filePath, e.getMessage());
            sb.append("  URL:      (unavailable)\n");
        }
        return sb.toString().trim();
    }

    /**
     * Formats a byte count as a human-readable size string (B, KB, or MB).
     *
     * @param bytes file size in bytes
     * @return formatted size string, e.g. {@code "12.3 KB"}
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
