package org.soynx.mcp.knowledgebase.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.soynx.mcp.knowledgebase.service.VaultService;
import org.soynx.mcp.knowledgebase.service.VaultServiceFactory;
import org.soynx.mcp.knowledgebase.tools.VaultBrowserTool;
import org.soynx.mcp.knowledgebase.tools.VaultGraphTool;
import org.soynx.mcp.knowledgebase.tools.VaultSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.*;

/**
 * Spring configuration for the MCP server layer.
 * <p>
 * Dynamically creates one MCP server per configured setup. Each setup gets its own
 * {@link WebMvcStreamableServerTransportProvider} bound to the setup's configured path,
 * and its own {@link McpSyncServer} with tool instances wired to that setup's repositories.
 * All transport router functions are combined into a single Spring MVC
 * {@link RouterFunction} bean.
 * </p>
 *
 * <h3>Backward compatibility</h3>
 * If no {@code REPOS__*} variables are present but the legacy {@code GITHUB_REPOSITORY}
 * variable is set, a single default repo and setup at {@code /mcp} are synthesised
 * automatically — existing deployments need no config changes.
 */
@Slf4j
@Configuration
public class McpToolsConfig {

    @Autowired
    private VaultProperties vaultProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${github.token:}")
    private String legacyToken;

    @Value("${github.repository:}")
    private String legacyRepository;

    @Value("${github.vault-root:}")
    private String legacyVaultRoot;

    @Value("${spring.ai.mcp.server.name:KnowledgeGithubMCP}")
    private String serverName;

    @Value("${spring.ai.mcp.server.version:1.0.0}")
    private String serverVersion;

    /** Holds all running MCP servers so they can be shut down cleanly. */
    private final List<McpSyncServer> activeServers = new ArrayList<>();

    // -------------------------------------------------------------------------
    // Dynamic MCP router / server creation
    // -------------------------------------------------------------------------

    /**
     * Creates one {@link WebMvcStreamableServerTransportProvider} and one {@link McpSyncServer}
     * per configured setup, then combines all their router functions into one Spring MVC bean.
     * <p>
     * Validation runs here (during bean creation) so misconfiguration fails fast at startup,
     * before any GitHub connections are attempted.
     * </p>
     */
    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction() {
        Map<String, RepoConfig>  repos  = resolvedRepos();
        Map<String, SetupConfig> setups = resolvedSetups(repos);

        // Fail fast during bean creation — before any GitHub connections are attempted
        if (repos.isEmpty()) {
            throw new IllegalStateException(
                "No repositories configured. Set REPOS__<KEY>__ORIGIN=owner/repo " +
                "or use the legacy GITHUB_REPOSITORY variable.");
        }
        if (setups.isEmpty()) {
            throw new IllegalStateException(
                "No setups configured. Set SETUP__<KEY>__PATH=/path/mcp and " +
                "SETUP__<KEY>__CONTENT=repoKey1,repoKey2.");
        }
        for (Map.Entry<String, SetupConfig> entry : setups.entrySet()) {
            String setupKey = entry.getKey();
            List<String> content = entry.getValue().getContent();
            if (content == null || content.isEmpty()) {
                throw new IllegalStateException(
                    "Setup '" + setupKey + "' has no content keys defined. " +
                    "Set SETUP__" + setupKey.toUpperCase().replace('-', '_') + "__CONTENT=repoKey.");
            }
            for (String repoKey : content) {
                if (!repos.containsKey(repoKey)) {
                    throw new IllegalStateException(
                        "Setup '" + setupKey + "' references undefined repo key '" + repoKey + "'. " +
                        "Available keys: " + repos.keySet());
                }
            }
        }
        log.info("[McpToolsConfig] Configuration validated: {} repo(s), {} setup(s)", repos.size(), setups.size());

        // Pre-build all VaultService instances (one per repo, shared across setups that reference it)
        Map<String, VaultService> serviceMap = new LinkedHashMap<>();
        for (Map.Entry<String, RepoConfig> entry : repos.entrySet()) {
            serviceMap.put(entry.getKey(), VaultServiceFactory.create(entry.getKey(), entry.getValue()));
        }

        RouterFunction<ServerResponse> combined = null;
        int totalMappings = 0;

        for (Map.Entry<String, SetupConfig> setupEntry : setups.entrySet()) {
            String     setupKey = setupEntry.getKey();
            SetupConfig setup   = setupEntry.getValue();
            String     endpoint = setup.getPath();

            List<VaultService> servicesForSetup = setup.getContent().stream()
                    .map(serviceMap::get)
                    .toList();

            // Startup log: one line per repo-to-endpoint mapping
            for (VaultService svc : servicesForSetup) {
                RepoConfig cfg = repos.get(svc.getRepoKey());
                String rootDisplay = (cfg.getRoot() == null || cfg.getRoot().isBlank()) ? "" : cfg.getRoot();
                log.info("[McpToolsConfig] Exposing repo '{}' (root='{}') at endpoint '{}'",
                        cfg.getOrigin(), rootDisplay, endpoint);
                totalMappings++;
            }

            // Create transport for this endpoint
            WebMvcStreamableServerTransportProvider transport =
                    WebMvcStreamableServerTransportProvider.builder()
                            .objectMapper(objectMapper)
                            .mcpEndpoint(endpoint)
                            .build();

            // Instantiate tool classes (plain objects, not Spring beans)
            VaultBrowserTool browserTool = new VaultBrowserTool(servicesForSetup);
            VaultSearchTool  searchTool  = new VaultSearchTool(servicesForSetup);
            VaultGraphTool   graphTool   = new VaultGraphTool(servicesForSetup);

            ToolCallbackProvider toolProvider = MethodToolCallbackProvider.builder()
                    .toolObjects(browserTool, searchTool, graphTool)
                    .build();

            McpSyncServer server = buildServer(transport, toolProvider);
            activeServers.add(server);

            RouterFunction<ServerResponse> rf = transport.getRouterFunction();
            combined = (combined == null) ? rf : combined.and(rf);

            log.info("[McpToolsConfig] MCP server '{}' ready at '{}'", setupKey, endpoint);
        }

        log.info("[McpToolsConfig] Total: {} setup(s), {} repo-endpoint mapping(s)", setups.size(), totalMappings);

        if (combined == null) {
            throw new IllegalStateException("No MCP setups were registered — check your configuration.");
        }
        return combined;
    }

