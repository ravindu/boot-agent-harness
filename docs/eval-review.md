# Eval process review (M2)

Reviewed 2026-10-20 on `feature/m2-evals` @ `69ec314`. Scope: `src/test/java/com/tw/docsagent/eval/`, `src/test/resources/evals/golden-set.yaml`, the three reports in `evals/reports/`, and the agent code they exercise (`AgentLoop`, `SearchDocsTool`, `CitationChecker`).

## Summary

The eval harness is well designed for a first version. Its weak spot is no longer the code. It's what the suite can tell you: **at 27/28 (96%) the suite is saturated.** It can't show whether a change improves accuracy, and the questions it would need to do that were removed during golden-set cleanup. Before using it to drive accuracy work, make the test set harder (and hold part of it out), repeat runs, and add a grounding check.

| # | Finding | Impact | Effort |
|---|---|---|---|
| 1 | The suite is saturated and too small to detect improvements | High | Medium |
| 2 | The golden set has been tuned against the agent's own outputs (no held-out set) | High | Low |
| 3 | Faithfulness (are the claims supported by the cited chunks?) is not measured | High | Medium |
| 4 | Decline questions are easy and graded leniently; the hard "in-domain but not in the docs" cases were removed | High | Low |
| 5 | The judge is only double-checked in one direction; it has no calibration set | Medium | Low |
| 6 | One run per question; no run-to-run diff | Medium | Low |
| 7 | Retrieval eval: replayed queries leak the answer, and hit@8 credits ranks Claude never saw | Medium | Low |
| 8 | Reports don't record what produced them (git SHA, prompt, golden set) | Low | Low |
| 9 | Compound and off-question key points | Low | Low |

## What's working well

- **Clear separation of truth, judgement and scoring.** The owner writes the truth (key points, must-not-say). Haiku only checks a checklist against an answer, never Spring knowledge. `EvalScorer` is pure code with unit tests. This is the right architecture for an LLM-as-judge eval.
- **The judge has to show evidence.** Every `present=true` key point must quote the answer, and code checks the quote (`AnswerJudge.verifyEvidence`). This is better than most production judges.
- **Preflight checks before spending money.** Unknown anchors and `expectedText` phrases that don't match exactly one chunk fail the run before any API call (`GoldenSetChecks`).
- **Retrieval measured on two levels and on its own.** Section hit vs passage hit, plus `RetrievalEvalIT`, which is deterministic and free. Finding that section-level hit rate was too coarse, and that agent runs are too noisy to measure retrieval, were the two most valuable lessons in M2.
- **Reports can be diagnosed on their own.** Search queries, chunk ids, run ids and answers are in the JSON, so a failure can be triaged without the throwaway Testcontainers DB.
- **The eval measures but doesn't gate.** `EvalRunIT` fails only when the run itself is broken. That's correct for an eval.

## Findings

### 1. The suite is saturated and too small to detect improvements

baseline-v2 shows 27/28 overall, 19/20 answers, 55/56 key points, passage hit 20/20. A suite that passes almost everything can't show improvement, and only regressions are visible.

There's also too little data to see a change in either direction:

- With 20 answer questions, one flip moves the score 5 points.
- The 95% confidence interval for 19/20 is roughly 75–99%.
- Between baseline and baseline-v2, three questions flipped **with no change to the agent** (Progress log). That noise is bigger than most real improvements you'll try, such as the 128-token tokenizer experiment or `effort: high` vs `medium`.

The roadmap says M2 should measure "whether `high` effort pays off". The current suite can't answer that question.

**Recommendation:** grow to ~50–60 answer questions and target a baseline pass rate of 60–75%, so there's room to move. Add the kinds of questions the agent currently doesn't face:

- **Multi-hop:** questions that need two sections. For example: "I use `@DataJpaTest` with a Testcontainers Postgres; how do I stop it replacing my DataSource *and* wire the container in?" needs `autoconfigured-spring-data-jpa` + `service-connections`.
- **Partially covered:** the docs answer half the question. The agent should answer that half and say the rest isn't covered.
- **No version given:** e.g. "How do I mock a bean in a test?", where Boot 3 (`@MockBean`) and Boot 4 (`@MockitoBean`) differ.
- **Boot 3 → 4 traps phrased the Boot 3 way:** e.g. "Where is `@WebMvcTest` in `spring-boot-test-autoconfigure`?" The agent should correct the premise.
- **Questions whose answer is a table row or a code example** deep in a long section. These are the chunking failures you already found.

### 2. The golden set has been tuned against the agent (no held-out set)

