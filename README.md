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

- **Multi-repo support** — expose multiple GitHub repositories from a single container, each at its own MCP endpoint, or combine several repos under one shared endpoint
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
        ├── POST /notes/mcp ──────────────────────────────────┐
        │                                                      │
        └── POST /code/mcp ───────────────────────────────┐   │
                                                           ▼   ▼
                                          ┌──────────────────────────────────────┐
                                          │         KnowledgeGithubMCP           │
                                          │                                      │
                                          │  Setup "notes" (/notes/mcp)          │
                                          │    VaultBrowserTool ─┐               │
                                          │    VaultSearchTool  ─┼─► repo-1      │
                                          │    VaultGraphTool   ─┤   repo-2      │
                                          │                      └──────────┐    │
                                          │  Setup "code" (/code/mcp)       │    │
                                          │    VaultBrowserTool ─┐          │    │
                                          │    VaultSearchTool  ─┼─► repo-3 │    │
                                          │    VaultGraphTool   ─┘          │    │
                                          └─────────────────────────────────│────┘
                                                                             │
                                                                             ▼
                                                                   GitHub REST API
```

**Components:**

| Component                | Role                                                                                                 |
|--------------------------|------------------------------------------------------------------------------------------------------|
| `VaultPropertiesFactory` | Parses `REPOS__*` and `SETUP__*` environment variables into named repo and setup definitions         |
| `McpToolsConfig`         | Dynamically creates one MCP server per setup, each with its own endpoint and isolated tool instances |
| `VaultServiceFactory`    | Creates a `VaultService` instance per repository (handles auth, root path)                           |
| `VaultBrowserTool`       | File tree, note content, and metadata                                                                |
| `VaultSearchTool`        | Full-text search and tag filtering                                                                   |
| `VaultGraphTool`         | Wiki-link graph traversal and recency queries                                                        |
| `VaultService`           | Single point of contact for GitHub API calls for one repository                                      |

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

---

## Installation

### Prerequisites

- Docker and Docker Compose
- A GitHub repository containing your notes or documentation
- A read-only GitHub PAT (only required for **private** repositories)

### 1. Get the compose file

```bash
curl -O https://raw.githubusercontent.com/soynx/KnowledgeGithubMCP/main/docker-compose.yml.sample
cp docker-compose.yml.sample docker-compose.yml
```

> No need to clone the repository — the Docker image is published on [GitHub Container Registry](https://ghcr.io/soynx/knowledge-github-mcp).

### 2. Configure

Edit `docker-compose.yml`. Two modes are supported:

#### Simple mode — one repo, one endpoint

```yaml
environment:
  - GITHUB_TOKEN=           # Leave empty for public repos; add PAT for private
  - GITHUB_REPOSITORY=youruser/your-repo
  - VAULT_ROOT=             # Optional: subfolder path if vault is not at repo root
  - LOG_LEVEL=WARN
```

Exposes your repo at `https://your-domain.com/mcp`.

#### Multi-repo mode — any number of repos and endpoints

```yaml
environment:
  # Define named repos
  - REPOS__VAULT__ORIGIN=youruser/obsidian-vault
  - REPOS__VAULT__TOKEN=ghp_xxxxxxxxxxxxxxxxxxxx
  - REPOS__VAULT__ROOT=

  - REPOS__DOCS__ORIGIN=youruser/team-docs
  - REPOS__DOCS__TOKEN=
  - REPOS__DOCS__ROOT=

  # Define named endpoints (each becomes a separate MCP connector in Claude)
  - SETUP__PERSONAL__PATH=/personal/mcp
  - SETUP__PERSONAL__CONTENT=vault

  - SETUP__WORK__PATH=/work/mcp
  - SETUP__WORK__CONTENT=vault,docs
```

- `REPOS__<KEY>__ORIGIN` — repository in `owner/repo` format (**required per repo**)
- `REPOS__<KEY>__TOKEN` — GitHub PAT; leave empty for public repos
- `REPOS__<KEY>__ROOT` — subfolder path within the repo; leave empty for repo root
- `SETUP__<KEY>__PATH` — the HTTP endpoint path for this MCP server
- `SETUP__<KEY>__CONTENT` — comma-separated list of repo keys to include

In the example above, `/work/mcp` serves tools that search and navigate **both** `vault` and `docs` together. `/personal/mcp` serves only `vault`.

Key naming: `REPOS__VAULT__` and `REPOS__MY_VAULT__` both result in key `vault` / `my-vault` (uppercase lowercased, `_` → `-`).

