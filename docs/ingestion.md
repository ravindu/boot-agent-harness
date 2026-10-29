# How ingestion works

Ingestion turns the Spring Boot reference docs into rows in pgvector that `searchDocs` can find by meaning. It runs once per docs version, before any agent run, and it never calls Claude.

```
GitHub (.adoc + .java) → AsciiDocParser → Chunker → IngestionService.toDocument → ONNX embedding → vector_store
        read                 parse          chunk          id + metadata             embed            store
```

Diagram: [`docs/diagrams/ingestion-pipeline.html`](diagrams/ingestion-pipeline.html). Code: `com.tw.docsagent.retrieval`.

Trigger it with the app running:

```bash
curl -s -X POST localhost:8080/api/ingestion/runs | jq
# {"bootVersion":"4.1.1","pages":10,"sections":119,"chunks":161,"resolvedExamples":93,"missingExamples":[],"durationMs":...}
```

The request is synchronous: `IngestionController` calls `IngestionService.ingestAll()` and returns when every page is stored (about a minute).

---

## Why ingestion exists: the theory

This section is for readers new to AI engineering. It answers four questions: why we need ingestion at all, why there is a second model next to Claude, where the number 384 comes from, and how an embedding model is chosen. Numbers marked *measured* come from this project's database; everything else is general background.

### 1. The problem: Claude doesn't reliably know these docs

Claude learned from a huge amount of text up to a training cutoff. It knows a lot about Spring Boot in general, but:

- **Versions blur together.** Boot 2, 3 and 4 behave differently, and the model can mix them up.
- **It can sound sure and still be wrong** (a *hallucination*): a property name that doesn't exist, an annotation from the wrong version.
- **It can't cite.** We want every answer to point at the exact doc section it came from, so a human can check it.

So we hand Claude the relevant documentation *with the question* and tell it to answer only from that text, citing it. That's called **grounding**. The question is *which* documentation to hand it.

**Option A: paste everything into the prompt.** It works for a few pages, and the `InPromptBaselineIT` test does exactly that as a baseline. It doesn't scale:

- **The context window is finite.** The prompt has a maximum size, and the full reference guide plus several Boot versions plus GitHub issues won't fit.
- **You pay for every token on every question.** Pasting 50,000 tokens of docs to answer one question costs 50,000 input tokens each time. Prompt caching (M3) reduces this but doesn't remove it.
- **More text means less focus.** Models answer better from 5 relevant passages than from 500 passages where the 5 are buried.

**Option B: retrieval-augmented generation (RAG).** Think of an open-book exam with a very good index. You don't read the whole book for each question; you look up the 5 most relevant passages and answer from those. RAG has two halves:

| Half | When | What | Here |
|---|---|---|---|
| **Ingestion** (offline) | once per docs version | split the docs into small pieces, compute a "meaning fingerprint" for each, store both | this document, `POST /api/ingestion/runs` |
| **Retrieval** (online) | on every question | compute the fingerprint of the question, find the pieces with the closest fingerprints, give them to Claude | the `searchDocs` tool |

### 2. Searching by meaning, not by words

A normal search (SQL `LIKE`, Google-style keywords) matches *words*. Users rarely use the docs' words. Someone asks *"How do I point my tests at a database running in Docker?"*; the answer is in the section about **Testcontainers** and **`@ServiceConnection`**, and none of those words is in the question.

*Measured.* That exact question, embedded and compared with all 161 stored chunks:

| Rank | Closest chunk (section anchor) | Distance (lower = closer) |
|---:|---|---:|
| 1 | `testing.testcontainers` | 0.304 |
| 2 | `testing.testcontainers.spring-beans` | 0.453 |
| 3 | `testing.testcontainers.junit-extension` | 0.527 |

The Testcontainers sections come first even though the question never says "Testcontainers". For comparison, *"A good recipe for pasta with tomato sauce"* has a closest distance of 0.855 to anything in the store, so nothing in it is related. A keyword search for the question's words finds 0 chunks.

