package org.soynx.mcp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VaultService {

    private final GitHub gitHubClient;
    private final GHRepository repository;

    @Value("${github.vault-root:}")
    private String vaultRoot;

    public String resolvePath(String path) {
        String resolved;
        if (vaultRoot == null || vaultRoot.isBlank()) {
            resolved = path == null ? "" : path;
        } else if (path == null || path.isBlank()) {
            resolved = vaultRoot;
        } else {
            resolved = vaultRoot.stripTrailing() + "/" + path.stripLeading();
        }
        log.debug("Path resolved: '{}' -> '{}'", path, resolved);
        return resolved;
    }

    public String getFileContent(String path) {
        String resolved = resolvePath(path);
        log.debug("Fetching file content from GitHub: '{}'", resolved);
        try {
            GHContent content = repository.getFileContent(resolved);
            try (var is = content.read()) {
                byte[] bytes = is.readAllBytes();
                log.debug("File '{}' fetched successfully ({} bytes)", resolved, bytes.length);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (GHFileNotFoundException e) {
            log.warn("File not found on GitHub: '{}'", resolved);
            return "ERROR: File not found at path '" + path + "'. Please check the path and try again.";
        } catch (IOException e) {
            log.error("IOException reading file '{}': {}", resolved, e.getMessage(), e);
            return buildIoError(e, "read file '" + path + "'");
        }
    }

    public List<GHContent> listDirectory(String path) {
        String resolved = resolvePath(path);
        String githubPath = resolved.isBlank() ? "/" : resolved;
        log.debug("Listing directory on GitHub: '{}'", githubPath);
        try {
            List<GHContent> entries = repository.getDirectoryContent(githubPath);
            log.debug("Directory '{}' returned {} entries", githubPath, entries.size());
            return entries;
        } catch (GHFileNotFoundException e) {
            log.warn("Directory not found on GitHub: '{}'", githubPath);
            return Collections.emptyList();
        } catch (IOException e) {
            log.error("IOException listing directory '{}': {}", githubPath, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    public GHContent getFileMetadata(String path) {
        String resolved = resolvePath(path);
        log.debug("Fetching file metadata from GitHub: '{}'", resolved);
        try {
            GHContent meta = repository.getFileContent(resolved);
            log.debug("Metadata retrieved for '{}' (SHA: {})", resolved, meta.getSha());
            return meta;
        } catch (GHFileNotFoundException e) {
            log.debug("No file at '{}' (metadata lookup)", resolved);
            return null;
        } catch (IOException e) {
            log.error("IOException fetching metadata for '{}': {}", resolved, e.getMessage(), e);
            return null;
        }
    }

    public PagedSearchIterable<GHContent> searchContent(String query) {
        log.debug("Executing GitHub code search: query='{}' repo='{}'", query, repository.getFullName());
        try {
            return gitHubClient.searchContent()
                    .repo(repository.getFullName())
                    .q(query)
                    .list();
        } catch (Exception e) {
            log.error("GitHub search failed for query '{}': {}", query, e.getMessage(), e);
            return null;
        }
    }

    public String getFullFileTree() {
        log.debug("Fetching recursive file tree from GitHub for repo '{}'", repository.getFullName());
        try {
            GHTree tree = repository.getTreeRecursive("HEAD", 1);
            String prefix = (vaultRoot != null && !vaultRoot.isBlank())
                    ? vaultRoot.stripTrailing() + "/" : "";

            List<String> paths = tree.getTree().stream()
                    .filter(e -> "blob".equals(e.getType()))
                    .map(GHTreeEntry::getPath)
                    .filter(p -> prefix.isEmpty() || p.startsWith(prefix))
                    .map(p -> prefix.isEmpty() ? p : p.substring(prefix.length()))
                    .filter(p -> !p.isBlank())
                    .sorted()
                    .toList();

            log.debug("File tree fetched: {} files (prefix='{}')", paths.size(), prefix);
            String rootLabel = prefix.isEmpty() ? "/" : vaultRoot;
            return renderTree(paths, rootLabel);
        } catch (IOException e) {
            log.error("Failed to fetch file tree: {}", e.getMessage(), e);
            return buildIoError(e, "fetch file tree");
        }
    }

    private String renderTree(List<String> paths, String rootLabel) {
        // Build node map: dir path -> sorted child names (dirs first, then files)
        java.util.TreeMap<String, java.util.TreeSet<String>> dirs = new java.util.TreeMap<>();
        java.util.Set<String> filePaths = new java.util.HashSet<>(paths);

        dirs.put("", new java.util.TreeSet<>());
        for (String path : paths) {
            String[] parts = path.split("/");
            StringBuilder current = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                String parent = current.toString();
                if (i > 0) current.append("/");
                current.append(parts[i]);
                dirs.computeIfAbsent(parent, k -> new java.util.TreeSet<>()).add(current.toString());
                if (i < parts.length - 1) dirs.computeIfAbsent(current.toString(), k -> new java.util.TreeSet<>());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append(rootLabel).append("\n");
        renderNode(sb, "", "", dirs, filePaths);
        sb.append("\n").append(paths.size()).append(" file(s) total");
        return sb.toString();
    }

    private void renderNode(StringBuilder sb, String node, String indent,
                            java.util.TreeMap<String, java.util.TreeSet<String>> dirs,
                            java.util.Set<String> files) {
        java.util.TreeSet<String> children = dirs.getOrDefault(node, new java.util.TreeSet<>());
        // Sort: directories first, then files
        List<String> sorted = children.stream()
                .sorted(java.util.Comparator
                        .comparingInt((String c) -> dirs.containsKey(c) ? 0 : 1)
                        .thenComparing(c -> c.substring(c.lastIndexOf('/') + 1)))
                .toList();

        for (int i = 0; i < sorted.size(); i++) {
            String child = sorted.get(i);
            boolean last = (i == sorted.size() - 1);
            String name = child.substring(child.lastIndexOf('/') + 1);
            boolean isDir = dirs.containsKey(child);

            sb.append(indent).append(last ? "└── " : "├── ").append(name);
            if (isDir) sb.append("/");
            sb.append("\n");

            if (isDir) {
                renderNode(sb, child, indent + (last ? "    " : "│   "), dirs, files);
            }
        }
    }

    public GHRepository getRepository() {
        return repository;
    }

    public String getRepositoryFullName() {
        return repository.getFullName();
    }

    private String buildIoError(IOException e, String action) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (message.contains("rate limit")) {
            return "ERROR: GitHub API rate limit exceeded. Please wait before retrying.";
        }
        return "ERROR: Failed to " + action + ". " + message;
    }
}
