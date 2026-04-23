package org.soynx.mcp.config;

import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * Spring configuration for the GitHub API client.
 * <p>
 * Initializes an authenticated {@link GitHub} client using the PAT supplied via the
 * {@code GITHUB_TOKEN} environment variable, and connects to the repository specified
 * by {@code GITHUB_REPOSITORY}. Both beans are singletons shared across all tool classes.
 * </p>
 */
@Slf4j
@Configuration
public class GitHubConfig {

    @Value("${github.token}")
    private String token;

    @Value("${github.repository}")
    private String repository;

    /**
     * Creates and authenticates the GitHub API client.
     *
     * @return authenticated {@link GitHub} instance
     * @throws IOException if authentication fails or the GitHub API is unreachable
     */
    @Bean
    public GitHub gitHubClient() throws IOException {
        log.debug("[GitHubConfig] Initializing GitHub client for repository: {}", repository);
        GitHub client = new GitHubBuilder().withOAuthToken(token).build();
        log.info("[GitHubConfig] GitHub client initialized successfully");
        return client;
    }

    /**
     * Connects to and returns the configured vault repository.
     *
     * @param gitHubClient the authenticated GitHub client
     * @return the {@link GHRepository} instance for the configured repository
     * @throws IOException if the repository does not exist or the token lacks access
     */
    @Bean
    public GHRepository ghRepository(GitHub gitHubClient) throws IOException {
        log.debug("[GitHubConfig] Connecting to repository: {}", repository);
        GHRepository repo = gitHubClient.getRepository(repository);
        log.info("[GitHubConfig] Connected to repository '{}' (private={})", repo.getFullName(), repo.isPrivate());
        return repo;
    }
}