The thing that makes this possible is called an **embedding**.

### 3. What an embedding is

An **embedding** is a list of numbers that represents the meaning of a piece of text. The list always has the same length for a given model; ours has 384 numbers.

**An analogy: coordinates.** GPS coordinates put every place on Earth at a point given by 2 numbers, so "how close are Colombo and Kandy?" becomes simple arithmetic. An embedding model does the same for meaning. It puts every text at a point in a "meaning space", so "how related are these two texts?" also becomes arithmetic. Texts about similar things land near each other.

**A toy example** (made-up numbers, 3 dimensions so you can picture it):

| Text | Vector |
|---|---|
| "Run a database in Docker for tests" | `[0.9, 0.8, 0.1]` |
| "Testcontainers starts a Postgres container" | `[0.8, 0.9, 0.2]` |
| "Pasta with tomato sauce" | `[0.1, 0.0, 0.9]` |

The first two point in almost the same direction; the third points somewhere else. Real models do this with hundreds of dimensions. That gives room for many shades of meaning at once: topic, "is it about testing", "is it about configuration", and countless patterns no human named.

**A real one** (*measured*, the first numbers of the stored vector for the Service Connections chunk):

```
[-0.100880235, -0.07157568, -0.16122343, -0.032721948, 0.03939178, 0.031153847, ...]   (384 numbers)
```

No single number means anything you could name, like "dimension 7 = databases". Meaning is spread across all 384 together. Only *comparisons between vectors* are meaningful.

**Where the numbers come from.** Nobody writes these rules by hand. The model is a neural network that was *trained*. For `all-MiniLM-L6-v2`, its authors fed it more than a billion pairs of related texts (a question and its answer, a title and its article, two paraphrases). The training repeatedly nudged the network so related pairs got vectors that are close, and random unrelated texts got vectors that are far apart. This is called **contrastive learning**. After training, the network places texts it has never seen (like our Spring docs) sensibly in the space, because it learned general patterns of language.

### 4. Measuring "close": cosine similarity

Picture each vector as an arrow from the origin. Two texts are related if their arrows point in a similar **direction**. The arrows' lengths don't matter. The standard measure is the cosine of the angle between the arrows:

```
cosine_similarity(a, b) = (a · b) / (|a| × |b|)        1 = same direction, 0 = unrelated, −1 = opposite
cosine_distance        = 1 − cosine_similarity         0 = same direction (what pgvector's <=> returns)
```

`a · b` (the dot product) multiplies the two lists number by number and adds the results; `|a|` is the length of arrow `a`. *Measured* distances from this store:

| Pair | Cosine distance |
|---|---:|
| Service Connections chunk 0 vs itself | 0.000 |
| Service Connections chunk 0 vs chunk 1 (same section) | 0.246 |
| Service Connections chunk 0 vs JUnit extension section | 0.344 |
| "How do I point my tests at a database running in Docker?" vs best chunk | 0.304 |
| "A good recipe for pasta with tomato sauce" vs best chunk | 0.855 |

That's the whole retrieval idea: embed the question, then return the chunks with the smallest distance. That's why `vector_store` is configured with `distance-type: cosine_distance` and an index built for cosine (`vector_cosine_ops`).

### 5. Why do we need another model? Isn't Claude enough?

Claude and the embedding model do different jobs:

| | Claude (`claude-sonnet-5-5`) | Embedding model (`all-MiniLM-L6-v2`) |
|---|---|---|
| Kind | **generative** model (a decoder) | **encoder** model |
| Input → output | text → new text, token by token | text → one fixed-length vector |
| Job here | reason, decide to call `searchDocs`, write the cited answer | turn chunks and questions into comparable vectors |
| Size | very large, runs on Anthropic's servers | ~23 million parameters, a 90 MB file, runs on your laptop's CPU |
| Cost | paid per token | free (local) |
| Calls per ingestion | 0 | 161 (one per chunk) |
| Calls per question | a few (each loop turn) | 1 per `searchDocs` call |