After the baseline, 4 key points were rewritten after looking at the failing answers, 1 judge false negative was fixed in the judge, and earlier 3 + 4 questions were dropped or replaced because "the docs don't cover them". Each change was justified on its own: the key points really did go beyond the docs. Together, though, they are the classic way an eval drifts toward "whatever the current system does". Part of the jump from 75% to 96% is the eval moving toward the agent, not the agent improving.

**Recommendation:**

- Split the set. Keep the current 28 as a **dev** set that you can look at and fix.
- Write a **test** set (~20–30 questions) without looking at agent answers, and run it rarely (e.g. once per milestone). Never edit it after seeing a result. Only fix it when the key point contradicts the docs, and record the fix in the report.
- In the report, list golden-set edits since the last run so a score change can be traced to the agent or to the eval.

### 3. Faithfulness is not measured

For RAG, accuracy has two parts:

- **Correctness:** does the answer contain the right facts? This is measured, by key points.
- **Faithfulness (grounding):** is every claim supported by the retrieved passages it cites? This is not measured.

`CitationChecker` only proves that a label like `[S3]` was handed out. It doesn't check that S3 says what the sentence claims. The judge never sees the retrieved chunks, so an answer that contains every key point *plus* an extra claim from Claude's memory, cited to an unrelated chunk, passes today. This is the main way a docs agent can go wrong, and it's the failure the system prompt explicitly forbids ("Don't fill gaps from memory").

**Recommendation:** add a second, cheap Haiku check per answer. It gets the answer and the text of every cited chunk (you already have `chunkTextById` in `EvalRunIT`) and, for each sentence, returns `supported` / `unsupported` / `no citation`. Report **citation precision** (cited sentences supported by their chunk) and **unsupported claims per answer**. Keep it a separate metric at first, and decide later whether it should fail a question.

### 4. Decline questions are easy and graded leniently

All 8 declines pass, but:

- **They're far from the corpus.** Spring Security JWT, Spring Cloud Config, Kubernetes and RabbitMQ DLQ have nothing in the Testing/Data pages, so declining is easy. The hard case for a docs agent is a question **in the domain, which the model knows well, but which the ingested docs don't cover**. The four questions you replaced (`@TestPropertySource`, `@Nested`, `@Sql` seeding, `@MockBean`) were exactly that. As `decline` questions they would test grounding far better than any current one. `@MockBean` is also a version trap: it was removed in Boot 4.
- **Memory leaks through in passing declines.** In baseline-v2:
  - `kafka-listener-out-of-scope` passed while the answer named `spring.kafka.listener.concurrency` and `ConcurrentKafkaListenerContainerFactory`.
  - `spring-cloud-config-out-of-scope` passed while naming `spring.config.import`.

  Neither is in the retrieved docs. These hints are harmless here, but they are answers from memory, and the key point "does not present an answer from memory" didn't catch them because the judge can't tell what came from the docs (see finding 3).
- **`prompt-injection-fake-source` partly answered.** It listed what the section covers, with citations. That's arguably good behaviour, but the key points don't say whether it's expected.

**Recommendation:** add back the four in-domain-but-uncovered questions as `decline`. Add `mustNotSay` items for the obvious memory answer (e.g. "`spring.kafka.listener.concurrency`"), so a leak is a checkable violation instead of a judgement call. Also add one **indirect injection** case: a fixture chunk that contains an instruction, to check that the `<doc>` wrapping in `SearchDocsTool` holds. The CLAUDE.md rule says retrieved content is untrusted, but every injection question so far comes from the user message.

### 5. The judge is only checked in one direction and isn't calibrated

`AnswerJudge.judge` asks for round 2 only when round 1 **fails** something, and `merge` passes an item only if **both** rounds pass it. So:

- Round 2 can never turn a fail into a pass. It can only flag the fail as `uncertain` or fail more items. The Progress log says the judge fix addressed a false negative. What actually fixed `data-jpa-test-boot4` was the evidence-quoting prompt, not the second round.
- A **pass** is never double-checked. At 96% pass rate, judge false positives (lenient passes) are the error that matters, and they're the unguarded one. The evidence quote proves the sentence is in the answer, not that it states the key point.
- `verifyEvidence` is applied to key points only, not to must-not-say items.

**Recommendation:** build a small **judge calibration set**. Take ~15 real answers from the reports and make mutated copies:

- delete the sentence with a key point,
- swap `DataRedisConnectionDetails` for `RedisConnectionDetails`,
- swap `spring-boot-data-jpa-test` for the Boot 3 package,
- add an uncited claim.

Hand-label what the judge should say, and run the judge on them (a no-agent IT, cents per run). Report judge precision and recall. Rerun it whenever you change `AnswerJudge.SYSTEM` or the judge model. This is "testing the tester", and it's the standard way to justify trusting an LLM judge.

### 6. One run per question; no run-to-run diff

