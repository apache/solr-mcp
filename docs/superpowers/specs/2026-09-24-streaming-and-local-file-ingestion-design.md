# Spec: streaming `index-url` and indexing local files

**Date:** 2026-09-24
**Status:** phase A (§3) implemented in PR #210 on 2026-09-24, with S5 changed to "no
Markdown limit" (see S5). Phase B (§4.2, local paths in `index-url` under STDIO) decided
2026-10-07, not yet implemented; phase C (SEP-2631) not started
**Builds on:** [`2026-09-15-url-ingestion-design.md`](2026-09-15-url-ingestion-design.md)
(`index-url`, PR #210, issue #208), whose §10 this spec replaces.

Two questions, one answer each:

1. **Remote files** (public web, GitHub, S3 via presigned URL): how can `index-url`
   drop its 10 MB limit and keep a simple interface? *By streaming the download into
   Solr instead of holding it in the server's memory* (§3).
2. **Files on the user's machine**: how can they be indexed without passing through
   the model? *Under STDIO, by passing the file's path to `index-url`, which streams it
   like a download. Under HTTP the server cannot see the user's disk, so the file must
   be at a URL (GitHub, S3); MCP file upload (SEP-2631) can fill that gap later.
   Elicitation was considered and is not the route* (§4).

---

## 1. Where the data is, and what reaches it

The MCP transport (STDIO or HTTP) only carries the tool call and its reply. The file
itself never crosses it, so what matters is what the **server** can reach:

```
 STDIO:  [ user's machine:  AI client ── solr-mcp ── ~/data.csv ]        same machine
 HTTP:   [ user's machine:  AI client, ~/data.csv ] ──net──► [ solr-mcp ]  server elsewhere
```

| Where the file is | Server can reach it? | Route |
|---|---|---|
| Public web / GitHub | yes, over HTTP | `index-url`, streamed (§3) |
| S3 / object storage | yes, with a presigned URL on the allow-list | `index-url`, streamed (§3) |
| User's machine, STDIO | yes (same disk) | `index-url` with a local path, streamed (§4.2) |
| User's machine, HTTP | no | put it at a URL; SEP-2631 later (§4.2) |
| Small pasted data | arrives in the tool call | the inline `index-*-documents` tools, unchanged |

Rule carried over from PR #194: **no transport-only tools.** Every tool is registered
identically in STDIO and HTTP. A *source* may still be available in one transport only,
when the other physically cannot reach it: the user's disk is reachable from a STDIO
server and not from an HTTP one (§4.2).

---

## 2. Today (PR #210)

```
 source ──bytes──► [ server memory: whole body, ≤ 10 MB ] ──► indexPayload ──► Solr
                    read to the last byte, then index
```

The body is read into memory, then indexed as the inline tool for its format would
index it (CSV/XML forwarded to Solr's update handlers, JSON/Markdown parsed by the
server). The 10 MB cap (`SOLR_INDEX_URL_MAX_BYTES`) exists only to bound that memory,
and it is why the tool, its description and the `index-data` prompt all have to
explain a limit and a fallback.

---

## 3. Remote files: stream the download into Solr

```
 source ──chunk──► server ──chunk──► Solr /update
        ──chunk──► (copies   ──chunk──► (parses and indexes
        ──chunk──►  ~8 KB at ──chunk──►  as bytes arrive)
                     a time)
```

### 3.1 Decisions

**S1. JSON, CSV and XML stream to Solr; nothing is held in memory.**
The fetched response stream becomes the single content stream of a
`ContentStreamUpdateRequest`, with the Content-Type for the format. Server memory per
call is a fixed small buffer regardless of file size, so **the size cap and
`SOLR_INDEX_URL_MAX_BYTES` are removed** for these formats, along with the
"over the limit, use `bin/solr post`" error and guidance.

*Verified against SolrJ 10.0.0 source:* `HttpJdkSolrClient.preparePutOrPost` sends a
request's content writer through a `PipedOutputStream`/`PipedInputStream` into
`HttpRequest.BodyPublishers.ofInputStream`, and
`ContentStreamUpdateRequest.getContentWriter` copies its one stream with
`transferTo`. `XMLRequestWriter` (which `SolrConfig` installs) only substitutes its own
writer for `UpdateRequest`; `ContentStreamUpdateRequest` is an
`AbstractUpdateRequest`, so it takes the streaming path. The body is streamed, not
buffered.

**S2. Formats map to Solr's own handlers.**

| Format | Solr endpoint | Content-Type | Note |
|---|---|---|---|
| CSV | `/update` + `header=true` | `text/csv; charset=UTF-8` | as `index-csv-documents` today |
| XML | `/update` | `application/xml` | must be an `<add>` block (S3) |
| JSON | `/update/json/docs` | `application/json` | one document per object; see S4 |
| Markdown | not streamed | — | parsed by the server (S5) |

**S3. The XML `<add>` guard stays, on a peeked prefix.** `SolrUpdateXml` rejects
anything but an `<add>` root, because the same grammar carries `<delete>` and
`<commit>`. It reads only up to the root element, so the fetcher wraps the response in
a `BufferedInputStream`, marks it, lets the StAX check read the prolog and root, and
resets before streaming. The mark limit (e.g. 64 KB) bounds a pathological prolog; a
prolog longer than that is rejected as not an `<add>` block.

**S4. JSON changes behaviour, deliberately.** Streaming JSON means Solr, not the
server, turns objects into documents, so the server's nested-object flattening no
longer applies to `index-url` JSON (field-name sanitising is being removed anyway in
PR #235). This is the same trade #205 made for CSV and XML. Use
`/update/json/docs` rather than `/update`: on `/update`, a nested map such as
`{"set": "x"}` is read as an atomic-update instruction, which is not what a user
indexing a data file means. **Verified** (`UrlIndexingIntegrationTest`, Solr 8.11,
9.9 and 10 with the `_default` configset): a nested object becomes dot-joined fields
on the same document (`{"studio":{"name":"Acme"}}` → `studio.name=Acme`), identically
on all three.

**S5. Markdown is read whole, with no limit (decided 2026-09-24).** Solr cannot parse
Markdown, so the server reads the whole document and parses it. A fixed internal cap
was proposed; the maintainer chose no limit for now, to keep the interface free of
size limits, with limits and checks to be added later if needed. The consequence —
a very large Markdown file at an allow-listed URL is held in memory for the call,
bounded only by the concurrency limit and timeouts — is recorded in
`THREAT_MODEL.md` §9.

**S6. A partial transfer must never be reported, or committed, as success.**
This is the main correctness risk, found while verifying S1: when the content writer
fails, `HttpJdkSolrClient` only logs `Cannot write Content Stream` and closes the pipe.
Solr then sees a shorter body that may still be valid (a truncated CSV is a valid
CSV), indexes it and returns 200. So:

- **The commit does not ride on the streaming request.** PR #210 (through the inline
  tools) sets `commit`/`softCommit` on the update request itself. For streaming, send
  the update without a commit, then send a separate commit **only if** the transfer
  completed.
- **The server decides "completed", not Solr.** The fetched stream is wrapped in a
  counting stream that records EOF, any read exception, and the timeout; if the
  source sent a `Content-Length`, the byte count must match it. Any failure → no
  commit, and the tool reports an error.
- **What the error must say.** Documents Solr already added from the partial body are
  not visible yet (no commit), but the `_default` configset's `autoCommit` (15 s,
  `openSearcher=false`) makes them durable, and the next commit by anyone makes them
  visible. The message therefore says the transfer failed partway, that some
  documents may appear in the collection, and to re-run (IDs make re-indexing
  idempotent) or delete by query.