    @PreDestroy
    public void shutdown() {
        log.info("[McpToolsConfig] Shutting down {} MCP server(s)...", activeServers.size());
        for (McpSyncServer server : activeServers) {
            try { server.close(); } catch (Exception e) {
                log.warn("[McpToolsConfig] Error closing MCP server: {}", e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Backward-compat resolution
    // -------------------------------------------------------------------------

    /**
     * Returns the effective repos map.
     * If {@link VaultProperties#getRepos()} is empty and the legacy {@code GITHUB_REPOSITORY}
     * variable is set, synthesises a single {@code "default"} repo from the legacy vars.
     */
    private Map<String, RepoConfig> resolvedRepos() {
        Map<String, RepoConfig> repos = vaultProperties.getRepos();
        if (!repos.isEmpty()) return repos;

        if (legacyRepository != null && !legacyRepository.isBlank()) {
            log.info("[McpToolsConfig] Legacy GITHUB_REPOSITORY detected — creating default repo");
            RepoConfig defaultRepo = new RepoConfig();
            defaultRepo.setOrigin(legacyRepository);
            defaultRepo.setToken(legacyToken);
            defaultRepo.setRoot(legacyVaultRoot);
            return Map.of("default", defaultRepo);
        }
        return repos; // empty — validator will catch this
    }

    /**
     * Returns the effective setups map.
     * If {@link VaultProperties#getSetups()} is empty and backward-compat mode is active
     * (i.e., {@code resolvedRepos} synthesised a {@code "default"} repo), synthesises a
     * single {@code "default"} setup at {@code /mcp}.
     */
    private Map<String, SetupConfig> resolvedSetups(Map<String, RepoConfig> resolvedRepos) {
        Map<String, SetupConfig> setups = vaultProperties.getSetups();
        if (!setups.isEmpty()) return setups;

        // Only synthesise if we're in legacy mode (no REPOS__ vars, but GITHUB_REPOSITORY present)
        if (resolvedRepos.size() == 1 && resolvedRepos.containsKey("default") &&
            legacyRepository != null && !legacyRepository.isBlank()) {
            log.info("[McpToolsConfig] Legacy mode — creating default setup at /mcp");
            SetupConfig defaultSetup = new SetupConfig();
            defaultSetup.setPath("/mcp");
            defaultSetup.setContent(List.of("default"));
            return Map.of("default", defaultSetup);
        }
        return setups; // empty — validator will catch this
    }

    // -------------------------------------------------------------------------
    // MCP server builder (same logic as original McpToolsConfig)
    // -------------------------------------------------------------------------

    private McpSyncServer buildServer(WebMvcStreamableServerTransportProvider transport,
                                      ToolCallbackProvider toolProvider) {
        List<McpServerFeatures.SyncToolSpecification> specs =
            Arrays.stream(toolProvider.getToolCallbacks())
                .map(tc -> {
                    var def = tc.getToolDefinition();
                    String schema = def.inputSchema();
                    if (schema == null || schema.isBlank() || schema.equals("{}")) {
                        schema = "{\"type\":\"object\",\"properties\":{}}";
                    }
                    McpSchema.Tool tool = new McpSchema.Tool(def.name(), def.description(), schema);
                    return new McpServerFeatures.SyncToolSpecification(
                            tool,
                            null,
                            (exchange, request) -> {
                                try {
                                    String args   = objectMapper.writeValueAsString(request.arguments());
                                    String result = tc.call(args);
                                    return new McpSchema.CallToolResult(
                                            List.of(new McpSchema.TextContent(null, result, null)),
                                            false, null, null);
                                } catch (Exception e) {
                                    return new McpSchema.CallToolResult(
                                            List.of(new McpSchema.TextContent(null, "Error: " + e.getMessage(), null)),
                                            true, null, null);
                                }
                            });
                })
                .toList();

        return McpServer.sync(transport)
                .serverInfo(serverName, serverVersion)
                .tools(specs)
                .build();
    }
}
