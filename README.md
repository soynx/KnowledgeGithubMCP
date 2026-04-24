# KnowledgeGithubMCP — Turn Any GitHub Repository into an AI Knowledge Base

**Give your AI agent structured, searchable access to any GitHub-hosted knowledge vault — without RAG pipelines, embeddings, or vector databases.**

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-blue.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4-green.svg)](https://spring.io/projects/spring-boot)
[![MCP](https://img.shields.io/badge/MCP-Streamable%20HTTP-blueviolet.svg)](https://modelcontextprotocol.io)

---

## What It Does

KnowledgeGithubMCP is a self-hosted [Model Context Protocol](https://modelcontextprotocol.io) (MCP) server that exposes any GitHub repository as a structured, queryable knowledge base to LLMs like Claude.

It is designed for developers and knowledge workers who store notes, documentation, or research in GitHub repositories (such as Obsidian vaults) and want their AI agent to read, search, and navigate that content — with zero infrastructure beyond a single Docker container.

No vector databases. No embedding pipelines. No data leaves your infrastructure unless you choose it to.

> [!IMPORTANT]
> **Connection Warning in Claude can be safely ignored.**
> When first connecting to Claude, you may see a "Couldn't connect" warning. The server works regardless. This is a known display issue with the Streamable HTTP transport implementation — a fix is in progress.

---

## Key Features

- **Full vault navigation** — browse the entire file tree in one call, or drill into any folder
- **Full-text search** — multi-term OR search across note content and file paths simultaneously
- **Tag-based filtering** — find all notes containing specific Obsidian tags (`#todo`, `#project`, etc.)
- **Knowledge graph traversal** — follow `[[wiki-links]]` forward (outgoing) and backward (backlinks)
- **Recency awareness** — query notes modified in the last N days, sorted by most recent
- **Zero infrastructure** — one Docker container, no databases, no preprocessing
- **Public & private repos** — works anonymously for public repos; use a read-only PAT for private ones
- **Read-only by design** — no write operations exist anywhere in the codebase

---

## Why This Project Exists

Most approaches to giving LLMs access to a personal knowledge base require a full RAG pipeline: chunking documents, generating embeddings, running a vector database, and managing index freshness. For a personal notes vault or team documentation repository, this is massive overkill.

GitHub already provides a structured API for navigating, reading, and searching file content. KnowledgeGithubMCP wraps that API as an MCP server, giving AI agents direct, structured access to your knowledge — with the same freshness guarantee as your last `git push`.

The result: your AI agent can answer questions about your notes, find related content, trace links between ideas, and surface recently updated documents — with a setup time measured in minutes, not days.

---

## Architecture

```
Claude (MCP Client)
        │
        │  Streamable HTTP  POST /mcp
        ▼
┌──────────────────────────────────────┐
│         KnowledgeGithubMCP           │
│                                      │
│  VaultBrowserTool  ─┐                │
│  VaultSearchTool   ─┼─► VaultService │
│  VaultGraphTool    ─┘        │       │
└─────────────────────────────-│───────┘
                               │
                               ▼
                     GitHub REST API
                     (your repository)
```

**Components:**

| Component | Role |
|---|---|
| `McpToolsConfig` | Registers all tools with the MCP server using Streamable HTTP transport |
| `VaultBrowserTool` | File tree, note content, and metadata |
| `VaultSearchTool` | Full-text search and tag filtering |
| `VaultGraphTool` | Wiki-link graph traversal and recency queries |
| `VaultService` | Single point of contact for all GitHub API calls |

---

## Example Usage

Once connected to Claude, you can ask:

```
"What have I written about Kubernetes?"
"Show me all notes tagged #todo"
"What notes link back to Projects/MyProject.md?"
"Which files did I change in the last 7 days?"
"Give me the full file tree of my vault"
"Show me the content of Area/Work/Q2-Planning.md"
```

Claude will call the appropriate MCP tools and return a structured answer directly from your repository. No copy-pasting, no manual searching.

**Example tool call and response:**

```
Tool: getVaultFileTree
→ Returns the complete file tree of your vault in one call:

vault/
├── Area/
│   └── Work/
│       ├── Q2-Planning.md
│       └── Team-Retro.md
├── Projects/
│   └── KubernetesSetup.md
└── Daily/
    └── 2026-04-22.md

47 file(s) total
```

---

## Installation

### Prerequisites

- Docker and Docker Compose
- A GitHub repository containing your notes or documentation
- A read-only GitHub PAT (only required for **private** repositories)

### 1. Clone the repository

```bash
git clone https://github.com/soynx/KnowledgeGithubMCP.git
cd KnowledgeGithubMCP
cp docker-compose.yml.sample docker-compose.yml
```

### 2. Configure

Edit `docker-compose.yml` and fill in your values:

```yaml
environment:
  - GITHUB_TOKEN=           # Leave empty for public repos; add PAT for private
  - GITHUB_REPOSITORY=youruser/your-repo
  - VAULT_ROOT=             # Optional: subfolder path if vault is not at repo root
  - LOG_LEVEL=WARN          # TRACE | DEBUG | INFO | WARN | ERROR
```

### 3. Run

```bash
docker compose up -d
```

The server starts on **port 8080**. For Claude to connect, it must be reachable over HTTPS — use a reverse proxy (e.g. Traefik, Caddy, or nginx) with a valid TLS certificate.

### 4. Connect to Claude

1. In Claude: **Settings → Connectors → Add custom connector**
2. Enter your server URL: `https://your-domain.com/mcp`
3. No OAuth required

> When updating the server, disconnect and reconnect the MCP connector in Claude settings to pick up new tools.

---

## Configuration Reference

| Environment Variable | Required | Default | Description |
|---|---|---|---|
| `GITHUB_TOKEN` | No | *(anonymous)* | Read-only GitHub PAT. Required for private repos. Leave empty for public. |
| `GITHUB_REPOSITORY` | **Yes** | — | Repository in `username/repo-name` format |
| `VAULT_ROOT` | No | *(repo root)* | Subfolder path if your vault lives in a subdirectory |
| `LOG_LEVEL` | No | `WARN` | Root log level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |

#### Generating a read-only GitHub token (private repos only)

1. GitHub → Profile → Settings → Developer Settings → Personal access tokens → Fine-grained tokens
2. Generate new token → select your repository under "Repository access"
3. Permissions → Contents → **Read-only**
4. No other permissions needed

---

## Available MCP Tools

| Tool | Description |
|---|---|
| `getVaultFileTree` | Returns the complete vault file tree in one call — use this first for orientation |
| `listVaultContents` | Lists files and folders at a specific path (includes file sizes) |
| `getNoteContent` | Fetches the full markdown content of a note by path |
| `getNoteMetadata` | Returns file size, SHA, and direct GitHub URL for a note |
| `searchNotes` | Multi-term OR search across note content and file paths |
| `searchByTag` | Finds all notes containing specific Obsidian tags |
| `getOutgoingLinks` | Extracts all `[[wiki-links]]` from a note and resolves which exist |
| `getIncomingLinks` | Finds all notes linking back to a given note (backlinks) |
| `getRecentlyModified` | Lists notes modified in the last N days, sorted by recency |

---

## Performance & Scale

- **File tree:** single GitHub API call regardless of vault size
- **Search:** uses GitHub Code Search — fast, no local index required
- **Rate limits:** authenticated requests allow 5,000 API calls/hour; anonymous access is limited to 60/hour
- **Repository size:** works with any size repository; very large repos (10,000+ files) may see slightly slower tree fetches

---

## Limitations

- **GitHub API rate limits** apply — heavy automated usage on public/anonymous access (60 req/hour) may hit limits
- **Search is powered by GitHub Code Search**, which indexes commits within minutes; brand-new files may not appear immediately
- **No streaming responses** — all tool results are returned as complete strings
- **Private repositories** require a GitHub PAT with `Contents: Read-only` permission
- **Spring AI milestone dependency** (`1.1.0-M1-PLATFORM-2`) — stable release upgrade planned once available

---

## Roadmap

- [ ] Upgrade to Spring AI stable release when Streamable HTTP support lands in GA
- [ ] Configurable search result limits via environment variables
- [ ] Support for multiple repositories in a single server instance
- [ ] Optional in-memory caching layer to reduce GitHub API calls
- [ ] GitLab support

---

## Contributing

Contributions are welcome. Open an issue to discuss what you'd like to change, or submit a pull request directly for small fixes.

Please keep PRs focused — one change per PR makes review faster.

---

## License

This project is licensed under the **MIT License** — free to use, modify, and distribute for any purpose. See [LICENSE](LICENSE) for details.
