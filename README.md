# KnowledgeGithubMCP

A **read-only Spring Boot MCP Server** that exposes a private notes vault hosted on GitHub to Claude via the [Model Context Protocol](https://modelcontextprotocol.io). Claude can browse, search, and navigate the vault's knowledge graph without ever having direct access to the repository or credentials.

---

## Features

| Tool                  | Description                                                                     |
|-----------------------|---------------------------------------------------------------------------------|
| `listVaultContents`   | List all files and folders at any path in the vault (use empty string for root) |
| `getNoteContent`      | Fetch the full markdown content of a note by its file path                      |
| `getNoteMetadata`     | Retrieve file metadata: size, SHA, and direct GitHub URL                        |
| `searchNotes`         | Full-text search across all notes via GitHub Code Search                        |
| `searchByTag`         | Find all notes that contain a specific tag (e.g. `#todo`)                       |
| `getOutgoingLinks`    | Extract all `[[wiki-links]]` from a note and resolve which ones exist           |
| `getIncomingLinks`    | Find all notes that link back to a given note (backlinks)                       |
| `getRecentlyModified` | List notes modified in the last N days, sorted by most recent first             |

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
"Was habe ich in meinen Notizen über Kubernetes geschrieben?"
"Zeig mir alle Notizen mit dem Tag #todo"
"Was verlinkt auf meine Notiz 'ProjectX'?"
"Welche Notizen habe ich in den letzten 7 Tagen geändert?"
"Zeig mir den Inhalt von Projects/MeinProjekt.md"
"Was sind alle Verlinkungen in meiner Daily Note von heute?"
```

---

## License

This project is licensed under the **MIT License** — free to use, modify, and distribute for any purpose. See [LICENSE](LICENSE) for details.