package org.soynx.mcp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.soynx.mcp.tools.VaultBrowserTool;
import org.soynx.mcp.tools.VaultGraphTool;
import org.soynx.mcp.tools.VaultSearchTool;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

import java.util.Arrays;
import java.util.List;

/**
 * Spring configuration for the MCP server and tool registration.
 * <p>
 * Sets up the Streamable HTTP transport on {@code /mcp}, registers all vault tool classes
 * ({@link VaultBrowserTool}, {@link VaultSearchTool}, {@link VaultGraphTool}) with the MCP server,
 * and wires the tool callback dispatch loop that routes incoming MCP tool-call requests to the
 * correct Java method.
 * </p>
 */
@Configuration
public class McpToolsConfig {

    /**
     * Creates the Streamable HTTP transport provider that handles MCP protocol communication
     * over {@code POST /mcp} and {@code GET /mcp} (SSE stream).
     *
     * @param objectMapper Jackson mapper used for JSON serialization of MCP messages
     * @return configured transport provider
     */
    @Bean
    public WebMvcStreamableServerTransportProvider mcpTransportProvider(ObjectMapper objectMapper) {
        return WebMvcStreamableServerTransportProvider.builder()
                .objectMapper(objectMapper)
                .mcpEndpoint("/mcp")
                .build();
    }

    /**
     * Registers the MCP transport's router function with Spring MVC so that
     * {@code /mcp} requests are dispatched to the transport provider.
     *
     * @param transportProvider the configured Streamable HTTP transport
     * @return the router function for {@code /mcp}
     */
    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(
            WebMvcStreamableServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }

    /**
     * Builds and starts the synchronous MCP server, registering all tool specifications
     * discovered from the {@link ToolCallbackProvider}.
     * <p>
     * Each tool's input schema is patched to always be a valid JSON Schema object — the MCP spec
     * requires a non-empty schema even for tools with no parameters.
     * </p>
     *
     * @param transport     the Streamable HTTP transport provider
     * @param vaultTools    the provider containing all registered vault tool callbacks
     * @param objectMapper  Jackson mapper for serializing tool arguments
     * @param serverName    MCP server name, from {@code spring.ai.mcp.server.name}
     * @param serverVersion MCP server version, from {@code spring.ai.mcp.server.version}
     * @return the running {@link McpSyncServer} instance
     */
    @Bean
    public McpSyncServer mcpSyncServer(
            WebMvcStreamableServerTransportProvider transport,
            ToolCallbackProvider vaultTools,
            ObjectMapper objectMapper,
            @Value("${spring.ai.mcp.server.name:KnowledgeGithubMCP}") String serverName,
            @Value("${spring.ai.mcp.server.version:1.0.0}") String serverVersion) {

        List<McpServerFeatures.SyncToolSpecification> specs = Arrays.stream(vaultTools.getToolCallbacks())
                .map(tc -> {
                    var def = tc.getToolDefinition();
                    String schema = def.inputSchema();
                    if (schema == null || schema.isBlank() || schema.equals("{}")) {
                        schema = "{\"type\":\"object\",\"properties\":{}}";
                    }
                    McpSchema.Tool tool = new McpSchema.Tool(
                            def.name(), def.description(), schema);
                    return new McpServerFeatures.SyncToolSpecification(
                            tool,
                            null,
                            (exchange, request) -> {
                                try {
                                    String args = objectMapper.writeValueAsString(request.arguments());
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

    /**
     * Creates the {@link ToolCallbackProvider} by registering all vault tool instances.
     * Spring AI's {@link MethodToolCallbackProvider} scans each object for {@code @Tool}-annotated
     * methods and exposes them as MCP-callable tools.
     *
     * @param browserTool tool class for file system browsing and content reading
     * @param searchTool  tool class for full-text and tag-based search
     * @param graphTool   tool class for wiki-link graph navigation and git history
     * @return the assembled tool callback provider
     */
    @Bean
    public ToolCallbackProvider vaultTools(
            VaultBrowserTool browserTool,
            VaultSearchTool searchTool,
            VaultGraphTool graphTool) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(browserTool, searchTool, graphTool)
                .build();
    }
}
