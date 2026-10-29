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
| M2 | Eval harness: ~30 golden questions with expected answers and sources, a runner, retrieval hit rate, LLM-as-judge score, cost per run | Evaluation, regression testing | **in progress**: golden set (28) and runner done; baseline run pending |
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
| Golden set (M2) | `src/test/resources/evals/golden-set.yaml`, loaded by `eval.GoldenSet` (test scope) with SnakeYAML from spring-boot-starter; fields `id`, `category`, `expect` (answer/decline), `traits` (what a question tests, e.g. `multi-hop`, `boot3-habit`; required, `[]` for none; the report shows the pass rate per trait. `paraphrased`, `deep-detail` and `table-or-code` are computed from the chunks and checked by the ITs, the rest are hand-tagged with rules in `GoldenSet`. Replaced an easy/medium/hard `difficulty` on 2026-10-20 because those labels had no checkable definition), `question`, `expectedSources` (anchors), `keyPoints`, `mustNotSay`; strict validation in `GoldenSetTest` | YAML is easiest for the owner to write; no new dependency; a typo fails the build |
| Eval runner (M2) | `EvalRunIT` (tag `live`): fresh Testcontainers DB → ingest → fail fast on unknown anchors → one agent run per question → judge → `EvalScorer` → `evals/reports/<time>-<label>.md/.json`. Flags `-Deval.label=…`, `-Deval.only=id,id` | Same code path as production runs, every run traced; reports are committed so runs can be compared |
| Re-judge (M2) | `RejudgeIT` (tag `live`): `-Deval.rejudge=report.json[,report.json]` judges the stored answers again with the current judge and golden set, writes `<startedAt>-<label>-rejudged.md/.json`. The pass rules live in one place (`EvalScorer.judged`) | A judge fix or a key-point correction costs cents (Haiku only) instead of a new agent run (~$1.60) |
| Declines (M2) | Naming a property, attribute or API that isn't in the searched docs fails a decline, even with "I couldn't confirm it" (owner, 2026-10-22). Each decline has a `mustNotSay` naming the likely leak | Run1/run2 answers named `spring.flyway.locations`, `spring.kafka.listener.concurrency` etc. while declining; that is memory leaking into a grounded answer |
| Judge (M2) | `claude-haiku-5-5`, effort medium, structured outputs (`output_config.format` JSON schema; since 2026-10-20 each checklist is an object with one required property per item, `"1"`…`"N"`, because an array let Haiku skip items; evidence is a list of exact quotes, every one checked, for key points and must-not-say items alike; `[Sn]` markers are ignored when matching); grades only the owner's key points / must-not-say, never its own Spring knowledge; the answer is passed as untrusted data | Cheap, checkable verdicts; no forced `tool_choice` (rejected by 5.5 models) |
| Pass rules (M2) | answer: COMPLETED + no invented citations + all key points + no must-not-say. decline: same checklist, or REFUSED. Retrieval hit is reported separately and doesn't decide the pass | Separates "search failed" from "answer failed" |
| Retrieval eval (M2) | `RetrievalEvalIT`, opt-in with `-Deval.retrieval=true`: ingests the real docs, searches like `searchDocs` (top 20) with fixed queries (golden question text + Claude's queries replayed from an agent report), records the rank of the `expectedText` chunk → hit@5, hit@8, MRR. No API key, no model calls, deterministic (verified: identical ranks over two runs) | Agent runs vary their search queries, so retrieval changes must be measured without Claude in the loop |
| `AgentRunResult.retrieved` (M2) | Every chunk shown to Claude in the run, cited or not | Needed for the retrieval hit rate; also visible in the API response |
| No new starters (M1) | `RestClient.builder()` instead of `spring-boot-starter-restclient`; MockMvc built from the context instead of `spring-boot-starter-webmvc-test` | Boot 4 split these into modules; plain spring-web/spring-test are enough |

## Progress log

Newest first. Lets a new session pick up where the last one stopped.

- **2026-10-29, rejudged run2 reviewed, chunk-size experiment (retrieval only, $0):**
  - `evals/reports/2026-10-21T150512Z-golden-73-run2-rejudged.md` (owner's re-judge with the 2026-10-22 fixes): **61/73 overall, answers 54/61, declines 7/12**, key points 139/150, 0 invented citations, $0.04 judge.
    - Declines dropped 12/12 → 7/12 because of the new "names it while saying it could not confirm it" rule. The five failing declines (Kafka listener, Spring Security JWT, Config Client, RabbitMQ DLQ, Flyway) all name a property or class from memory. This is the intended behaviour under the rule, so it's a real failure, not a judge error.
    - Answers 55 → 54: `web-slice-testing` and `test-rest-template-usage` now pass (reworded key points). `transactional-random-port-no-rollback`, `webmvc-test-skips-configuration-beans` and `graphql-test-slice` now fail. The first two are passage misses where the answer only hedges the point. `graphql-test-slice` is the one uncertain verdict (the two judge rounds disagree on whether the dependency "provides" the tester).
    - **Still open:** the same re-judge for run1 (`…091819Z-golden-73-run1.json`). Not in `evals/reports/` yet. Owner's command, ~$0.04.
  - **Chunk size (200 vs 150 vs 120 words), reports `2026-10-29T021742Z-retrieval-128-recheck`, `…024443Z-chunk-150w`, `…024515Z-chunk-120w`:** no clear gain.
    - Question-text hit@5: 48 / 44 / 47. MRR: 0.565 / 0.571 / 0.632.
    - Claude's replayed queries hit@5: 53 / 50 / 53. MRR: 0.680 / 0.632 / 0.654.
    - Per question, 150 words loses more than it gains (7 vs 3 on question text). 120 words is a wash on hit@5 and better on MRR, but only on question text. **200 words stays.**
    - The derived-trait check (tags computed on 200-word chunks) is now skippable with `-Deval.checkDerivedTraits=false`, and the report records that. Default on.
    - Lesson: the remaining question-text misses are ranking problems (the right chunk is found at rank 6–20), not chunk-size problems. Candidates for M4: hybrid search and reranking.
  - **M2 status:** the runner, judge, retrieval eval and golden set are done. Left: the owner's run1 re-judge, and a decision on the declines rule (strict as written, or allow a named property when it's flagged as unverified).

- **2026-10-29, tokenizer-window experiment (retrieval only, $0):** `RetrievalEvalIT` now records `embeddingWindowTokens` in its report config (from `-Dspring.ai.embedding.transformer.tokenizer.options.maxLength`, default 128).
  - Same 61 answer questions, same docs, two runs: `evals/reports/2026-10-29T021742Z-retrieval-128-recheck.md` (128, the current default) and `evals/reports/2026-10-29T021857Z-tokenizer-256.md` (256).
  - Question-text hit@5 48 → 49, hit@8 51 → 50, MRR 0.57 → 0.56. Claude's replayed queries: hit@5 53 → 56, hit@8 59 → 59, MRR 0.68 → 0.66.
  - Per question: question-text hit@5 gained 3, lost 2; replayed queries hit@5 gained 6, lost 3. Some near misses got worse (`dynamic-property-source-testing` 4 → 9, `rest-client-test-slice` 1 → 9, `webclient-test-slice` 10 → 18).
  - **Result: no clear gain.** The 256 window is not adopted. The default stays 128. The open question is still about chunk size, not the tokenizer window.
  - Not measured yet: the agent score (a live run, owner's call) and the embedding cost of 256.
  - `./mvnw verify` green (no API key).
  - Next (owner): the re-judge of run1 and run2 with the 2026-10-22 fixes (command below, ~$0.07). Then decide whether to try token-sized chunks (≤256 tokens) as the next retrieval experiment.

- **2026-10-20, M2 started (branch `feature/m2-evals`, from `feature/m1-agent-loop`):**
  - `feature/m1-agent-loop` pushed. PR not opened yet: `gh` isn't logged in here, so the owner opens it.
  - Done: golden-set format with 3 example questions (paraphrased how-to, Boot 4 version-specific `@DataJpaTest`, out-of-scope Kafka trap), `GoldenQuestion`/`GoldenSet` loader, `GoldenSetTest` (6 tests, green).
  - 2026-10-20: owner added 24 questions. Review against the ingested docs: fixed indentation and one unquoted colon (both broke parsing), 6 non-existent anchors, and key points the Boot 4 docs contradict (`TestRestTemplate` needs `@AutoConfigureTestRestTemplate`, `DataRedisConnectionDetails`, `@JdbcTest` module). 4 questions the docs don't cover (`@TestPropertySource`, `@Nested`, `@Sql` seeding, `@MockBean`) were replaced by owner's choice with doc-backed ones (`RestTestClient`, `OutputCaptureExtension`, embedded DB per context, containers as beans).
  - Golden set now: **27 questions** (19 answer, 8 decline), every anchor verified to exist. Owner writes the last 3.
  - Owner added 4 more; 3 dropped at the owner's request because the ingested docs don't cover them (RestTemplateBuilder timeouts, cache config, cache testing). Kept `rest-client-test-slice` with Boot 4 key points. **28 questions** (20 answer, 8 decline).
  - 2026-10-21: **runner built** (`AnswerJudge`, `EvalScorer`, `EvalReport`, `EvalRunIT`; `AgentRunResult.retrieved` added). `./mvnw verify` green: 65 tests. Not yet run live.
  - **Baseline run (2026-10-20, `evals/reports/2026-10-20T032440Z-baseline.md`):** 21/28 passed (75%); answers 13/20, declines 8/8, retrieval hit 20/20 (section level), key points 48/57, 0 invented citations, $0.557 total, 2.1 turns, 7.3 s avg.
  - Triage of the 7 failures: **2 retrieval at chunk level** (`testcontainers-service-connection`: right section, but not the chunk with the `@ServiceConnection` example; `rest-test-client-boot4`: example split across chunks), **1 judge false negative** (`data-jpa-test-boot4`: the answer states the missed point), **4 golden-set key points** that go beyond the docs or the question (`spy-existing-bean`, `rest-docs-integration`, `test-rest-template-usage`, `embedded-database-per-context`).
  - Lesson: the section-level retrieval hit rate is too coarse (100% while two answers lacked the passage they needed).
  - **Eval fixes after the baseline (2026-10-20):**
    1. Golden set: 4 key points corrected against the doc text (`spy-existing-bean`, `rest-docs-integration`, `test-rest-template-usage`, `embedded-database-per-context`). The 2 retrieval failures and `data-jpa-test-boot4` unchanged on purpose.
    2. Judge: every "present" verdict must quote the answer (checked in code), a failing round 1 gets an independent round 2, an item passes only if both rounds pass it, disagreements are reported as uncertain.
    3. `expectedText` on all 20 answer questions (each phrase verified to occur in exactly one chunk) → new **passage hit rate**; the report also stores Claude's `searchDocs` queries and the retrieved chunk ids per question.
    - `./mvnw verify` green: 73 tests.
  - **baseline-v2 (2026-10-20, `evals/reports/2026-10-20T065749Z-baseline-v2.md`, run without the label flag, renamed afterwards):** 27/28 (96%); passage hit 20/20; 1 re-judged, 0 uncertain; $0.584, 2.2 turns, 9.0 s.
    - 5 flips explained by the eval fixes (4 key points + `data-jpa-test-boot4` now passing on quoted evidence).
    - 3 flips with **no change to the agent**: `testcontainers-service-connection` and `rest-test-client-boot4` FAIL→PASS (Claude searched differently, e.g. "Testcontainers @ServiceConnection database container integration tests", and chunk 0 made it into the results), `service-connection-redis-boot4` PASS→FAIL. That is run-to-run noise: Claude's search queries vary, and chunk 0 vs the table chunk is a near-tie (0.365 vs 0.347 for one query).
    - Lesson: one agent run per question can't show a retrieval improvement. Retrieval needs its own deterministic measurement (fixed queries, no Claude), and agent scores need repeated runs.
    - `service-connection-redis-boot4`: key point asks for a "static RedisContainer field"; the docs only show the Redis case as a `GenericContainer` `@Bean` (the agent said so). Open: loosen the key point (owner to decide).
  - **Retrieval eval built and run (2026-10-20, `evals/reports/2026-10-20T073453Z-retrieval-baseline.md`, $0):** question text hit@5 15/20, hit@8 16/20, MRR 0.49; Claude's replayed queries hit@5 18/20, hit@8 20/20, MRR 0.70. Same ranks on a second run. `./mvnw verify` green: 81 tests (RetrievalEvalIT skipped without the flag).
    - Near misses with Claude's queries: `testcontainers-service-connection` rank 6 and `data-jpa-test-boot4` rank 6 (found only when Claude asks for more than 5 results). Question text alone misses `testcontainers-service-connection` and `web-slice-testing` entirely (>20): Claude's query rewriting is doing a lot of work.
    - Next: the 128-token experiment, measured with this eval first (`-Deval.label=tokenizer-256`), then confirmed with an agent run. Then the first experiment: the 128-token embedding window (Open questions).
  - **Eval process review (2026-10-20, `docs/eval-review.md`):** the suite is saturated (96%) and too small and noisy to show improvements. It has no held-out set, no faithfulness check, easy declines, a judge that's only checked in one direction, and single runs. Suggested order: reproducible reports → harder questions + held-out set → repeated runs + diff → judge calibration → faithfulness → then the tokenizer/effort experiments. Nothing implemented yet; owner to decide.
  - **Harder golden set, drafts (2026-10-20):** **45 draft questions** written by Claude from the ingested chunk text, without looking at agent runs: 41 answer (multi-hop, Boot 3 premises, partly covered, details deep in a section) and 4 declines on topics the docs lack (`@TestPropertySource`, `@Nested`, `@Sql`, Flyway).
    - Each question has **traits** instead of a difficulty (owner asked what easy/medium/hard was measured against; the answer was "nothing checkable"). New `DerivedTraits` (+ `DerivedTraitsTest`) computes `paraphrased`, `deep-detail`, `table-or-code` from the chunks; `GoldenSetChecks.assertDerivedTraitsMatchTheDocs` runs it in both ITs before any API call. `GoldenSet` enforces the hand-tagged rules (`multi-hop` ⇔ 2+ expectedText phrases; `boot3-habit` and `docs-gap` need a `mustNotSay`). `EvalReport` has a "By trait" table and traits in failure headings. Counts over 73: deep-detail 32, paraphrased 17, none 14, distractor 9, table-or-code 7, boot3-habit 7, multi-hop 5, docs-gap 4, false-premise 3, partial-coverage 2.
    - Measured difficulty (pass rate per question over 3 runs) comes from the next baseline; freeze it then, don't recompute per run. Now **73 questions** (61 answer, 12 decline). They sit in a section marked DRAFT at the end of `golden-set.yaml`; 2 have `OWNER CHECK` comments (`data-jpa-test-with-testcontainers`: does Boot 4 still need `Replace.NONE` with `@ServiceConnection`?; `test-rest-template-timeouts`: the docs do cover it).
    - `./mvnw verify` green: 91 tests. Retrieval eval with fresh ingestion (`evals/reports/2026-10-20T090305Z-retrieval-golden-73.md`, $0): all phrases unique, derived traits match; old 20 unchanged; question text hit@5 48/61, MRR 0.57. New questions outside the top 5: `multiple-slices-one-test`, `service-connection-subset-types`, `webclient-test-slice`, `jpa-deferred-bootstrap`, `redis-template-override`, `transactional-random-port-no-rollback`, `mockito-bean-reset-partial`, `data-jpa-test-with-testcontainers` (>20).
    - Next (owner): review the drafts (keep/fix/delete). Then decide whether they are the held-out set (don't run them while tuning) or join the dev set. Then the first agent run on the full set (~$1.50) as the new baseline.
  - **golden-73-run1 (2026-10-20, `evals/reports/2026-10-20T091819Z-golden-73-run1.md`, run by the owner before the drafts were reviewed):** 59/73 (81%), answers 47/61, declines 12/12, passage hit 56/61, 0 invented citations, $1.64.
    - Triage of the 14 failures: **8 judge crashes** (Haiku returned fewer checklist entries than asked, e.g. `keyPoints indices [1], expected [1, 2, 3]`), **4 judge false negatives or golden-set issues** (`data-jpa-test-boot4`, `spy-existing-bean`, `graphql-test-slice` compound key point, `service-connection-redis-boot4`), **4 retrieval misses** (`redis-template-override`, `service-connection-subset-types`, `jpa-deferred-bootstrap`, `embedded-database-per-context`; two overlap with judge crashes). The agent passed every false-premise, partial-coverage, docs-gap and distractor question. Estimated real answer accuracy ~56/61.
    - **Fix:** numbered-object judge schema (see Decisions) and `RejudgeIT`. `./mvnw verify` green: 98 tests. Run1's JSON reads back (checked).
    - Next (owner): `./mvnw verify -Plive -Dit.test=RejudgeIT -Deval.rejudge=evals/reports/2026-10-20T091819Z-golden-73-run1.json` (add run2's JSON with a comma when it exists), then compare run1 and run2 question by question for the noise level.
  - **run1 re-judged + run2 (2026-10-21/22, `…091819Z-golden-73-run1-rejudged.md`, `…2026-10-21T150512Z-golden-73-run2.md`):** re-judging run1 removed all 8 judge crashes: answers 47/61 → **55/61**. Run2: answers **55/61**, key points 141/150, 0 invented citations, $1.65, the same as run1.
    - **10 of 73 questions changed result between the two runs.** 4 come from the agent: Claude's searches differ (only 22/73 identical query lists), so a chunk is found or not. 6 come from the eval: 3 key points asking for more than the docs say, 1 quote spanning sentences, 1 ambiguous `mustNotSay` (Flyway), 1 judge disagreement. Failing in both runs: `jpa-deferred-bootstrap`, `redis-template-override` (retrieval).
    - Agent behaviour confirmed: overall accuracy stable at ~90%, every answer failure is a retrieval miss, and with the chunk missing it says what it can't confirm instead of inventing. It does name properties from memory while declining (now a failure, see Decisions).
    - **Eval fixes (2026-10-22):** 3 key points reworded to the doc text (`web-slice-testing`, `test-rest-template-usage`, `service-connection-redis-boot4`, which closes the open item); 9 declines got "names X even while saying it could not confirm it" `mustNotSay` items; judge evidence is a list of quotes and ignores `[Sn]` markers (a quote without the marker was wrongly rejected); must-not-say violations need a real quote too. `./mvnw verify` green: 102 tests.
    - **128-token window, measured on the golden set:** of the 61 answer phrases, 30 end within the first 128 tokens of their chunk, 10 end at 129–256, 21 after 256. The 31 "invisible" ones retrieve worse (question-text hit@5 23/31, MRR 0.49 vs 25/30, 0.64) and cause 8 of the 11 passage misses in run1+run2. A 256 window reaches 10 of them; the other 21 need chunks sized to ≤256 tokens.
    - Next (owner): re-judge both runs with the fixes: `./mvnw verify -Plive -Dit.test=RejudgeIT -Deval.rejudge=evals/reports/2026-10-20T091819Z-golden-73-run1.json,evals/reports/2026-10-21T150512Z-golden-73-run2.json` (~$0.07). Then the tokenizer experiment (Open questions), measured with `RetrievalEvalIT` first.
  - Originally planned next (Claude): the runner (live IT: one agent run per question, retrieval hit rate from the `TOOL_CALL` steps, citation checks), the Haiku judge, and a results report.

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

- **Embedding window (found 2026-10-13):** `all-MiniLM-L6-v2`'s bundled `tokenizer.json` truncates at 128 tokens, so most chunks are only partly embedded (`docs/ingestion.md`, Step 5). Options: raise the tokenizer max length via `TransformersEmbeddingModel` tokenizer options (the model was trained on 256), size chunks by tokens, or move to Voyage AI. Decide with the M2 retrieval hit rate, not one question. **Update 2026-10-29:** raising the window to 256 tokens was measured (`evals/reports/2026-10-29T021857Z-tokenizer-256.md`) and gave no clear gain (MRR 0.57 → 0.56, gains and losses about equal). Not adopted. Chunk size was then tested too (next entry): 200 words stays.
- **Model download on every start (found 2026-10-13):** Spring AI caches the 90 MB ONNX model in a new random temp dir per start (17 copies so far). A fixed cache directory via Spring AI's transformers embedding cache property would make restarts and ITs faster. Needs a config change; owner to approve.