Reasons for a separate model:

1. **Claude's API doesn't return embeddings.** The Messages API only produces text. Anthropic doesn't offer an embedding model and points to Voyage AI for that.
2. **A different job needs a different design.** A generative model is trained to predict the next word. An embedding model is trained (contrastively, see above) so that *distances between its outputs* mean something. You could ask Claude to "rate how related these two texts are", but you'd need one paid call per pair, and you can't index the results.
3. **Scale and cost.** Search compares the question with *every* stored chunk. With vectors, that's cheap arithmetic that pgvector does in milliseconds, and the HNSW index avoids even looking at most rows. Embedding is one small, fast, free model call.

One rule follows from this: **documents and questions must be embedded by the same model.** Each model builds its own meaning space. A vector from model A compared with a vector from model B is meaningless, even when both have 384 numbers. Changing the model means re-ingesting everything.

### 6. Where does 384 come from?

**We didn't choose 384. The model did.** It's a fixed property of the model's architecture.

A transformer (the network design behind both Claude and MiniLM) represents every token internally as a vector of a fixed size, called the **hidden size**. `all-MiniLM-L6-v2` was built from a base model named `MiniLM-L6-H384`, and the name says it: **L6** = 6 layers, **H384** = hidden size 384. Inside the model:

```
"Testcontainers > Service Connections ..."
   │ tokenizer (WordPiece)
   ▼
[CLS] test ##con ##tain ##ers > service connections ...     up to 128 tokens
   │ 6 transformer layers
   ▼
one 384-number vector per token                              (last_hidden_state)
   │ mean pooling: average the token vectors
   ▼
one 384-number vector for the whole chunk                    → stored in vector_store.embedding
```

Other models have other sizes. 768, 1024, 1536 and 3072 are common, and some models let you pick a smaller output size. The trade-off:

| More dimensions | Fewer dimensions |
|---|---|
| room for finer distinctions, often better retrieval | smaller, faster, cheaper |
| more storage: 3072 floats × 4 bytes = 12 KB per chunk | 384 floats × 4 bytes = 1.5 KB per chunk |
| slower comparisons and bigger indexes | fine for 161 chunks or even 100,000 |

Since the model fixes the size, three places in this project must agree on it, or startup or insertion fails:

| Where | Value |
|---|---|
| model output | 384 (fixed by `all-MiniLM-L6-v2`) |
| `application.yaml` | `spring.ai.vectorstore.pgvector.dimensions: 384` |
| `V1__vector_store.sql` | `embedding vector(384)` |

Check it yourself: `select vector_dims(embedding) from vector_store limit 1;` returns `384`.

### 7. How do you choose an embedding model?

There's no single best model. You weigh these criteria:

