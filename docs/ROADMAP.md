# Roadmap

Planning notes carried over from the first planning conversation (2026-10-12). Update milestone status and decisions as work progresses.

## Goal

Practice the **AI engineering harness** end to end on a use case where the owner can judge every answer: a Spring Boot docs and migration assistant.

Why this use case: the owner is a Spring Boot expert (can spot hallucinations and write a trustworthy eval set), the docs are too large to paste into a prompt (real RAG is needed), multiple Boot versions contradict each other (forces metadata filtering), and the data is public (AsciiDoc in `spring-projects/spring-boot`, Apache 2.0; issues via the GitHub API).

## Milestones

| # | Milestone | Concepts | Status |
|---|---|---|---|
| M0 | Project setup: `pom.xml`, compose, Testcontainers, config properties, Anthropic SDK smoke call | Project skeleton | done |
| M1 | Hand-written agent loop with one tool (`searchDocs`) over one version of the Boot reference docs; ingestion (read → chunk → embed → store); answers with citations; every step traced to a table | Agent loop, tool use, ingestion, chunking, embeddings, grounding | done |
| M2 | Eval harness: ~30 golden questions with expected answers and sources, a runner, retrieval hit rate, LLM-as-judge score, cost per run | Evaluation, regression testing | todo |
| M3 | Context management (trim tool results, summarize long chats), budgets, retries/rate limits, prompt caching, conversation memory; **`askUser` tool (first human-in-the-loop)** | Context engineering, limits, caching, HITL pause/resume | todo |
| M4 | Retrieval quality: multiple Boot versions with version metadata and filtering, structure-aware chunking (keep code blocks whole), hybrid search (pgvector + Postgres full-text), reranking; more tools (`searchIssues`, `getReleaseNotes`, `compareVersions`); structured output | Metadata filtering, hybrid search, reranking, structured output | todo |
| M5 | Migration agent: migrate a small sample Boot 3 project to Boot 4 with `readFile`, `editFile` (**requires approval**), `runBuild` (Docker sandbox); fix-until-green loop | Side-effecting tools, HITL approvals, sandboxing, self-verification | todo |
| M6 | Compare the hand-written harness with Spring AI (`ChatClient`, advisors, `@Tool`) and the Claude Agent SDK | What frameworks give and hide | todo |

Before building any RAG, try once: put a small doc subset directly into the prompt with prompt caching, as a baseline to compare RAG against.

## Human-in-the-loop design

Two scenarios, sharing the same pause/resume machinery:

1. **`askUser(question)`** (M3): the agent pauses on an ambiguous question (e.g. no Boot version given) and asks the user.
2. **Edit approval** (M5): before `editFile` runs, the harness pauses and shows the exact diff. The human can approve, reject with a reason, or edit the change.

How it works:
- When Claude returns a `tool_use` for a tool that needs approval, persist the full message history and the pending tool call in Postgres and return `202 Accepted` with an `approvalId`. Don't hold the HTTP request open.
- Approval is a state machine: `PENDING → APPROVED → EXECUTED`, `PENDING → REJECTED`, `PENDING → EXPIRED`. Optimistic locking (`@Version`) so a double approve doesn't execute twice.
- Store a hash of the tool arguments at creation and re-check it right before execution.
- Every `tool_use` must get a matching `tool_result` in the next message. A rejection is sent back as a `tool_result` with `is_error: true` and the reason, so Claude can change its plan.
- The approver must be an authenticated user; the agent can never approve itself.

Planned API:

```
POST /api/agent/runs                      → 200 finished, or 202 waiting (approvalId)
GET  /api/agent/runs/{runId}              → status, step trace, pending approval with diff
POST /api/agent/approvals/{id}/approve    → resume the loop
POST /api/agent/approvals/{id}/reject     → body { reason }; resume with the rejection
GET  /api/agent/runs/{runId}/events       → SSE stream of steps
```

## Decisions