**S7. Everything that protects the fetch stays.** Allow-list, link-local and
cloud-metadata refusal on every hop, no credentials or caller headers, redirect limit,
non-2xx rejected before any body is read, format resolution, connect/read/total
timeouts. The concurrency limit (`SOLR_INDEX_URL_MAX_CONCURRENT_FETCHES`, default 4)
stays: a stream holds an outbound connection and a Solr connection for its whole
duration even though it no longer holds memory. The total timeout (default 5 m)
becomes the practical bound on file size; its message should say so.

**S8. Transport.** Identical in STDIO and HTTP; nothing in this section is
transport-specific.

### 3.2 Interface after the change

```
index-url(collection, url, format?)
```

- Same three arguments. No size limit to explain for JSON/CSV/XML.
- Reply: CSV/XML/JSON → `Solr accepted the <FORMAT> document (<n> bytes) for
  collection '<c>' and committed it (status …, … ms)`. Solr's update response carries
  no document count; the `index-data` prompt already verifies the count in its next
  step. Markdown → the document count, as today.
- Configuration removed: `SOLR_INDEX_URL_MAX_BYTES`. Kept: allowed hosts, the three
  timeouts, the concurrency limit.
- Partial transfer: `The URL stopped delivering the document after <n> bytes, so what
  Solr received was not committed. …` (or `did not deliver the whole document within
  the read or total timeout` for a timeout); a failure before the first byte reports
  the existing unreachable/timeout messages instead.

