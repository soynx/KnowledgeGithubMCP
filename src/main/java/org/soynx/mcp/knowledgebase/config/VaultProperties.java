package org.soynx.mcp.knowledgebase.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holder for all resolved repository and setup configuration.
 * Populated by {@link VaultPropertiesFactory} at application startup by scanning
 * {@code REPOS__*} and {@code SETUP__*} environment variables.
 */
public class VaultProperties {

    /**
     * Map of logical repo key → {@link RepoConfig}.
     * Keys are normalised to lowercase with underscores converted to hyphens,
     * e.g. env var segment {@code REPO_1} → key {@code repo-1}.
     */
    private Map<String, RepoConfig> repos = new LinkedHashMap<>();

    /**
     * Map of logical setup key → {@link SetupConfig}.
     * Keys follow the same normalisation as repo keys.
     */
    private Map<String, SetupConfig> setups = new LinkedHashMap<>();

    public Map<String, RepoConfig> getRepos() { return repos; }
    public void setRepos(Map<String, RepoConfig> repos) { this.repos = repos; }

    public Map<String, SetupConfig> getSetups() { return setups; }
    public void setSetups(Map<String, SetupConfig> setups) { this.setups = setups; }
}