The Progress log's lesson ("agent scores need repeated runs") hasn't been implemented yet.

**Recommendation:**

- Add `-Deval.repeat=N` (start with 3, about $1.75 for the current set). Report per question `passes/N`, plus two summary metrics:
  - **pass^N:** passed every time. This is the reliability you'd ship.
  - **mean pass rate:** the expected score.
- Treat a question that passes 1 or 2 times out of 3 as *flaky*, and list it separately. Flaky questions are where your accuracy work should go first.
- Add a **diff against the previous report**: flips by id, with old vs new search queries. You did this by hand for baseline-v2. Automating it makes the variance visible on every run.

### 7. Retrieval eval: query leakage and an over-generous hit@8

`RetrievalEvalIT` is the most reliable measurement you have. Two things inflate its "Claude's queries" row:

- **The queries contain the answer.** Claude searched for `spring.datasource.generate-unique-name`, `@AutoConfigureTestDatabase Replace.NONE`, `spring.kafka.listener.concurrency`, i.e. what it already knew. Retrieval "works" here because Sonnet already knows Boot well. For the topics where the agent matters most (new in Boot 4, which the model may not know), Claude can't write such queries. That's why question text MRR (0.49) is far lower than Claude's queries MRR (0.70). Treat **question text** as the honest retrieval number when you compare chunking or embedding changes.
- **hit@8 credits results Claude never saw.** The replay searches to depth 20, but most real calls used `topK` 5. `QuestionResult` stores the queries but not the `topK` Claude asked for. "Claude's queries hit@8 20/20" includes two rank-6 hits from 5-result searches.

  Store `topK` with each query and add **hit@shown** (was the chunk within the `topK` Claude requested?).
- **The replayed queries come from one run,** baseline-v2, which was a good run. Fine for comparing retrieval changes against each other, but don't read the absolute number as "what Claude gets".

### 8. Reports don't record what produced them

`EvalRunIT.config()` records model, effort, judge, docs and `maxWordsPerChunk`. To compare two reports with confidence, you also need:

- git SHA and a dirty flag,
- a hash of `agent-system.txt`, of `golden-set.yaml` and of `AnswerJudge.SYSTEM`,
- the embedding model and tokenizer max length (you're about to change it),
- `agent-max-tokens`.

Also, the label defaults to `run`. baseline-v2 was run without it and renamed afterwards. Failing fast without `-Deval.label` avoids that.

### 9. Compound and off-question key points

Some key points bundle several facts:

- `data-jpa-test-boot4` #2: scans entities, configures repositories, *and* an embedded DB.
- `rest-client-test-slice` #2: Jackson, GSON, Jsonb, `RestTemplateBuilder`, `RestClient.Builder`, `MockRestServiceServer`.

The judge then has to decide what "partially stated" means, which is where Haiku is least consistent. Split them into atomic points.

Other key points ask for things the question didn't ask:

- `mockito-bean-replacement`: "comes from Spring Framework".
- `test-rest-template-usage`: basic auth.

These penalise the concise answers the system prompt asks for ("short and direct"). Either move them to an optional `niceToHave` list that's reported but doesn't fail the question, or reword the question.

Still open from the Progress log: `service-connection-redis-boot4` asks for a "static RedisContainer field", but the docs only show `GenericContainer` as a `@Bean`. That's a golden-set error, so fix the key point.

## Suggested order

1. **Low effort:** make the reports reproducible (8), fail fast without a label, fix the Redis key point, store `topK` (7).
2. **Make the suite harder:** add the 4 in-domain decline questions with `mustNotSay`, 8–10 multi-hop, partially covered and version-trap questions, and write a held-out test set (1, 2, 4).
3. **Repeated runs and a diff against the previous report (6).** After this, a score change means something.
4. **Judge calibration set (5).**
5. **Faithfulness check (3).**
6. Only then: the 128-token embedding experiment and `effort: high`, measured with question-text retrieval MRR and pass^3.

## Glossary (concepts used above)

- **Saturation / ceiling effect:** when a benchmark passes almost everything, it can't show improvements.
- **Held-out set:** questions never used while tuning, so their score isn't biased toward the current system.
- **Faithfulness / groundedness:** every claim in the answer is supported by the retrieved context. Different from correctness: an answer can be correct but ungrounded (from memory) or grounded but incomplete.
- **Citation precision:** fraction of cited statements that their cited passage actually supports.
- **pass^k vs pass@k:** pass^k = succeeds on *all* k tries (reliability); pass@k = succeeds on *at least one* (capability).
- **Judge calibration:** measuring an LLM judge's precision and recall against human-labelled verdicts, often with deliberately broken answers.
- **MRR (mean reciprocal rank):** average of 1/rank of the first relevant result; rewards finding it near the top.
