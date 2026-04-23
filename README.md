# KnowledgeGithubMCP

A **read-only Spring Boot MCP Server** that exposes a private notes vault hosted on GitHub to Claude via the [Model Context Protocol](https://modelcontextprotocol.io). Claude can browse, search, and navigate the vault's knowledge graph without ever having direct access to the repository or credentials.


> [!IMPORTANT]
> currently *(as of 23.04.2026 / v.1.2.0)*, when connecting claude to this server, it will most likely forward you to a claude website saying `"Couldn't connect"`.
> It will ask to switch to the claude desktop app again. After switching to the app, it will again show a warning in the top right corner, saying it can`t connect to the server.
> **THE SERVER WORKS REGARDLESS, THESE MESSAGES CAN SAVELY BE IGNORED!**
> I don't know if this is an issue with my code (most likely), my network setup or just because antropic just newly release the streamable-http MCP-Server feature.
> **I am on this bug and will fix it as soon as i can!**


---

## Features

| Tool                  | Description                                                                                                     |
|-----------------------|-----------------------------------------------------------------------------------------------------------------|
| `listVaultContents`   | List all files and folders at any path in the vault (use empty string for root)                                 |
| `getNoteContent`      | Fetch the full markdown content of a note by its file path                                                      |
| `getNoteMetadata`     | Retrieve file metadata: size, SHA, and direct GitHub URL                                                        |
| `searchNotes`         | Search notes by content AND file path using a list of terms — any match returns the note (OR logic, path-aware) |
| `getVaultFileTree`    | Returns the complete vault file list as compact JSON: `{"total": N, "files": ["path/a.md", ...]}`               |
| `searchByTag`         | Find all notes containing any of the specified Obsidian tags — accepts a list for broader recall (OR logic)     |
| `getOutgoingLinks`    | Extract all `[[wiki-links]]` from a note and resolve which ones exist                                           |
| `getIncomingLinks`    | Find all notes that link back to a given note (backlinks)                                                       |
| `getRecentlyModified` | List notes modified in the last N days, sorted by most recent first                                             |

All tools return plain strings. Errors are surfaced as descriptive messages — no exceptions are ever propagated to Claude.

---

## Docker Setup

### 1. Clone and configure

```bash
git clone https://github.com/soynx/KnowledgeGithubMCP.git
cd KnowledgeGithubMCP
cp docker-compose.yml.sample docker-compose.yml
```

Open `docker-compose.yml` and fill in your values:

```yaml
environment:
  - GITHUB_TOKEN=ghp_your_readonly_pat_here
  - GITHUB_REPOSITORY=youruser/your-vault-repo
  - VAULT_ROOT=        # leave empty if vault is at repo root
  - LOG_LEVEL=INFO     # TRACE | DEBUG | INFO | WARN | ERROR
```

### 2. Build and run

```bash
docker compose up --build -d
```

The server starts on **port 8080**.

### 3. Optional: persist logs

Uncomment the volumes block in `docker-compose.yml` to mount logs to your host:

```yaml
volumes:
  - /your/host/path/logs:/app/appdata/logs
```

Log files rotate daily, cap at 10 MB per file, and are retained for 7 days (200 MB total cap).

### 4. Health check

```bash
curl http://localhost:8080/actuator/health
```

---

## Connecting Claude

1. Deploy the container so it is reachable via HTTPS (e.g. via a reverse proxy on your Homelab)
2. In Claude: **Settings → Connectors → Add custom connector**
3. Enter the URL: `https://your-domain.com/mcp`
4. No OAuth is required — KnowledgeGithubMCP authenticates to GitHub internally using the PAT

> Note: when updating the server, it is recommended to disconnect and reconnect the MCP server inside claude settings
---

## MCP Server Structure

### Transport

KnowledgeGithubMCP uses **Streamable HTTP** transport (MCP spec 2025-11-25) via Spring MVC. The MCP endpoint is exposed automatically by `spring-ai-starter-mcp-server-webmvc` at:

```
POST /mcp
GET  /mcp  (SSE stream for server-sent events)
```

### Architecture

```
Claude (MCP Client)
    │
    │  Streamable HTTP  (POST /mcp)
    ▼
KnowledgeGithubMcpApplication   ← Spring Boot entry point
    │
    ├── McpToolsConfig           ← Registers all tools with the MCP server
    │
    ├── VaultBrowserTool         ← listVaultContents / getNoteContent / getNoteMetadata
    ├── VaultSearchTool          ← searchNotes / searchByTag
    └── VaultGraphTool           ← getOutgoingLinks / getIncomingLinks / getRecentlyModified
              │
              ▼
         VaultService            ← Single point of contact for all GitHub API calls
              │
              ▼
         GHRepository            ← org.kohsuke:github-api (read-only PAT)
              │
              ▼
         GitHub REST API
```

### Configuration

| Environment Variable | Required | Description                                                                         |
|----------------------|----------|-------------------------------------------------------------------------------------|
| `GITHUB_TOKEN`       | Yes      | Read-only GitHub PAT (`repo` scope)                                                 |
| `GITHUB_REPOSITORY`  | Yes      | Repository in `username/repo-name` format                                           |
| `VAULT_ROOT`         | No       | Subfolder within the repo where the vault lives. Leave empty for repo root.         |
| `LOG_LEVEL`          | No       | Root log level. Default: `WARN`. Options: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |

### Security

- **Read-only by design** — no write operations exist anywhere in the codebase
- Credentials are injected via environment variables only — never hardcoded
- The GitHub PAT should have the minimum required scope: `repo` (read) for private repositories, or no scope at all for public ones
- KnowledgeGithubMCP itself has no authentication layer — secure it at the network/reverse-proxy level

### How to generate readonly token:
- open github.com
- Profile -> Settings -> Developer Settings -> Personal access tokens - Fine-grained tokens
- Press "Generate new token"
- Fill out name, description, expiry, etc
- Repository access: "Only selected repositories"
- *select your obsidian vault repo*
- Permissions: Repositories -> "Add permission"
- **ONLY SELECT "Contents" and make sure that access it set to "Read-only"**
- Press "Generate token" and it is ready to use
---

## Example Claude Prompts

```
"What have I written in my notes about Kubernetes?"
"Show me all notes tagged with #todo"
"What links to my note 'ProjectX'?"
"Which notes have I changed in the last 7 days?"
"Show me the content of Projects/MyProject.md"
"What are all the links in my daily note from today?"
```

---

## License

This project is licensed under the **MIT License** — free to use, modify, and distribute for any purpose. See [LICENSE](LICENSE) for details.
