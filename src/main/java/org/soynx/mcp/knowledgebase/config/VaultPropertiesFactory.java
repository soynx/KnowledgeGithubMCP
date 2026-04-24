package org.soynx.mcp.knowledgebase.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code REPOS__*} and {@code SETUP__*} environment variables into
 * a {@link VaultProperties} instance.
 *
 * <h3>Naming convention</h3>
 * <pre>
 *   REPOS__&lt;KEY&gt;__ORIGIN  — repository owner/repo string
 *   REPOS__&lt;KEY&gt;__TOKEN   — GitHub PAT (blank = anonymous / public repo)
 *   REPOS__&lt;KEY&gt;__ROOT    — vault subfolder path (blank = repo root)
 *
 *   SETUP__&lt;KEY&gt;__PATH    — MCP endpoint URL path, e.g. /notes/mcp
 *   SETUP__&lt;KEY&gt;__CONTENT — comma-separated list of repo keys
 * </pre>
 *
 * <h3>Key normalisation</h3>
 * The middle {@code <KEY>} segment is normalised to lowercase with underscores
 * replaced by hyphens: {@code REPO_1} → {@code repo-1}.
 * This ensures env var keys and the comma-separated content values always match.
 */
@Slf4j
@Configuration
public class VaultPropertiesFactory {

    @Bean
    public VaultProperties vaultProperties() {
        VaultProperties props = new VaultProperties();
        Map<String, String> env = System.getenv();

        for (Map.Entry<String, String> entry : env.entrySet()) {
            String key   = entry.getKey();
            String value = entry.getValue() != null ? entry.getValue() : "";

            if (key.startsWith("REPOS__")) {
                String[] parts = key.split("__", 3);
                if (parts.length != 3) continue;

                String repoKey = normalizeKey(parts[1]);
                String field   = parts[2].toLowerCase();

                RepoConfig repo = props.getRepos().computeIfAbsent(repoKey, k -> new RepoConfig());
                switch (field) {
                    case "origin" -> { repo.setOrigin(value);  log.debug("[VaultPropertiesFactory] Repo '{}' origin='{}'", repoKey, value); }
                    case "token"  -> { repo.setToken(value);   log.debug("[VaultPropertiesFactory] Repo '{}' token={}", repoKey, value.isBlank() ? "(anonymous)" : "(set)"); }
                    case "root"   -> { repo.setRoot(value);    log.debug("[VaultPropertiesFactory] Repo '{}' root='{}'", repoKey, value); }
                    default -> log.warn("[VaultPropertiesFactory] Unknown REPOS field '{}' in env var '{}' — ignored", field, key);
                }

            } else if (key.startsWith("SETUP__")) {
                String[] parts = key.split("__", 3);
                if (parts.length != 3) continue;

                String setupKey = normalizeKey(parts[1]);
                String field    = parts[2].toLowerCase();

                SetupConfig setup = props.getSetups().computeIfAbsent(setupKey, k -> new SetupConfig());
                switch (field) {
                    case "path" -> { setup.setPath(value);  log.debug("[VaultPropertiesFactory] Setup '{}' path='{}'", setupKey, value); }
                    case "content" -> {
                        List<String> repos = Arrays.stream(value.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isBlank())
                                .toList();
                        setup.setContent(repos);
                        log.debug("[VaultPropertiesFactory] Setup '{}' content={}", setupKey, repos);
                    }
                    default -> log.warn("[VaultPropertiesFactory] Unknown SETUP field '{}' in env var '{}' — ignored", field, key);
                }
            }
        }

        log.info("[VaultPropertiesFactory] Parsed {} repo(s) {} and {} setup(s) {} from environment",
                props.getRepos().size(), props.getRepos().keySet(),
                props.getSetups().size(), props.getSetups().keySet());
        return props;
    }

    /**
     * Normalises an env var key segment to a logical key:
     * uppercase → lowercase, underscores → hyphens.
     * Example: {@code REPO_1} → {@code repo-1}, {@code NOTES} → {@code notes}.
     */
    private String normalizeKey(String raw) {
        return raw.toLowerCase().replace('_', '-');
    }
}
