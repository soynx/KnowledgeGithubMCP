package org.soynx.mcp.knowledgebase.service;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/**
 * Central service for all GitHub API interactions against a single repository.
 * <p>
 * All vault operations (file reads, directory listings, search, tree traversal) go through this
 * class. It is the single point of contact between the MCP tool layer and the GitHub REST API.
 * Errors are caught here and never propagated as exceptions — callers receive {@code null},
 * empty collections, or {@code "ERROR: ..."} strings depending on the method contract.
 * </p>
 * <p>
 * Instances are created by {@link VaultServiceFactory} — this class is <em>not</em> a Spring bean.
 * Multiple instances may exist when multiple repos are configured.
 * </p>
 */
@Slf4j
public class VaultService {

    /**
     * -- GETTER --
     *  Returns the logical key that identifies this repo in multi-repo setups.
     *
     * @return repo key, e.g. {@code "vault"}
     */
    @Getter
    private final String repoKey;
    private final GitHub gitHubClient;
    /**
     * -- GETTER --
     *  Returns the underlying
     *  instance for direct GitHub API access.
     *
     * @return the repository client
     */
    @Getter
    private final GHRepository repository;
    private final String vaultRoot;

    /**
     * @param repoKey      logical key identifying this repo, e.g. {@code "vault"} or {@code "repo-1"}
     * @param gitHubClient authenticated (or anonymous) GitHub API client
     * @param repository   the connected GitHub repository
     * @param vaultRoot    optional subfolder path within the repo; blank means repo root
     */
    public VaultService(String repoKey, GitHub gitHubClient, GHRepository repository, String vaultRoot) {
        this.repoKey = repoKey;
        this.gitHubClient = gitHubClient;
        this.repository = repository;
        this.vaultRoot = vaultRoot != null ? vaultRoot : "";
    }

    /**
     * Resolves a vault-relative path to the full repository path by prepending {@code vaultRoot}
     * if one is configured.
     *
     * @param path vault-relative path, or {@code null} / blank for the vault root itself
     * @return the full repository path ready to pass to the GitHub API
     */
    public String resolvePath(String path) {
        log.trace("[resolvePath] input='{}', vaultRoot='{}'", path, vaultRoot);
        String resolved;
        if (vaultRoot.isBlank()) {
            resolved = path == null ? "" : path;
        } else if (path == null || path.isBlank()) {
            resolved = vaultRoot;
        } else {
            resolved = vaultRoot.stripTrailing() + "/" + path.stripLeading();
        }
        log.debug("[resolvePath] '{}' -> '{}'", path, resolved);
        return resolved;
    }