| Criterion | Question to ask | `all-MiniLM-L6-v2` |
|---|---|---|
| **Retrieval quality** | Does it find the right passages? Public benchmarks (the MTEB leaderboard, "Retrieval" tab) give a first ranking, but only your own eval set gives the real answer | decent for English prose; well below modern models on benchmarks |
| **Input window** | How many tokens does it read per text? Longer means bigger chunks are fully represented | 128 tokens as configured here (trained on 256) — see [Step 5](#the-128-token-window-measured) |
| **Domain** | Trained on text like yours? Code, other languages? | general English sentences, not code |
| **Dimensions** | Storage and speed budget? | 384, small |
| **Where it runs** | Local (free, private, no key) or an API (better quality, costs money, data leaves your machine)? | local, in the JVM through ONNX Runtime |
| **Speed** | How long do ingestion and each query take? | fast on CPU |
| **Ecosystem** | Does your framework support it? | Spring AI's default local model: one starter, zero config |
| **License** | Can you use it commercially? | Apache 2.0 |

**Why we chose it for M1.** It needs no API key, it's free, it runs offline, it's Spring AI's built-in local option, and it's good enough to learn the full RAG pipeline end to end. The `Decisions` table in `docs/ROADMAP.md` says the same: "Local ONNX first, Voyage AI optional later".

**What we'll do about its weaknesses.** It's an older, small model with a short window, and it wasn't trained on code. The planned upgrade is Voyage AI, the provider Anthropic recommends, which has much longer input windows and models tuned for code. The decision follows the project rule, *changes are measured*: once the M2 eval suite exists, we run the same golden questions with each candidate and compare the retrieval hit rate.

### 8. The whole picture

```
OFFLINE (ingestion, this document)                          ONLINE (every question)

docs ─► split into chunks ─► embedding model ─► vectors     question ─► same embedding model ─► vector
                                                   │                                              │
                                                   ▼                                              ▼
                                            vector_store ◄──── nearest by cosine distance ────────┘
                                                                         │
                                                                         ▼
                                                    top 5 chunks (S1…S5) ─► Claude ─► answer citing [S1]
```

Ingestion is the left half. The rest of this document walks through it step by step.

---

## Configuration

Everything ingestion needs is in `application.yaml`:

```yaml
docsagent:
  docs:                                   # DocsSourceProperties
    raw-base-url: https://raw.githubusercontent.com/spring-projects/spring-boot
    tag: v4.1.1                           # docs are pinned to a git tag, so ingestion is reproducible
    boot-version: 4.1.1                   # stored on every chunk; searchDocs filters on it
    site-base-url: https://docs.spring.io/spring-boot/4.1/reference   # for citation URLs
    pages: [testing/index, testing/testcontainers, data/sql, ...]     # 10 pages in M1
  ingestion:
    max-words-per-chunk: 200              # IngestionProperties

spring.ai.vectorstore.pgvector:
  initialize-schema: false                # Flyway creates the table (V1__vector_store.sql)
  schema-validation: true                 # startup fails if the table doesn't match
  dimensions: 384                         # must equal the embedding model's output size
  index-type: hnsw
  distance-type: cosine_distance
```

`IngestionService.ingestAll()` loops over `pages` and runs steps 1–6 below **per page**.

---

## Step 1: Read (`GitHubDocsSource`)

`DocsSource` is a two-method interface, so tests can swap the network for fixtures (`FixtureDocsSource`, files under `src/test/resources/docs-fixture/`):

| Method | Fetches | Example URL |
|---|---|---|
| `readPage("testing/testcontainers")` | the AsciiDoc page | `…/spring-boot/v4.1.1/documentation/spring-boot-docs/src/docs/antora/modules/reference/pages/testing/testcontainers.adoc` |
| `readExample("testing/testcontainers/serviceconnections/MyIntegrationTests")` | a Java example referenced by the page | `…/v4.1.1/documentation/spring-boot-docs/src/main/java/org/springframework/boot/docs/testing/testcontainers/serviceconnections/MyIntegrationTests.java` |

Details worth knowing:

- Plain `RestClient.builder()`, no token: raw.githubusercontent.com is public. (Boot 4 auto-configures `RestClient.Builder` only with `spring-boot-starter-restclient`, which we don't use.)
- The path is built as a plain string. Passed as a URI template variable, every `/` would be encoded as `%2F` and GitHub would return 404.
- A missing example (HTTP 404) returns `Optional.empty()`; it is reported in `missingExamples` instead of failing the run. Any other HTTP error fails the run.

---

## Step 2: Parse (`AsciiDocParser`)

The parser turns one `.adoc` page into a list of `DocSection(page, anchor, title, breadcrumb, body)`. It is a small, line-by-line state machine that handles exactly the constructs the Boot reference uses, not a full AsciiDoc implementation. It is a pure function: the example lookup is passed in as a `Function`, so `AsciiDocParserTest` needs no network.

### Sections come from `[[anchor]]` + heading pairs

```asciidoc
[[testing.testcontainers.service-connections]]
== Service Connections
A service connection is a connection to any remote service.
```

- An `[[anchor]]` line is remembered; the next `=`/`==`/`===` heading starts a new section with that anchor.
- The anchor becomes the **citation target**: `https://docs.spring.io/spring-boot/4.1/reference/testing/testcontainers.html#testing.testcontainers.service-connections`.
- A heading without its own anchor keeps the enclosing anchor, so its citation URL still lands on the right part of the page.
- The **breadcrumb** is the stack of headings above the section, indexed by heading level (`=` is level 1). Starting a level-2 heading pops everything at level 2 and deeper. Example: `Testcontainers > Service Connections`.

### What each construct becomes

| In the `.adoc` | In `DocSection.body` | Why |
|---|---|---|
| `include-code::MyIntegrationTests[]` | the real Java file as a ```` ```java ```` fence, license header and `package` line removed | The model and the embedding need the code, not a directive. Header lines cost tokens and say nothing |
| `[source,java]` + `----` … `----` | ```` ```java ```` … ```` ``` ```` | Markdown fences are what Claude reads best; the Chunker uses them to keep code together |
| `` javadoc:org.springframework…ServiceConnection[format=annotation] `` | `` `@ServiceConnection` `` | What a reader sees on the docs site |
| `configprop:spring.datasource.url[]` | `` `spring.datasource.url` `` | Same |
| `xref:testing/index.adoc#testing.utilities[Test Utilities]` | `Test Utilities` | Link text only |
| `https://…[text]`, `{url-…}[text]` | `text` | Link text only |
| `[NOTE]` | `NOTE:` | Keeps the admonition's meaning |
| `====`, `****`, `// comment`, `ifdef::`, page attributes, `{attribute}` | dropped | Markup with no content |

**Where examples live.** The Boot build stores an `include-code` example in a package derived from the section anchor: dashes removed, dots become slashes. `testing.testcontainers.service-connections` + `MyIntegrationTests` → `testing/testcontainers/serviceconnections/MyIntegrationTests` (`AsciiDocParser.examplePath`).

Result for the M1 pages: 119 sections, all 93 `include-code` examples resolved.

---

## Step 3: Chunk (`Chunker`)

A section can be anywhere from one line to several screens long. The Chunker splits each section into **chunks** of at most `max-words-per-chunk` (200) words. Each chunk becomes one row and one vector.

### Why chunk at all

- **One vector per idea.** An embedding is one point in space. A whole page about ten topics becomes a blurry average that matches nothing well. Small, focused pieces give sharp matches.
- **The model has an input limit.** The embedding model ignores everything past its input window (see [Step 5](#the-128-token-window-measured)).
- **Cheaper, more precise context for Claude.** `searchDocs` returns the top 5 chunks, not 5 pages.

### Why a breadcrumb in front of every chunk

A chunk like *"Add the annotation to the container field"* means nothing on its own; on the page, the heading above gave it context. So every chunk starts with its breadcrumb (`Testcontainers > Service Connections`, then a blank line). The breadcrumb is part of the embedded text, so it helps retrieval, and Claude sees it in the tool result.

### The algorithm

```
chunk(section):
  if body is blank → no chunks
  header = breadcrumb + "\n\n"
  for each block in splitBlocks(body):          # 1. paragraphs; a ``` fence is one block
    for each piece in fitToLimit(block):        # 2. only blocks over the limit get split
      if current chunk is non-empty and current + piece > maxWords:
        emit header + current; start a new chunk # 3. greedy packing
      append piece to current chunk
  emit the last chunk
```

1. **`splitBlocks`**: splits the body at blank lines into paragraphs. It tracks ```` ``` ```` lines, so a code fence stays one block even if the code contains blank lines.
2. **`fitToLimit`**: a block within the limit is kept whole. A block over the limit (usually a long code example) is split by lines, packing lines until the limit. A single line longer than the limit is split every 200 words (`splitLongLine`).
3. **Greedy packing**: blocks are appended to the current chunk until the next one would push it over the limit. Then the chunk is emitted and a new one starts. So a chunk never cuts a paragraph, and never cuts a code fence that fits in one chunk.

Words are counted as whitespace-separated tokens (`countWords`). The breadcrumb header is not counted.

### A real chunk

`testing.testcontainers.service-connections` produced 4 chunks. Chunk 0, as stored in `vector_store.content` (1,871 characters):

````text
Testcontainers > Service Connections

A service connection is a connection to any remote service.
Spring Boot's auto-configuration can consume the details of a service connection and use them to establish a connection to a remote service.
When doing so, the connection details take precedence over any connection-related configuration properties.

When using Testcontainers, connection details can be automatically created for a service running in a container by annotating the container field in the test class.

```java
import org.junit.jupiter.api.Test;
...
@Testcontainers
@SpringBootTest
class MyIntegrationTests {

	@Container
	@ServiceConnection
	static Neo4jContainer neo4j = new Neo4jContainer("neo4j:5");
	...
}
```

Thanks to `@ServiceConnection`, the above configuration allows Neo4j-related beans ...
NOTE: You'll need to add the `spring-boot-testcontainers` module as a test dependency ...
````

Breadcrumb first, then whole paragraphs, the inlined example as one fence, and the resolved `` `@ServiceConnection` `` macro.

### Chunks per page (M1)

| Page | Sections | Chunks |
|---|---:|---:|
| testing/spring-boot-applications | 42 | 64 |
| data/nosql | 29 | 38 |
| data/sql | 29 | 33 |
| testing/testcontainers | 8 | 14 |
| testing/test-utilities | 5 | 6 |
| testing/test-modules | 1 | 2 |
| 4 index/short pages | 1 each | 1 each |
| **Total** | **119** | **161** |

Tests: `ChunkerTest` (breadcrumb prefix, word limit, fence kept together, oversized blocks, empty section).

---

## Step 4: Identify (`IngestionService.toDocument`)

Each chunk becomes a Spring AI `Document(id, text, metadata)`:

**Deterministic id.** `UUID.nameUUIDFromBytes("4.1.1|testing/testcontainers|testing.testcontainers.service-connections|0")`. That's a name-based (version 3) UUID: the same version + page + anchor + chunk index always gives the same id, `a72a0771-3564-3801-9796-69498e6b4ff6` for the chunk above. That's what makes re-ingestion safe (Step 6).

**Metadata** (stored as JSON next to the text):

| Key | Example | Used for |
|---|---|---|
| `bootVersion` | `4.1.1` | `searchDocs` filter; delete before re-ingest |
| `page` | `testing/testcontainers` | delete before re-ingest |
| `anchor` | `testing.testcontainers.service-connections` | citation target |
| `title` | `Service Connections` | shown to Claude |
| `breadcrumb` | `Testcontainers > Service Connections` | shown to Claude |
| `url` | `https://docs.spring.io/spring-boot/4.1/reference/testing/testcontainers.html#testing.testcontainers.service-connections` | citations returned by the API |
| `chunkIndex` | `0` | ordering; part of the id |

---

## Step 5: Embed (ONNX `all-MiniLM-L6-v2`, inside Spring AI)

There is no explicit "embed" call in our code. `vectorStore.add(documents)` (Spring AI's `PgVectorStore`) calls the auto-configured `EmbeddingModel` for the batch, then inserts. The model is `TransformersEmbeddingModel` from `spring-ai-starter-model-transformers`: `all-MiniLM-L6-v2` exported to ONNX and run **locally** in the JVM through ONNX Runtime. No API key, no network call per chunk, no cost.

What happens to one chunk:

1. **Tokenize.** A WordPiece tokenizer (`tokenizer.json`) splits the text into sub-word tokens. Prose is about 1.3 tokens per word. Code is much more: `Neo4jConnectionDetails` or `org.junit.jupiter.api.Test` each become many tokens.
2. **Run the model.** The 6-layer transformer produces one 384-number vector per token (`last_hidden_state`).
3. **Mean-pool.** The token vectors are averaged into **one 384-number vector** for the chunk (`meanPooling`).

At startup the model (`model.onnx`, 90 MB) and `tokenizer.json` are downloaded from the Spring AI GitHub repo into a cache directory. By default that directory is a new random temp folder (`spring-ai-model-cache<random>`), so *every* app or test start downloads the model again. *Measured* on 2026-10-13: 17 copies on this machine. That's most of the ~27 s startup. Setting a fixed cache directory would fix it (see Open questions in `docs/ROADMAP.md`).

The same model embeds the user's query at search time, which is why ingestion and search must always use the same model. A different model means a different vector space (and possibly a different dimension). Then all chunks must be re-ingested, and `vector(384)` in the schema changes.

### The 128-token window (measured)

`Chunker` and `IngestionProperties` assume a ~256-token window. Measured on 2026-10-13, the effective window is **128 tokens**: the bundled `tokenizer.json` sets `truncation.max_length = 128` (and padding to 128). Everything after token 128 has no effect on the vector:

| Input embedded | Tokens | Cosine similarity to the same text + an unrelated sentence appended |
|---|---:|---:|
| first 60 words of the chunk above | 79 | 0.98 (the appended text still counts) |
| first 90 words | 214 | **1.000000** (appended text ignored) |
| full chunk | 465 | **1.000000** |

Across all 161 stored chunks:

| | |
|---|---|
| Chunks longer than 128 tokens | **133 of 161** |
| Median / p90 / max chunk length | 292 / 547 / 1,003 tokens |
| Average share of a chunk that shapes its vector | **~54%** |

What this means:

- **Retrieval only "sees" the start of most chunks:** the breadcrumb and the first paragraph or so. In the example above, the vector is built from the breadcrumb, the first paragraphs and part of the import lines. The `@ServiceConnection` code and the explanation after it don't affect it at all.
- **Claude still gets the full text.** `searchDocs` returns `content`, not the vector, so answers are not truncated. Only the *matching* is affected.
- Possible fixes, to be compared with the M2 eval suite before choosing: raise the tokenizer's max length (`TransformersEmbeddingModel` accepts tokenizer options; MiniLM was trained on 256), size chunks by tokens instead of words, or move to a model with a longer window (Voyage AI, already the planned upgrade). Listed under Open questions in `docs/ROADMAP.md`.

---

## Step 6: Store (`PgVectorStore` → `vector_store`)

For each page, `IngestionService` does two calls:

```java
vectorStore.delete(f.and(f.eq("bootVersion", version), f.eq("page", page)).build());  // 1. remove this page's old chunks
vectorStore.add(documents);                                                          // 2. embed + insert the new ones
```

1. **Delete by metadata.** Spring AI turns the filter expression into SQL on the JSON column (`DELETE FROM vector_store WHERE <metadata filter>`). This removes chunks that no longer exist, for example when a section got shorter and now produces 3 chunks instead of 4.
2. **Add = embed + upsert.** After embedding, `PgVectorStore` batch-inserts with:
   ```sql
   INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?, ?, ?::jsonb, ?)
   ON CONFLICT (id) DO UPDATE SET content = ?, metadata = ?::jsonb, embedding = ?
   ```

The table, created by Flyway (`V1__vector_store.sql`) and checked at startup by `schema-validation: true`:

```sql
CREATE EXTENSION IF NOT EXISTS vector;          -- pgvector: the vector(n) type and distance operators
CREATE TABLE vector_store (
    id        uuid PRIMARY KEY,                 -- our deterministic id
    content   text,                             -- the chunk text (with breadcrumb)
    metadata  json,                             -- the 7 keys above
    embedding vector(384)                       -- the ONNX output
);
CREATE INDEX spring_ai_vector_index ON vector_store USING hnsw (embedding vector_cosine_ops);
```

- **Cosine distance** compares the *direction* of two vectors, not their length: 0 means same direction (same meaning), larger means less related. pgvector's operator is `<=>`.
- **HNSW** (Hierarchical Navigable Small World) is an approximate nearest-neighbour index: a layered graph that finds the closest vectors without comparing the query to every row. With 161 rows a full scan would be just as fast. The index matters at tens of thousands of chunks (M4: several Boot versions).

### Why re-running is safe

Run ingestion twice and you still have 161 rows. The page delete removes stale chunks, and the deterministic ids make the insert an update if a row survived. Each page is stored as soon as it's processed, so a failure on page 7 leaves pages 1–6 stored and fresh. Running again repairs the rest.

---

## How the stored chunks are used (query time)

`SearchDocsTool` (the `searchDocs` tool Claude calls) does the online half:

```java
vectorStore.similaritySearch(SearchRequest.builder()
        .query(query)                                                 // embedded with the same ONNX model
        .topK(topK)                                                   // default 5, clamped to 1..8
        .filterExpression(f.eq("bootVersion", "4.1.1").build())       // metadata filter
        .build());
```

That becomes roughly `SELECT …, embedding <=> :queryVector AS distance FROM vector_store WHERE <bootVersion filter> ORDER BY distance LIMIT 5`. The hits are labelled `S1…Sn` in the tool result. Their `url` metadata is what `[Sn]` citations in the answer resolve to.

---

## Inspect it yourself

```bash
p() { docker-compose exec postgres psql -U docsagent -d docsagent "$@"; }

p -c "select count(*) from vector_store;"                                         # 161
p -c "select metadata->>'page' page, count(*) from vector_store group by 1 order by 2 desc;"
p -c "select id, metadata->>'chunkIndex' idx, length(content) chars, vector_dims(embedding) dims
      from vector_store where metadata->>'anchor' = 'testing.testcontainers.service-connections';"
p -c "select content from vector_store where id = 'a72a0771-3564-3801-9796-69498e6b4ff6';"
p -c "select substr(embedding::text, 1, 80) from vector_store limit 1;"           # the first numbers of a vector

# Nearest neighbours of one chunk (cosine distance, 0 = identical direction)
p -c "select metadata->>'anchor' anchor, round((embedding <=> (select embedding from vector_store
      where id = 'a72a0771-3564-3801-9796-69498e6b4ff6'))::numeric, 3) distance
      from vector_store order by distance limit 5;"
```

## Tests

| Test | Covers |
|---|---|
| `AsciiDocParserTest` | sections, breadcrumbs, macros, `include-code` inlining, example path rule |
| `ChunkerTest` | word limit, breadcrumb prefix, fences kept together, oversized blocks |
| `IngestionIT` | full pipeline against Testcontainers pgvector with `FixtureDocsSource` (offline), including re-ingestion without duplicates |
| `VectorStoreIT` | `PgVectorStore` against the Flyway schema |

```bash
./mvnw test -Dtest='AsciiDocParserTest,ChunkerTest'
./mvnw verify -Dit.test=IngestionIT
```

## Known limits (M1)

- **128-token embedding window:** see [Step 5](#the-128-token-window-measured). This is the biggest retrieval issue today.
- **Words, not tokens:** the chunk limit counts words, while the model's limit is in tokens, and code tokenizes very densely.
- **Large code examples can be split across chunks** (by lines). Structure-aware chunking that never cuts code is M4.
- **One version, ten pages.** Multiple Boot versions with version filtering, hybrid search (pgvector + Postgres full text) and reranking are M4.
- **Synchronous endpoint.** Fine for ten pages; the full reference guide would need a background job.
