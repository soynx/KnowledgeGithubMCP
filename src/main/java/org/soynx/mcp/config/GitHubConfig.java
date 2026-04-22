package org.soynx.mcp.config;

import lombok.extern.slf4j.Slf4j;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Slf4j
@Configuration
public class GitHubConfig {

    @Value("${github.token}")
    private String token;

    @Value("${github.repository}")
    private String repository;

    @Bean
    public GitHub gitHubClient() throws IOException {
        log.info("Initializing GitHub client for repository: {}", repository);
        return new GitHubBuilder().withOAuthToken(token).build();
    }

    @Bean
    public GHRepository ghRepository(GitHub gitHubClient) throws IOException {
        log.info("Connecting to repository: {}", repository);
        return gitHubClient.getRepository(repository);
    }
}