    /**
     * Fetches the full decoded UTF-8 content of a file from the GitHub repository.
     *
     * @param path vault-relative file path, e.g. {@code "Projects/MyProject.md"}
     * @return the file's text content, or an {@code "ERROR: ..."} string if not found or on failure
     */
    public String getFileContent(String path) {
        log.trace("[getFileContent] Requested path='{}'", path);
        String resolved = resolvePath(path);
        log.debug("[getFileContent] Fetching from GitHub: '{}'", resolved);
        try {
            GHContent content = repository.getFileContent(resolved);
            try (var is = content.read()) {
                byte[] bytes = is.readAllBytes();
                log.info("[getFileContent] Fetched '{}' ({} bytes)", resolved, bytes.length);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (GHFileNotFoundException e) {
            log.warn("[getFileContent] File not found: '{}'", resolved);
            return "ERROR: File not found at path '" + path + "'. Please check the path and try again.";
        } catch (IOException e) {
            log.error("[getFileContent] IOException reading '{}': {}", resolved, e.getMessage(), e);
            return buildIoError(e, "read file '" + path + "'");
        }
    }

    /**
     * Lists the immediate contents (files and subdirectories) of a vault directory.
     *
     * @param path vault-relative directory path, or blank for the vault root
     * @return list of {@link GHContent} entries, or an empty list if not found or on error
     */
    public List<GHContent> listDirectory(String path) {
        log.trace("[listDirectory] Requested path='{}'", path);
        String resolved = resolvePath(path);
        String githubPath = resolved.isBlank() ? "/" : resolved;
        log.debug("[listDirectory] Listing directory on GitHub: '{}'", githubPath);
        try {
            List<GHContent> entries = repository.getDirectoryContent(githubPath);
            log.info("[listDirectory] '{}' returned {} entries", githubPath, entries.size());
            return entries;
        } catch (GHFileNotFoundException e) {
            log.warn("[listDirectory] Directory not found: '{}'", githubPath);
            return Collections.emptyList();
        } catch (IOException e) {
            log.error("[listDirectory] IOException listing '{}': {}", githubPath, e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Fetches metadata (name, path, size, SHA, URLs) for a single file without reading its content.
     * Returns {@code null} if the file does not exist or on any API error.
     *
     * @param path vault-relative file path
     * @return the {@link GHContent} metadata object, or {@code null} if unavailable
     */
    public GHContent getFileMetadata(String path) {
        log.trace("[getFileMetadata] Requested path='{}'", path);
        String resolved = resolvePath(path);
        log.debug("[getFileMetadata] Fetching metadata from GitHub: '{}'", resolved);
        try {
            GHContent meta = repository.getFileContent(resolved);
            log.debug("[getFileMetadata] Metadata retrieved for '{}' (SHA: {}, size: {} bytes)",
                    resolved, meta.getSha(), meta.getSize());
            return meta;
        } catch (GHFileNotFoundException e) {
            log.debug("[getFileMetadata] No file at '{}' (not found)", resolved);
            return null;
        } catch (IOException e) {
            log.error("[getFileMetadata] IOException fetching metadata for '{}': {}", resolved, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Executes a GitHub Code Search query scoped to this repository.
     * <p>
     * GitHub Code Search supports literal text matching only — no regex. Results are paginated
     * and lazily fetched via the returned iterable.
     * </p>
     *
     * @param query the search query string (literal text, GitHub code search syntax)
     * @return a paged iterable of matching {@link GHContent} entries, or {@code null} on API error / rate limit
     */
    public PagedSearchIterable<GHContent> searchContent(String query) {
        log.trace("[searchContent] query='{}', repo='{}'", query, repository.getFullName());
        log.debug("[searchContent] Executing GitHub code search: query='{}'", query);
        try {
            PagedSearchIterable<GHContent> results = gitHubClient.searchContent()
                    .repo(repository.getFullName())
                    .q(query)
                    .list();
            log.info("[searchContent] Search submitted for query='{}'", query);
            return results;
        } catch (Exception e) {
            log.error("[searchContent] GitHub search failed for query '{}': {}", query, e.getMessage(), e);
            return null;
        }
    }

    /**
     * Returns a sorted list of all vault-relative file paths by traversing the repository's git tree.
     * <p>
     * Only blob (file) entries are included — directories are not listed. The {@code vaultRoot}
     * prefix is stripped from all paths so callers always work with vault-relative values.
     * </p>
     *
     * @return sorted list of all vault file paths, or an empty list on error
     */
    public List<String> getAllFilePaths() {
        log.trace("[getAllFilePaths] Fetching git tree for repo '{}'", repository.getFullName());
        log.debug("[getAllFilePaths] Calling getTreeRecursive HEAD");
        try {
            GHTree tree = repository.getTreeRecursive("HEAD", 1);
            String prefix = vaultRoot.isBlank() ? "" : vaultRoot.stripTrailing() + "/";
            log.trace("[getAllFilePaths] vaultRoot prefix='{}'", prefix);
            List<String> paths = tree.getTree().stream()
                    .filter(e -> "blob".equals(e.getType()))
                    .map(GHTreeEntry::getPath)
                    .filter(p -> prefix.isEmpty() || p.startsWith(prefix))
                    .map(p -> prefix.isEmpty() ? p : p.substring(prefix.length()))
                    .filter(p -> !p.isBlank())
                    .sorted()
                    .toList();
            log.info("[getAllFilePaths] Found {} file(s) in vault tree", paths.size());
            return paths;
        } catch (IOException e) {
            log.error("[getAllFilePaths] Failed to fetch git tree: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Builds a human-readable error string from an {@link IOException}, with special handling for
     * GitHub API rate limit errors.
     *
     * @param e      the caught exception
     * @param action description of the action that failed, used in the error message
     * @return a plain-text error string prefixed with {@code "ERROR: "}
     */
    public String buildIoError(IOException e, String action) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        if (message.contains("rate limit")) {
            log.warn("[buildIoError] Rate limit detected while trying to {}", action);
            return "ERROR: GitHub API rate limit exceeded. Please wait before retrying.";
        }
        return "ERROR: Failed to " + action + ". " + message;
    }
}
