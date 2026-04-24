package org.soynx.mcp.knowledgebase.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for a named MCP endpoint setup.
 * Each setup maps a URL path to one or more named repositories and becomes
 * its own independent MCP server endpoint.
 *
 * <p>Example env vars:</p>
 * <pre>
 *   SETUP__NOTES__PATH=/notes/mcp
 *   SETUP__NOTES__CONTENT=vault,docs
 * </pre>
 *
 * <p>A setup with multiple repos aggregates all tool results across those repos.</p>
 */
public class SetupConfig {

    /** The HTTP endpoint path for this MCP server, e.g. {@code /notes/mcp}. Required. */
    private String path;

    /**
     * Ordered list of repo keys (defined in {@code REPOS__*}) that this setup exposes.
     * Populated by splitting the comma-separated {@code CONTENT} env var value.
     */
    private List<String> content = new ArrayList<>();

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public List<String> getContent() { return content; }
    public void setContent(List<String> content) { this.content = content; }
}
