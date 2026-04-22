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

@Configuration
public class McpToolsConfig {

    @Bean
    public WebMvcStreamableServerTransportProvider mcpTransportProvider(ObjectMapper objectMapper) {
        return WebMvcStreamableServerTransportProvider.builder()
                .objectMapper(objectMapper)
                .mcpEndpoint("/mcp")
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> mcpRouterFunction(
            WebMvcStreamableServerTransportProvider transportProvider) {
        return transportProvider.getRouterFunction();
    }

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
                    McpSchema.Tool tool = new McpSchema.Tool(
                            def.name(), def.description(), def.inputSchema());
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