### 3.3 Tests to add

- A body larger than the old cap streams through with constant server memory (assert
  on documents indexed, not on heap).
- Source connection dropped mid-body, with and without `Content-Length`: error
  returned, no commit sent, and the partial-transfer wording.
- Total timeout hit mid-stream: same as above.
- XML with a `<delete>` root is refused before any byte reaches Solr; an `<add>`
  document with a long comment prolog under the mark limit is accepted.
- JSON with nested objects, run against every image in the Solr compatibility matrix
  (S4 verification).

---

## 4. Files on the user's machine

### 4.1 Options considered

**A. Read a local path on the server.** Works only when the server shares the user's
disk, i.e. STDIO. As a separate `index-file(path)` tool it was PR #194, closed on
2026-09-12 as a transport-only tool. As a source accepted by `index-url`, the tool list
stays identical in both transports and only the accepted sources differ. **Chosen
(2026-10-07)** in that form; see §4.2.

**B. Form-mode elicitation.** The server asks the client to show the user a form.
Form schemas are limited to flat objects of primitive properties (string, number,
integer, boolean, enum); there is no file or binary type
([MCP spec, elicitation](https://modelcontextprotocol.io/specification/draft/client/elicitation)).
The most a form can collect is a path string, which is option A again. **Not viable.**

**C. URL-mode elicitation (MCP `2025-11-25`).** The server asks the client to open a
URL in the user's browser; the interaction there is out of band, and the client and
model never see it. The server could serve an upload page, and the browser would post
the file straight to the server, which streams it into Solr with the §3 path:

```
 AI client ──tools/call index-file(collection)──► server
           ◄─ elicitation/create {mode:url, url:https://server/upload/<id>} ─
 user's browser ──(opens URL, picks file, POST)──► server ──stream──► Solr
 AI client ──retry with requestState──► server ──► "Solr accepted …"
```

It keeps the file out of the model and works for HTTP. It is **not the chosen route**,
because:

1. **No STDIO equivalent.** A STDIO server has no network listener, and opening one
   is a `VALID` finding under the threat model (§13, breaking a §8 property), so it
   cannot serve an upload page. STDIO would need a different
   mechanism, which makes this transport-specific again.
2. **HTTP mode is stateless today.** `application-http.properties` sets
   `spring.ai.mcp.server.protocol=stateless`, which disables elicitation, and
   correlating the browser upload with the tool call needs server-side state. It
   would require moving the HTTP transport to stateful Streamable HTTP.
3. **New authenticated web surface.** The spec requires the server to verify that the
   user who opens the URL is the user who triggered the elicitation (its phishing
   section: typically a browser session tied to the same authorization server, with
   matching `sub`). The server has no browser login today; this is a new endpoint,
   session handling and threat-model section.
4. **Uneven client support.** URL mode is marked new in `2025-11-25` and "may change";
   clients must declare `elicitation.url`, and Spring AI 1.1.7 support for URL mode is
   unverified.

**D. MCP file upload, SEP-2631 "File Objects and Transfer"** (open draft,
modelcontextprotocol PR #2631; it replaced SEP-2356, closed 2026-06-26). The client
asks the server for an upload slot (`files/authorizeUpload`), uploads the file to the
URL it is given, and passes a file reference to the tool. It is one protocol-level
mechanism for both transports, implemented by the client rather than a web page we
host, and it is where the MCP ecosystem is converging.

**E. Solr's own tooling, run on the user's machine.** `bin/solr post -c <collection>
<file>`, or `curl` to `/update`. Streams, has no size limit, and works whichever
transport the MCP server uses. Its cost is a manual step, and the user's machine must
be able to reach Solr.

### 4.2 Decision (revised 2026-10-07)

| Source | STDIO | HTTP |
|---|---|---|
| Any local file path | ✅ | ❌ refused with a message naming the alternatives |
| GitHub / S3-style URL on the allow-list | ✅ | ✅ (the only non-inline route) |

**L1. One tool, two kinds of source.** `index-url(collection, url, format?)` keeps its
name and arguments. Under STDIO, `url` may also be a local path: absolute, `~/…`, or a
`file:` URL. The file is opened with `ContentStreamBase.FileStream` and goes through
the same §3 path as a download: JSON, CSV and XML stream to Solr, Markdown is read
whole, and the commit is sent only after the whole file was read.

**L2. No path allow-list.** Under STDIO the server runs as the user, on the user's
machine, so any file the user can read can be indexed. The operating system's file
permissions are the only limit. The path is resolved with `toRealPath()` (it must
exist and be a regular file) and is not otherwise restricted. In the Docker STDIO
image the container sees only the directories mounted with `-v`, so "any path" means
any mounted path there; the JAR and the native binary see the whole disk.

**L3. Local paths are switched on by profile, not by transport code.** A property
`solr.index-url.local-files` (env `SOLR_INDEX_URL_LOCAL_FILES`) is `true` in
`application-stdio.properties` and `false` in `application-http.properties`. The tool
is registered identically in both; only its default differs, as the Docker Compose and
security defaults already do. An HTTP deployment refuses a path with: `This server
cannot read files on your machine. Put the file at an http(s) URL on the allow-list
(for example GitHub or a presigned S3 URL) and pass that URL, or paste small data
into the inline indexing tools.`

**L4. S3 and S3-compatible storage go through the URL route.** The server holds no
cloud credentials, so a private bucket needs a presigned URL. The bucket's host must
be on `allowed-hosts`, whose default stays GitHub only: an operator adds e.g.
`my-bucket.s3.eu-west-2.amazonaws.com`, or the host of MinIO, R2 or another
S3-compatible store. `*.amazonaws.com` is not suggested, because it also admits AWS
API endpoints reachable only from inside a VPC.

**L5. Guidance changes with it.** The server instructions and the `index-data` prompt
route a local file to `index-url` with its path under STDIO, and under HTTP to a URL
(or, for a file that exists only on the user's machine, to `bin/solr post` / `curl`
run where the file is).

**L6. Threat-model impact (to record when implemented).** Under STDIO this is within
the existing trust boundary: the server already runs as the user. What is new is that
the model can now choose to read any of the user's files and put the content into
Solr, where `search` returns it. A prompt injection in data the model reads could use
that to move e.g. `~/.ssh/id_ed25519` into a Solr collection that other people can
query. Record it in `THREAT_MODEL.md` §9 (bounded by the client's tool-approval prompt
and by who can query that Solr) and §11 (misuse: pointing a STDIO server at a shared
Solr). Under HTTP nothing changes, because the property is off by default; an operator
who turns it on exposes the server's own disk to every authenticated client, which
§11 records as misuse.

**Later: SEP-2631.** When SEP-2631 is accepted and supported by Spring AI and at least
one major client, add a way to pass a SEP-2631 file reference, reusing the §3
streaming path unchanged (a file handle is just another input stream). That closes the
HTTP gap for files that exist only on the user's machine.

**Revisit C** only if SEP-2631 stalls **and** the HTTP transport has moved to stateful
mode for other reasons.

---

## 5. Phases

| Phase | Scope | Depends on |
|---|---|---|
| A (done) | §3: stream JSON/CSV/XML in `index-url`, remove `SOLR_INDEX_URL_MAX_BYTES`, partial-transfer handling | PR #210 |
| B | §4.2 L1–L6: local paths in `index-url` under STDIO, refused under HTTP | phase A; planned for PR #210 |
| C | §4.2 later: SEP-2631 file references, both transports, reusing phase A | SEP-2631 accepted; Spring AI support |

Phase A can land in PR #210 itself or as a follow-up PR; it changes no tool signature.

## 6. Open questions

1. Whether the concurrency limit and total timeout defaults (4, 5 m) still fit now
   that the size cap is gone; the total timeout is the practical bound on file size.
2. Whether Markdown should regain a limit (S5).
3. Whether an HTTP operator should be able to switch `local-files` on at all, or
   whether it should be impossible outside the `stdio` profile (L3).

Resolved during phase A: S4 (verified, above); the success reply reports the byte
count; the XML root must start within the first 64 KB
(`SolrUpdateXml.ROOT_WITHIN_BYTES`).