### 3. Run

```bash
docker compose up -d
```

The server starts on **port 8080**. For Claude to connect, it must be reachable over HTTPS — use a reverse proxy (e.g. Traefik, Caddy, or nginx) with a valid TLS certificate.

### 4. Connect to Claude

1. In Claude: **Settings → Connectors → Add custom connector**
2. Enter your server URL, e.g. `https://your-domain.com/personal/mcp`
3. Repeat for each setup you want as a separate connector
4. No OAuth required

> When updating the server, disconnect and reconnect the MCP connector in Claude settings to pick up new tools.

---

## Configuration Reference

### Simple mode (single repo)

| Variable            | Required | Default       | Description                                          |
|---------------------|----------|---------------|------------------------------------------------------|
| `GITHUB_TOKEN`      | No       | *(anonymous)* | Read-only GitHub PAT. Required for private repos.    |
| `GITHUB_REPOSITORY` | **Yes**  | —             | Repository in `owner/repo` format                    |
| `VAULT_ROOT`        | No       | *(repo root)* | Subfolder path if your vault is in a subdirectory    |
| `LOG_LEVEL`         | No       | `WARN`        | Log level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |

### Multi-repo mode

| Variable pattern        | Required | Description                                           |
|-------------------------|----------|-------------------------------------------------------|
| `REPOS__<KEY>__ORIGIN`  | **Yes**  | Repository in `owner/repo` format                     |
| `REPOS__<KEY>__TOKEN`   | No       | GitHub PAT; leave empty for public repos              |
| `REPOS__<KEY>__ROOT`    | No       | Subfolder within the repo (leave empty for repo root) |
| `SETUP__<KEY>__PATH`    | **Yes**  | HTTP endpoint path, e.g. `/notes/mcp`                 |
| `SETUP__<KEY>__CONTENT` | **Yes**  | Comma-separated repo keys to include in this setup    |
| `LOG_LEVEL`             | No       | `WARN`                                                | Log level: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR` |

#### Generating a read-only GitHub token (private repos only)

1. GitHub → Profile → Settings → Developer Settings → Personal access tokens → Fine-grained tokens
2. Generate new token → select your repository under "Repository access"
3. Permissions → Contents → **Read-only**
4. No other permissions needed

---

## Multi-Repo Path Handling

When a setup contains more than one repository, tool outputs prefix every file path with the repo key:

```
vault/Projects/MyNote.md
docs/Architecture/Overview.md
```

Pass these prefixed paths back to `getNoteContent`, `getNoteMetadata`, `getOutgoingLinks` etc. exactly as returned — the server routes them to the correct repository automatically.

`[[wiki-links]]` are resolved across all repos in the setup — a note in `vault` can link to a note in `docs` and `getOutgoingLinks` will show it as `[EXISTS]`.

---

## Available MCP Tools

| Tool                  | Description                                                                   |
|-----------------------|-------------------------------------------------------------------------------|
| `getVaultFileTree`    | Returns every file path in the vault as JSON — use this first for orientation |
| `listVaultContents`   | Lists files and folders at a specific path (includes file sizes)              |
| `getNoteContent`      | Fetches the full markdown content of a note by path                           |
| `getNoteMetadata`     | Returns file size, SHA, and direct GitHub URL for a note                      |
| `searchNotes`         | Multi-term OR search across note content and file paths                       |
| `searchByTag`         | Finds all notes containing specific Obsidian tags                             |
| `getOutgoingLinks`    | Extracts all `[[wiki-links]]` from a note and resolves which exist            |
| `getIncomingLinks`    | Finds all notes linking back to a given note (backlinks)                      |
| `getRecentlyModified` | Lists notes modified in the last N days, sorted by recency                    |

---

## Performance & Scale

- **File tree:** single GitHub API call per repository regardless of vault size
- **Search:** uses GitHub Code Search — fast, no local index required
- **Rate limits:** authenticated requests allow 5,000 API calls/hour; anonymous access is limited to 60/hour
- **Multi-repo:** each repository counts against its own token's rate limit independently
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
- [ ] Optional in-memory caching layer to reduce GitHub API calls
- [ ] GitLab support

---

## Contributing

Contributions are welcome. Open an issue to discuss what you'd like to change, or submit a pull request directly for small fixes.

Please keep PRs focused — one change per PR makes review faster.

---

## License

This project is licensed under the **MIT License** — free to use, modify, and distribute for any purpose. See [LICENSE](LICENSE) for details.
