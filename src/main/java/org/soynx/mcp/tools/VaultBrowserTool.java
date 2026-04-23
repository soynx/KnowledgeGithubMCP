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

    @Tool(description = """
            List the immediate files and folders inside a specific vault directory.
            Use this to explore a known folder's contents in detail (includes file sizes).
            For a full vault overview use getVaultFileTree instead — it is cheaper and shows everything at once.
            Pass an empty string to list the vault root.""")
    public String listVaultContents(
            @ToolParam(description = "Folder path relative to vault root, e.g. 'Projects' or 'Areas/Work'. Pass empty string for the vault root.") String path) {
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

    @Tool(description = """
            Get every file path in the entire vault as compact JSON: {"total": N, "files": ["path/to/note.md", ...]}.
            Call this first whenever you don't know where to look — the full sorted path list reveals the folder \
            structure and lets you identify relevant files before reading them.
            The paths returned are the exact values to pass into getNoteContent, getOutgoingLinks, getNoteMetadata, etc.""")
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

    @Tool(description = """
            Fetch the complete raw markdown content of a note, including frontmatter, tags, body text, and [[wiki-links]].
            Requires an exact file path — obtain it from getVaultFileTree or from searchNotes results.
            Use this when you need to read the full content of a specific note.""")
    public String getNoteContent(
            @ToolParam(description = "Exact file path relative to vault root, e.g. 'Projects/MyProject.md'. Must include the .md extension.") String filePath) {
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

    @Tool(description = """
            Get file metadata for a note: name, path, size, SHA hash, and direct GitHub HTML/download URLs.
            Use this when you need the GitHub link to share or verify a note, or to check its size before reading.
            Does NOT return note content — use getNoteContent for that.""")
    public String getNoteMetadata(
            @ToolParam(description = "Exact file path relative to vault root, e.g. 'Projects/MyProject.md'. Must include the .md extension.") String filePath) {
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
