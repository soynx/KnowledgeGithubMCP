package org.soynx.mcp.knowledgebase.config;

/**
 * Configuration for a single GitHub repository entry.
 * Populated from environment variables with the {@code REPOS__<key>__} prefix.
 *
 * <p>Example env vars:</p>
 * <pre>
 *   REPOS__VAULT__ORIGIN=username/my-obsidian-vault
 *   REPOS__VAULT__TOKEN=ghp_xxxxxxxxxxxx
 *   REPOS__VAULT__ROOT=notes/
 * </pre>
 */
public class RepoConfig {

    /** Repository in {@code owner/repo} format, e.g. {@code soynx/my-vault}. Required. */
    private String origin;

    /** GitHub PAT with Contents:read permission. Leave blank for public repositories. */
    private String token;

    /** Subfolder path within the repository where the vault root lives. Leave blank for repo root. */
    private String root;

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getRoot() { return root; }
    public void setRoot(String root) { this.root = root; }
}