| Decision | Choice | Reason |
|---|---|---|
| Use case | Spring Boot docs + migration assistant | Owner can verify answers; covers every RAG and harness concern |
| Agent loop | Hand-written on the Anthropic Java SDK | The point is to learn the harness, so no framework hides it |
| Spring AI | Only embeddings + pgvector `VectorStore` | Those are not harness code |
| Embeddings | Local ONNX first, Voyage AI optional later | No extra API key to start |
| Vector store | pgvector on port 5433 | Owner knows Postgres; 5432 is used by another local project |
| First HITL | `askUser` in M3, edit approvals in M5 | Build pause/resume early on a simple case |
| Versions (verified 2026-10-12) | Spring Boot 4.1.1, Spring AI 2.0.1, Anthropic Java SDK 2.71.0 | Latest stable lines; Spring AI 2.0.1 is built against Boot 4.1.1. No milestones |
| Base package | `com.tw.docsagent` | Owner's choice |
| First docs subset (M1) | Boot 4 reference "Testing" and "Data" sections | Big enough for real retrieval problems; owner can write strong golden questions on both |
| Approval UI | curl + checked-in `.http` files first; a small web page later (M5) if diffs need it | No new dependency |
| Live Claude tests | Tagged `live`, run only with `./mvnw verify -Plive` | An exported API key never costs money by accident |
| Vector store schema | Flyway creates `vector_store` (`initialize-schema=false`) | "Flyway owns the schema" |
| Persistence (M1) | `JdbcClient` + records, JSONB for message history; optimistic locking as explicit `UPDATE … WHERE version = ?` | SQL stays visible; no extra dependency |
| Refusals (M1) | No server-side fallback; a refusal ends the run with status `REFUSED` and its category in the trace | Keeps the gateway on the stable API; revisit if evals show false refusals |
| Doc source (M1) | Raw `.adoc` pages and `include-code` Java examples fetched from `spring-projects/spring-boot` at tag `v4.1.1` at ingestion time; tests use small fixtures | Nothing third-party vendored; `./mvnw verify` stays offline |
| Citations (M1) | Tool results label chunks `S1…Sn`; the model cites `[Sn]`; the harness maps markers to doc URLs and flags unknown markers as invented citations | Grounding becomes measurable (M2 metric) |
| Chunking (M1) | Split at `[[anchor]]` headings, ~200 words per chunk with a breadcrumb prefix, `include-code` examples inlined | all-MiniLM-L6-v2 embeds only the first ~256 tokens |
| Run statuses (M1) | `COMPLETED`, `MAX_TURNS_REACHED`, `BUDGET_EXCEEDED`, `TIMED_OUT`, `TRUNCATED`, `REFUSED`, `FAILED`; limits checked before every model call | A run never ends silently |
| Agent request (M1) | `claude-sonnet-5-5`, effort `medium`, `max_tokens` 10000 (owner changed it from 16000 on 2026-10-19), tools `strict: true`, system prompt in `prompts/agent-system.txt` | M2 measures whether `high` effort pays off |
| Pricing (M1) | USD per MTok in `docsagent.pricing.models` (checked 2026-10-12); startup fails if a configured model has no price | Cost per run without hard-coded prices |
| No new starters (M1) | `RestClient.builder()` instead of `spring-boot-starter-restclient`; MockMvc built from the context instead of `spring-boot-starter-webmvc-test` | Boot 4 split these into modules; plain spring-web/spring-test are enough |

## Progress log

Newest first. Lets a new session pick up where the last one stopped.

- **2026-10-19, M1 live results (runs by the owner on 2026-10-13, both live ITs passed):**
  - `InPromptBaselineIT` (10 pages pasted into a cached prompt): first call 71,427 cache-write tokens, 983 output, **$0.188**; second call reads the cache, **$0.027**.
  - `AgentLiveIT` (RAG through `searchDocs`): `COMPLETED`, 2 turns, 6,006 tokens, **$0.018**, 2 citations, 0 invented.
  - So for one question, RAG used ~12× fewer tokens than the baseline and cost less than even a cache-hit baseline call. Answer quality is compared in M2.
  - 3 manual runs against compose Postgres: all `COMPLETED`, 0 invented citations, $0.013–0.019 each.
  - Commit `5043712` ("Implement the phase M2") holds the docs, diagrams and CLAUDE.md, not M2 code. It also changed `agent-max-tokens` 16000 → 10000 (now recorded in Decisions) and the sample question in `http/agent.http`.
  - `./mvnw verify` green on 2026-10-19. **M1 done.** Next: push `feature/m1-agent-loop`, open the PR, start M2 on `feature/m2-evals`.

- **2026-10-13:** M1 architecture diagram added at `docs/diagrams/architecture-m1.html` (diagram-design skill). Redraw it when a milestone changes the topology.
  - Ingestion explained in `docs/ingestion.md` (+ `docs/diagrams/ingestion-pipeline.html`). Real ingestion against compose Postgres: 161 chunks.
  - Finding: the ONNX tokenizer truncates at **128 tokens**, not the ~256 the Chunker assumes. 133 of 161 chunks are longer; on average ~54% of a chunk shapes its vector. See Open questions.
  - `docs/ingestion.md` gained a beginner theory section (RAG, embeddings, cosine distance, why a second model, where 384 comes from, choosing a model) with measured examples.

- **2026-10-12, M1 (branch `feature/m1-agent-loop`, local commits, not pushed):**
  - Done: ingestion (`retrieval/`), tools (`tool/`), agent loop (`agent/`), trace tables V2 + `JdbcRunRecorder` (`trace/`), REST API (`api/`), `http/agent.http`. `./mvnw verify` green: 34 unit tests, 7 ITs.
  - Real docs check (network only): 10 pages → 119 sections → 161 chunks, all 93 `include-code` examples resolved.
  - Pending, needs a Console API key (owner runs these; Claude Code doesn't use the key):
    - `ANTHROPIC_API_KEY=… ./mvnw verify -Plive -Dit.test=InPromptBaselineIT`: step 0 baseline, ~$0.12. Record its numbers here.
    - `ANTHROPIC_API_KEY=… ./mvnw verify -Plive -Dit.test=AgentLiveIT`: real question, must cite `testing.testcontainers.service-connections`. A few cents.
  - Then: mark M1 done, push the branch, open a PR.
  - Known limits, by design for M1: requests are synchronous, the timeout is checked only between steps, a single call can overshoot the budget, and code blocks can be split across chunks (M4).

## Open questions

- **Embedding window (found 2026-10-13):** `all-MiniLM-L6-v2`'s bundled `tokenizer.json` truncates at 128 tokens, so most chunks are only partly embedded (`docs/ingestion.md`, Step 5). Options: raise the tokenizer max length via `TransformersEmbeddingModel` tokenizer options (the model was trained on 256), size chunks by tokens, or move to Voyage AI. Decide with the M2 retrieval hit rate, not one question.
- **Model download on every start (found 2026-10-13):** Spring AI caches the 90 MB ONNX model in a new random temp dir per start (17 copies so far). A fixed cache directory via Spring AI's transformers embedding cache property would make restarts and ITs faster. Needs a config change; owner to approve.
