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
