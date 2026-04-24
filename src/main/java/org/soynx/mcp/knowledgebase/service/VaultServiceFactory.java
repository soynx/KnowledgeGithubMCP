package org.soynx.mcp.knowledgebase.service;

import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.soynx.mcp.knowledgebase.config.RepoConfig;

import java.io.IOException;

/**
 * Static factory for creating {@link VaultService} instances.
 * <p>
 * Encapsulates GitHub client construction (anonymous vs. token-authenticated),
 * repository lookup, and error wrapping. Wraps all {@link IOException}s in
 * {@link IllegalStateException} so callers (Spring configuration beans) do not
 * need to declare checked exceptions.
 * </p>
 */
@Slf4j
public class VaultServiceFactory {

    private VaultServiceFactory() {}

    /**
     * Creates a ready-to-use {@link VaultService} for the given repo key and configuration.
     *
     * @param repoKey logical identifier for this repo, e.g. {@code "vault"} or {@code "repo-1"}
     * @param config  the repo configuration (origin, token, root)
     * @return an initialised {@link VaultService} connected to the GitHub repository
     * @throws IllegalStateException if the GitHub client cannot be created or the repository is not accessible
     */
    public static VaultService create(String repoKey, RepoConfig config) {
        String token  = config.getToken();
        String origin = config.getOrigin();
        String root   = config.getRoot() != null ? config.getRoot() : "";
        boolean anonymous = token == null || token.isBlank();

        log.info("[VaultServiceFactory] Initialising repo '{}' (origin='{}', auth={})",
                repoKey, origin, anonymous ? "anonymous" : "token");

        try {
            GitHub github = anonymous
                    ? GitHub.connectAnonymously()
                    : new GitHubBuilder().withOAuthToken(token).build();

            GHRepository repo = github.getRepository(origin);
            log.info("[VaultServiceFactory] Connected to '{}' (private={}) as repo key '{}'",
                    repo.getFullName(), repo.isPrivate(), repoKey);

            return new VaultService(repoKey, github, repo, root);

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to initialise VaultService for repo key='" + repoKey +
                    "', origin='" + origin + "': " + e.getMessage(), e);
        }
    }
}
