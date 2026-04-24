package org.soynx.mcp.knowledgebase.tools;

import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHContent;
import org.soynx.mcp.knowledgebase.service.VaultService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP tool class for browsing the vault's file system structure and reading note content.
 * <p>
 * Supports both single-repo and multi-repo setups. In multi-repo mode all returned paths
 * carry a {@code repoKey/} prefix (e.g. {@code vault/Projects/Note.md}) so the AI can
 * pass them back unmodified to tools that accept a file path.
 * </p>
 */
@Slf4j
public class VaultBrowserTool {

    private final List<VaultService> services;

    public VaultBrowserTool(List<VaultService> services) {
        this.services = services;
    }

    // -------------------------------------------------------------------------
    // Tools
    // -------------------------------------------------------------------------

    /**
     * Lists the immediate contents of a vault directory (one level deep).
     */
    @Tool(description = """
            List the immediate files and folders inside a specific vault directory.
            Use this to explore a known folder's contents in detail (includes file sizes).
            For a full vault overview use getVaultFileTree instead — it is cheaper and shows everything at once.
            Pass an empty string to list the vault root.
            In a multi-repo setup, paths must be prefixed with the repo key, e.g. 'vault/Projects'.\
            Pass an empty string to see all repo keys available at the root.""")
    public String listVaultContents(
            @ToolParam(description = "Folder path relative to vault root, e.g. 'Projects' or 'Areas/Work'. In multi-repo setups prefix with the repo key: 'vault/Projects'. Pass empty string for the root.") String path) {

        String effectivePath = path == null ? "" : path.trim();
        boolean multiRepo = MultiRepoPathUtil.isMultiRepo(services);
        log.info("[listVaultContents] path='{}', multiRepo={}", effectivePath.isBlank() ? "/" : effectivePath, multiRepo);

        // Multi-repo root: return synthetic listing of all repo keys
        if (multiRepo && effectivePath.isBlank()) {
            StringBuilder sb = new StringBuilder("Available repositories in this setup:\n\n");
            for (VaultService svc : services) {
                sb.append("  [REPO]  ").append(svc.getRepoKey()).append("/\n");
            }
            sb.append("\nPass 'repoKey/' as the path to browse inside a repository.");
            return sb.toString();
        }

        // Route to the correct service
        String[] parts = MultiRepoPathUtil.splitPrefix(effectivePath, services);
        VaultService svc = MultiRepoPathUtil.findService(services, parts[0]);
        if (svc == null) {
            return "ERROR: Unknown repo key '" + parts[0] + "'. Available: " +
                    services.stream().map(VaultService::getRepoKey).toList();
        }

        List<GHContent> entries = svc.listDirectory(parts[1]);
        if (entries.isEmpty()) {
            log.warn("[listVaultContents] No entries at path='{}'", effectivePath);
            return "No entries found at path '" + effectivePath + "'. The path may not exist or the directory is empty.";
        }

        List<GHContent> folders = entries.stream().filter(e -> "dir".equals(e.getType())).toList();
        List<GHContent> files   = entries.stream().filter(e -> "file".equals(e.getType())).toList();

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

    /**
     * Returns the entire vault file tree as compact JSON.
     */
    @Tool(description = """
            Get every file path in the entire vault as compact JSON: {"total": N, "files": ["path/to/note.md", ...]}.
            Call this first whenever you don't know where to look — the full sorted path list reveals the folder \
            structure and lets you identify relevant files before reading them.
            In a multi-repo setup paths are prefixed with the repo key, e.g. "vault/Projects/Note.md".
            The paths returned are the exact values to pass into getNoteContent, getOutgoingLinks, getNoteMetadata, etc.""")
    public String getVaultFileTree() {
        boolean multiRepo = MultiRepoPathUtil.isMultiRepo(services);
        log.info("[getVaultFileTree] Fetching full recursive file tree, multiRepo={}", multiRepo);

        List<String> allPaths = new ArrayList<>();
        for (VaultService svc : services) {
            List<String> paths = svc.getAllFilePaths();
            if (multiRepo) {
                paths = paths.stream().map(p -> svc.getRepoKey() + "/" + p).toList();
            }
            allPaths.addAll(paths);
        }

        if (allPaths.isEmpty()) {
            log.warn("[getVaultFileTree] All services returned empty path lists");
            return "ERROR: Could not retrieve file tree or vault is empty.";
        }

        allPaths.sort(String::compareTo);

        StringBuilder sb = new StringBuilder();
        sb.append("{\"total\":").append(allPaths.size()).append(",\"files\":[");
        for (int i = 0; i < allPaths.size(); i++) {
            sb.append("\"").append(allPaths.get(i).replace("\"", "\\\"")).append("\"");
            if (i < allPaths.size() - 1) sb.append(",");
        }
        sb.append("]}");
        log.debug("[getVaultFileTree] Returned {} path(s) ({} chars)", allPaths.size(), sb.length());
        return sb.toString();
    }

    /**
     * Fetches the full raw markdown content of a note.
     */
    @Tool(description = """
            Fetch the complete raw markdown content of a note, including frontmatter, tags, body text, and [[wiki-links]].
            Requires an exact file path — obtain it from getVaultFileTree or from searchNotes results.
            In a multi-repo setup the path must include the repo key prefix, e.g. 'vault/Projects/MyProject.md'.
            Use this when you need to read the full content of a specific note.""")
    public String getNoteContent(
            @ToolParam(description = "Exact file path relative to vault root, e.g. 'Projects/MyProject.md'. In multi-repo setups include the repo key prefix: 'vault/Projects/MyProject.md'.") String filePath) {

        if (filePath == null || filePath.isBlank()) {
            log.warn("[getNoteContent] Called with null or blank filePath");
            return "ERROR: filePath must not be empty.";
        }
        log.info("[getNoteContent] Fetching content for '{}'", filePath);

        String[] parts = MultiRepoPathUtil.splitPrefix(filePath, services);
        VaultService svc = MultiRepoPathUtil.findService(services, parts[0]);
        if (svc == null) {
            return "ERROR: Unknown repo key '" + parts[0] + "'. Path must start with a known repo key.";
        }

        String content = svc.getFileContent(parts[1]);
        if (content.startsWith("ERROR:")) {
            log.warn("[getNoteContent] Error for '{}': {}", filePath, content);
        }
        return content;
    }

    /**
     * Returns file metadata for a note (size, SHA, GitHub URLs).
     */
    @Tool(description = """
            Get file metadata for a note: name, path, size, SHA hash, and direct GitHub HTML/download URLs.
            Use this when you need the GitHub link to share or verify a note, or to check its size before reading.
            In a multi-repo setup the path must include the repo key prefix, e.g. 'vault/Projects/MyProject.md'.
            Does NOT return note content — use getNoteContent for that.""")
    public String getNoteMetadata(
            @ToolParam(description = "Exact file path relative to vault root. In multi-repo setups include the repo key prefix: 'vault/Projects/MyProject.md'.") String filePath) {

        if (filePath == null || filePath.isBlank()) {
            log.warn("[getNoteMetadata] Called with null or blank filePath");
            return "ERROR: filePath must not be empty.";
        }
        log.info("[getNoteMetadata] Fetching metadata for '{}'", filePath);

        String[] parts = MultiRepoPathUtil.splitPrefix(filePath, services);
        VaultService svc = MultiRepoPathUtil.findService(services, parts[0]);
        if (svc == null) {
            return "ERROR: Unknown repo key '" + parts[0] + "'. Path must start with a known repo key.";
        }

        GHContent meta = svc.getFileMetadata(parts[1]);
        if (meta == null) {
            log.warn("[getNoteMetadata] File not found: '{}'", filePath);
            return "ERROR: File not found at path '" + filePath + "'. Please check the path and try again.";
        }

        boolean multiRepo = MultiRepoPathUtil.isMultiRepo(services);
        String displayPath = MultiRepoPathUtil.prefixPath(meta.getPath(), svc.getRepoKey(), multiRepo);

        StringBuilder sb = new StringBuilder();
        sb.append("Metadata for: ").append(displayPath).append("\n");
        sb.append("  Name:     ").append(meta.getName()).append("\n");
        sb.append("  Path:     ").append(displayPath).append("\n");
        sb.append("  Size:     ").append(formatSize(meta.getSize())).append("\n");
        sb.append("  SHA:      ").append(meta.getSha()).append("\n");
        try {
            sb.append("  URL:      ").append(meta.getHtmlUrl()).append("\n");
            sb.append("  Download: ").append(meta.getDownloadUrl()).append("\n");
        } catch (IOException e) {
            sb.append("  URL:      (unavailable)\n");
        }
        return sb.toString().trim();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
