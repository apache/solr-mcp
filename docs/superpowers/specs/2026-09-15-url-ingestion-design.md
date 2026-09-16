# Spec: `index-url` — index a document set from an http(s) URL

**Date:** 2026-09-15 (rewritten the same day; supersedes the streaming design, see §9)
**Status:** ready to implement
**Tracking issue:** https://github.com/apache/solr-mcp/issues/208
**Base:** upstream `main` @ `b4ffe18`. Branch `feat/index-url` on the author's fork.

Written for an implementer with no access to the discussions behind it. §3 lists
every decision with its reason; §4 to §7 are the specification; §8 is the definition
of done; §9 records what was tried before and why it was dropped. Every default value,
message, exception type and boundary condition is stated exactly; where two readings
were possible, the chosen one is written out.

---

## 1. Goal and non-goals

**Goal.** Let an MCP client index a JSON, CSV, XML or Markdown document set into a
Solr collection by naming a URL, so the payload never passes through the model's
context. Identical behaviour in the STDIO and HTTP transports. Datasets up to a
configurable cap (default 10 MB) are handled by this tool; larger ones are routed to
Solr's own bulk tooling by the model's guidance (§6.1), not by this server.

**Why this exists.** The server's differentiator is the onboarding loop: look at a
dataset, design the schema, add fields, index, verify counts, search, iterate, all in
one conversation. Every existing indexing tool takes the payload as a tool-call
argument, so the model must emit every byte. Measured on PR #197: 61 documents took
over two minutes, of which Solr and the server took under one second; the rest was the
model emitting ~9,500 tokens. The loop therefore breaks at the indexing step for
anything beyond a few dozen records, and in a chat-only client such as Claude Desktop
there is no way around it because the client cannot run a shell command. A URL
argument is ~20 tokens regardless of size, and the server fetches the bytes itself.

**Why a cap and not streaming.** Once the bytes bypass the model, the bottleneck is
gone. A 10 MB body parsed in memory by the existing parsers is instant. Datasets
larger than a conversation would ever onboard belong to `bin/solr post` or Solr's
`/update` handler, which do bulk ingestion better than this server can, with no model
in the loop. A streaming implementation existed and worked (§9); it was dropped because
it duplicated Solr's own parsers, conflicted with open PR #205, and defended a case the
guidance now routes elsewhere.

**Non-goals.**

- Local-file ingestion (`index-file`). Removed from scope (§9). Under STDIO the model
  gives the user the `bin/solr post` command; under HTTP an attached file goes through
  the inline tools as today.
- Schema definition from a URL. Schema payloads are a few KB and the model should read
  and reconcile them against `get-schema`.
- HTML pages. `text/html` is an error, not converted.
- Authenticated or header-customised fetches.
- Streaming or unlimited size. That is the phase-2 follow-up in §10, which keeps this
  tool's contract and changes only its insides.
- Progress notifications. The HTTP transport is `stateless`; unchanged.

---

## 2. What exists on `main` that this builds on (verified 2026-09-15 @ `b4ffe18`)

| Component | Location | Role |
|---|---|---|
| `IndexingService.indexJsonDocuments` / `indexCsvDocuments` / `indexXmlDocuments` / `indexMarkdownDocuments` | `indexing/IndexingService.java` lines 219, 294, 393, 467 | The four inline tools. Each calls `indexingDocumentCreator.createSchemalessDocumentsFrom<Format>(String)`, then `indexDocuments(collection, docs)`, then returns `"Successfully indexed " + successCount + " of " + docs.size() + " documents into collection '" + collection + "'"`. The summary text is duplicated four times. |
| `IndexingService.indexDocuments(String collection, List<SolrInputDocument>)` | same file | Batches, adds, commits, returns the success count. |
| `IndexingDocumentCreator.createSchemalessDocumentsFrom{Json,Csv,Xml,Markdown}(String)` | `indexing/documentcreator/` | Fully materialised parsers. Throw the unchecked `DocumentProcessingException`. |
| `IndexingService.SCHEMA_FIRST_GUIDANCE` | same file, line 128 | Trailing sentence appended to every indexing tool description. |
| `IndexingService.indexDataPrompt` | same file | The `index-data` MCP prompt; step 3 chooses the tool. |
| `spring.ai.mcp.server.instructions` | `application.properties` | Server instructions shown to every client. |
| `McpToolRegistrationTest` | `src/test/java/.../McpToolRegistrationTest.java` | Reflection-only checks: tool names unique, parameters annotated, every `@McpTool` method carries `@PreAuthorize`. Holds an explicit list of service classes. |
| `McpClientIntegrationTestBase` + `McpClientIntegrationTest` (HTTP) + `McpClientStdioIntegrationTest` | same directory | Full MCP round trips per transport against Testcontainers Solr. The base asserts the expected tool list and per-tool hints. |
| `SolrConfig` | `config/SolrConfig.java` line 104 | `@EnableConfigurationProperties(SolrConfigurationProperties.class)`; new property records are registered here. |
| `spring-web` 6.2.18 | production classpath | Provides `RestClient`. Apache HttpClient and Jetty client are **not** on the production classpath. |

---

## 3. Decisions and why

**D1. Hosts are allow-listed, restricted by default to GitHub's raw-content hosts.**
Default `solr.index-url.allowed-hosts` =
`raw.githubusercontent.com,*.githubusercontent.com,github.com`. `*` allows any host.
*Why:* the tutorial example (`raw.githubusercontent.com/apache/solr-mcp/main/src/test/resources/shows.json`)
works with no configuration; an HTTP deployment on a corporate network is safe without
the operator remembering a knob, which matches the project's posture of HTTP security
being on by default; an operator hosting data on S3 or an internal server adds one
entry. An earlier draft allowed any host by default and documented the SSRF exposure;
that made the server an open SSRF primitive in HTTP mode and would have drawn a
maintainer objection on the threat model. `github.com` is included because
`github.com/<org>/<repo>/raw/...` URLs redirect to `raw.githubusercontent.com`.

**D2. Link-local and cloud-metadata addresses are refused even when the allow-list is `*`.**
`169.254.0.0/16`, `fe80::/10` and `fd00:ec2::254`.
*Why:* the single worst outcome of `*` is a cloud instance handing its credentials to
a caller. Refusing these needs no configuration and blocks no legitimate document
host. Loopback and RFC1918 are **not** refused; they are governed by the allow-list
like any other host.

**D3. The body is read into memory, capped at `max-bytes` (default 10 MB), and parsed by the existing creators.**
*Why:* it reuses code that is already tested, keeps the server's field-name
sanitising and nested-XML flattening, adds no parser code, and makes "nothing was
indexed" true for every fetch or parse failure, because indexing starts only after the
whole body has been parsed. 10 MB is roughly 15,000 shows-sized JSON records, far
beyond what a conversation onboards. The cap is a property so an operator can raise it.

**D4. Datasets over the cap are routed to Solr's own tooling by guidance, not by this server.**
*Why:* `bin/solr post -c <collection> <file>` and `curl` to `/update` already do bulk
ingestion with no model in the loop and no new attack surface, and they run where the
file is, which also covers the Claude Desktop local-file case that no server-side tool
can reach (the client cannot send files to an MCP server; SEP-2631 is the open draft
that would change that). The model emits the exact command (§6.1).

**D5. The HTTP client is Spring's `RestClient` over `SimpleClientHttpRequestFactory`.**
*Why:* it is already on the classpath, its `readTimeout` applies to every socket read
including the body (the JDK `java.net.http.HttpClient`'s timeout covers only the
response headers, which is what forced a hand-written watchdog in the earlier draft),
and it gives the outbound call Micrometer observations for free. `RestClient`'s
`exchange(...)` is used rather than `retrieve()` because `exchange` does not apply
status handlers, so non-2xx responses are handled by this code, not by an exception
from Spring. Apache HttpClient would also work but is not on the classpath and is not
worth a new dependency.

**D6. Redirects are followed manually: up to five are followed; the sixth redirect response is an error; `https` to `http` is refused.**
*Why:* release-asset and `github.com/.../raw` URLs redirect. Manual following is
required so the allow-list and D2 run on every hop; `HttpURLConnection` follows
redirects itself by default, so the request factory turns that off (§4.3).

**D7. No credentials and no caller-supplied headers, ever; a URL with embedded `user:pass@` is rejected.**
*Why:* `THREAT_MODEL.md` §12 names credential-from-tool-argument as a model-changing
condition. The server sends only `Accept` and `User-Agent`.

**D8. Format resolution: explicit `format` > requested URL's path extension > final URL's path extension > `Content-Type` > error. `text/plain` and `text/html` never resolve.**
*Why:* `raw.githubusercontent.com` serves `.json` as `text/plain; charset=utf-8`, as
do most static hosts, so `Content-Type` alone would mis-route the canonical example.
`text/html` is refused so an ordinary web page cannot be indexed as CSV rows. The
final URL is consulted so a short link that redirects to `.../shows.json` resolves.

**D9. Non-2xx is an error before any body is read.**
*Why:* a GitHub 404 body is the literal text `404: Not Found`; the CSV parser would
index it as one valid document.

**D10. One tool with an optional `format`, not four per-format URL tools.**
*Why:* PR #197 kept per-format inline tools because each signature is optimised for its
format's token shape. `index-url` carries no payload in its arguments, so there is no
shape to optimise.

**D11. Registered in both transports with no `@Profile` gate.**
*Why:* PR #194 was closed because a STDIO-only tool gave the two transports different
surfaces. This design has no transport-specific tool and reverses no prior decision.

**D12. Defaults chosen by the spec author, to be confirmed in the PR:** the default
allow-list contents (D1) and the 10 MB cap (D3). Both are single constants.

---

## 4. Server implementation

### 4.1 Tool contract

```
index-url(collection: String, url: String, format: String?) -> String
```

| Parameter | `required` | Description text (verbatim for `@McpToolParam`) |
|---|---|---|
| `collection` | `true` | `Solr collection to index into` |
| `url` | `true` | `Absolute http or https URL of a UTF-8 JSON, CSV, XML or Markdown document, at most the configured size limit (default 10 MB). The host must be on the server's allow-list (GitHub raw content by default). Fetched from the MCP server's network, not the client's. No credentials or custom headers are sent.` |
| `format` | `false` | `Optional format: json, csv, xml, markdown or md; defaults to the URL path extension, then the Content-Type` |

`@McpTool` attributes: `name = "index-url"`,
`annotations = @McpTool.McpAnnotations(idempotentHint = true, openWorldHint = true)`.
The method carries `@PreAuthorize("isAuthenticated()")`.

Description (verbatim, followed by `IndexingService.SCHEMA_FIRST_GUIDANCE`):

> Index a UTF-8 JSON, CSV, XML or Markdown document set from an http(s) URL without
> sending its contents through the model. Available in both STDIO and HTTP mode. The
> URL is fetched by the MCP server with no credentials or custom headers; its host must
> be on the server's allow-list (GitHub raw content by default) and the body must be
> within the configured size limit (default 10 MB). For larger datasets, index directly
> with Solr (bin/solr post or the /update handler) instead. Redirects are followed.
> Non-2xx responses and HTML pages are errors; nothing is indexed unless the whole
> document parses. Reuse the URL for another collection.

Return value: the same summary string the inline tools return (§4.4).

### 4.2 Classes

All in `org.apache.solr.mcp.server.indexing` unless stated. The package is
`@NullMarked`; use `org.jspecify.annotations.Nullable` where a value may be null.

| Class | Kind | Responsibility |
|---|---|---|
| `UrlIndexingService` | `public`, `@Service @Observed` | The `@McpTool` method. Public constructor `(IndexingService, UrlIndexingProperties)` **annotated `@Autowired`**; package-private constructor `(IndexingService, UrlIndexingProperties, UrlFetcher)` for the unit test. Spring refuses to choose between two constructors without the annotation; every Spring test fails to start if it is missing. |
| `UrlIndexingProperties` | `public record`, `@ConfigurationProperties(prefix = "solr.index-url")` | `List<String> allowedHosts` (`@DefaultValue({"raw.githubusercontent.com", "*.githubusercontent.com", "github.com"})`), `Duration connectTimeout` (`@DefaultValue("10s")`), `Duration readTimeout` (`@DefaultValue("30s")`), `DataSize maxBytes` (`@DefaultValue("10MB")`). The record's compact constructor rejects `maxBytes` outside `1 .. Integer.MAX_VALUE - 1` bytes with `IllegalArgumentException("solr.index-url.max-bytes must be between 1 byte and 2 GB")`, so a misconfiguration fails at startup; there is no "0 means unlimited". Registered by adding it to the `@EnableConfigurationProperties` annotation on `SolrConfig`. |
| `UrlTargetPolicy` | package-private final, static `check(URI uri, List<String> allowedHosts, List<InetAddress> resolved)` | Pure, no I/O. Throws only `IllegalArgumentException`. Order of checks and messages in §4.3 step 3. |
| `UrlFetcher` | package-private final | Owns the `RestClient`. Resolves the host, calls the policy, performs the request, follows redirects, enforces the size cap, returns `FetchedBody(URI finalUri, String mediaType, Charset charset, byte[] body)`. Throws `IllegalArgumentException` for caller-fixable problems and `IOException` for network problems (§4.3). |
| `IndexFormats` | package-private final, static `normalize(String keyword)` | Maps `json`, `csv`, `xml`, `md`, `markdown` (any case, trimmed) to `json`/`csv`/`xml`/`markdown`; anything else, including blank, throws `IllegalArgumentException("Cannot determine the file format. Supply format=json, csv, xml or markdown.")`. Used only by `UrlIndexingService`; the existing private switch in `IndexingService.resolveIndexTool` is left as is. |
| `IndexingService.indexPayload(String collection, String payload, String format)` | new package-private method | See §4.4. |

No other production classes. In particular there is no streaming reader, no watchdog,
no byte-cap stream, and no scheduler.

### 4.3 Fetch algorithm (`UrlIndexingService.indexUrl` then `UrlFetcher.fetch`)

1. If `collection.isBlank()` throw `IllegalArgumentException("Provide a non-empty collection name.")`.
   Do not null-check `collection` or `url`: `required = true` makes null impossible.
2. If `url.isBlank()`, or `URI.create(url.trim())` throws, throw
   `IllegalArgumentException(INVALID_URL)` where
   `INVALID_URL = "Provide an absolute http or https URL."`.
3. If `format` is non-null and non-blank, `explicit = IndexFormats.normalize(format)`
   (throws before any network activity); otherwise `explicit = null`.
4. `fetcher.fetch(uri)`; an `IOException` from it becomes an `IllegalStateException`
   per §4.5 (two rows: read timeout vs everything else).
5. Inside `UrlFetcher.fetch`, with `current = uri` and `redirects = 0`, loop:
   1. `UrlTargetPolicy.check(current, allowedHosts, List.of())` — syntactic checks
      only, before any DNS lookup, in this order: scheme must be `http` or `https`
      (case-insensitive) and `getHost()` non-null, else `INVALID_URL`;
      `getUserInfo()` must be null, else
      `"Remove the credentials from the URL; this server never sends credentials."`;
      host must match the allow-list (§4.3.1), else the allow-list message (§4.5).
   2. `addresses = InetAddress.getAllByName(current.getHost())`; an
      `UnknownHostException` propagates as an `IOException`.
   3. `UrlTargetPolicy.check(current, allowedHosts, addresses)` — repeats step 5.1
      and then, for **every** address, throws
      `"This server does not fetch link-local or cloud-metadata addresses."` if
      `isLinkLocalAddress()` is true or the address equals `fd00:ec2::254`. This
      applies regardless of the allow-list, including `*`. (`getAllByName` returns an
      `Inet4Address` for an IPv4-mapped literal such as `::ffff:169.254.169.254`, and
      decodes a bare decimal host such as `2852039166` to `169.254.169.254`, so both
      are caught here.)
   4. Send `GET current` through the `RestClient` with headers exactly
      `Accept: application/json, text/csv, application/xml, text/xml, text/markdown, text/plain;q=0.5, */*;q=0.1`
      and `User-Agent: solr-mcp`, and nothing else. Use
      `restClient.get().uri(current).exchange((request, response) -> ...)` so that
      non-2xx statuses reach this code instead of a Spring exception. Inside the
      exchange function:
      - Let `status = response.getStatusCode().value()`.
      - If `status` is 301, 302, 303, 307 or 308 **and** a `Location` header is
        present: record the target and return a redirect marker. Otherwise, if the
        status is not 2xx, return a status marker. Otherwise read headers and body as
        in steps 5.7 to 5.9 and return them. (Markers are a small sealed interface or
        record; the body stream must be fully consumed or closed before returning.)
   5. On a redirect marker: increment `redirects`; if it is now **greater than 5**,
      throw `"The URL redirected more than 5 times. Use the final URL directly."`
      (five redirects are followed; the sixth redirect response fails). Compute
      `target = current.resolve(location)`; if `current` is `https` and `target` is
      `http` throw
      `"The URL redirects from https to http, which is refused. Use the final https URL directly."`;
      set `current = target` and continue the loop from 5.1.
   6. On a status marker for status *N*: throw
      `"The URL returned HTTP N; nothing was indexed. Check that it is public and points at a raw document, not a web page."`.
   7. Media type = the `Content-Type` value up to the first `;`, trimmed, lower-cased;
      `""` if the header is absent. Charset = the `charset=` parameter (quotes
      stripped) via `Charset.forName`; UTF-8 if absent; an
      `IllegalCharsetNameException` or `UnsupportedCharsetException` becomes
      `"The URL declares an unsupported charset. Supply a UTF-8 document."`.
   8. If a `Content-Length` header is present and greater than `maxBytes`, throw the
      size error (§4.5) **without reading the body**.
   9. `bytes = body.readNBytes((int) maxBytes + 1)` (safe: the record validates
      `maxBytes <= Integer.MAX_VALUE - 1`). If `bytes.length > maxBytes` throw the
      size error. The count is of raw body bytes after transfer decoding and before
      charset decoding. Close the response in a `finally` on every path out of the
      exchange function.
   10. Return `FetchedBody(current, mediaType, charset, bytes)`.
6. Back in the service: `selected = explicit != null ? explicit : resolveFormat(uri, fetched.finalUri(), fetched.mediaType())` (§4.3.2).
7. `payload = new String(fetched.body(), fetched.charset())`.
8. `return indexingService.indexPayload(collection, payload, selected)`, mapping
   its exceptions per §4.5.

#### 4.3.1 Allow-list matching

Host is `uri.getHost()` lower-cased with any trailing `.` removed. Each entry is
trimmed and lower-cased. An entry matches when:

- it is exactly `*` (matches every host), or
- it starts with `*.` and the host ends with the entry's suffix including the leading
  dot, and the host is longer than that suffix (so `*.githubusercontent.com` matches
  `raw.githubusercontent.com` but not `githubusercontent.com`), or
- it equals the host exactly (this is the only way an IP literal or `localhost`
  matches, other than `*`).

An empty list matches nothing: every call fails with the allow-list message. That is
the behaviour when an operator sets `SOLR_INDEX_URL_ALLOWED_HOSTS=`; it is not
special-cased.

#### 4.3.2 Format resolution

Extension of a URI = the substring after the last `.` of the last `/`-separated
segment of `getPath()`, lower-cased; `null` if the path is null, has no `.` in its
last segment, or ends with `.`. Query string and fragment are excluded because
`getPath()` excludes them.

For each of `requested`, then `finalUri`: if the extension is non-null and
`IndexFormats.normalize(extension)` succeeds, that is the format. Otherwise:

| Media type | Format |
|---|---|
| `application/json` | `json` |
| `text/csv` | `csv` |
| `application/xml`, `text/xml` | `xml` |
| `text/markdown` | `markdown` |
| `text/html` | error `FORMAT_UNRESOLVED + " HTML pages are not supported."` |
| anything else, including `text/plain`, `application/octet-stream` and `""` | error `FORMAT_UNRESOLVED` |

`FORMAT_UNRESOLVED = "Cannot determine the format from the URL path or Content-Type. Supply format=json, csv, xml or markdown."`

### 4.4 `IndexingService.indexPayload` (refactor, no behaviour change)

```java
String indexPayload(String collection, String payload, String format) throws SolrServerException, IOException
```

- `switch (format)`: `"json"` → `createSchemalessDocumentsFromJson`, `"csv"` → `...FromCsv`,
  `"xml"` → `...FromXml`, `"markdown"` → `...FromMarkdown`; any other value throws
  `IllegalArgumentException("Unsupported document format: " + format)` (unreachable
  from `index-url`, which passes normalised values).
- Then `int successCount = indexDocuments(collection, docs)` and return
  `"Successfully indexed " + successCount + " of " + docs.size() + " documents into collection '" + collection + "'"`.
- Each of the four inline tool methods becomes: validate its arguments exactly as it
  does today, then `return indexPayload(collection, <payload>, "<format>")` inside its
  existing try/catch. Their error messages, exception types and logging do not change.
  `IndexingServiceTest` must pass **unmodified**; that is the check that the refactor
  is behaviour-preserving.

### 4.5 Error mapping in `UrlIndexingService`

`IllegalArgumentException` for caller-fixable problems, `IllegalStateException` for
environment problems; log the cause at `debug` for caller problems and `warn` for Solr
problems; never include the response body, resolved IP, or stack detail in a message.
Because parsing completes before indexing starts, every row above the Solr row can
truthfully say nothing was indexed.

| Condition | Exception | Message (verbatim) |
|---|---|---|
| blank `collection` | IAE | `Provide a non-empty collection name.` |
| blank / unparsable / relative / non-http(s) `url`, or null host | IAE | `Provide an absolute http or https URL.` |
| `url` has userinfo | IAE | `Remove the credentials from the URL; this server never sends credentials.` |
| host not on the allow-list | IAE | `The URL's host is not on this server's allow-list. Allowed by default: raw.githubusercontent.com, *.githubusercontent.com, github.com. The operator can change SOLR_INDEX_URL_ALLOWED_HOSTS (use * to allow any host). Nothing was indexed.` (the "Allowed by default" list is a constant, not the live configuration) |
| link-local / metadata address (D2) | IAE | `This server does not fetch link-local or cloud-metadata addresses.` |
| unknown explicit `format` | IAE | `Cannot determine the file format. Supply format=json, csv, xml or markdown.` |
| `https` → `http` redirect | IAE | `The URL redirects from https to http, which is refused. Use the final https URL directly.` |
| sixth redirect | IAE | `The URL redirected more than 5 times. Use the final URL directly.` |
| non-2xx status *N* | IAE | `The URL returned HTTP N; nothing was indexed. Check that it is public and points at a raw document, not a web page.` |
| unsupported charset | IAE | `The URL declares an unsupported charset. Supply a UTF-8 document.` |
| body larger than `maxBytes` (by `Content-Length` or by reading) | IAE | `The document is larger than this server's limit of <limit>; nothing was indexed. Index datasets this large directly with Solr (bin/solr post or the /update handler); the index-data prompt shows the command.` where `<limit>` is `maxBytes.toMegabytes() + " MB"` if `maxBytes.toBytes() % 1048576 == 0`, otherwise `maxBytes.toBytes() + " bytes"` (so the default renders as `10 MB`) |
| format unresolved | IAE | `FORMAT_UNRESOLVED`, with ` HTML pages are not supported.` appended for `text/html` |
| `ResourceAccessException` whose cause chain contains a `SocketTimeoutException` | ISE | `The URL did not respond within the read timeout; nothing was indexed. Try again or ask the operator to raise SOLR_INDEX_URL_READ_TIMEOUT.` |
| any other `IOException` / `ResourceAccessException` (unknown host, connect refused, connect timeout) | ISE | `Cannot reach the URL from the MCP server. The URL is fetched from the server's network, not the client's, so localhost and private addresses refer to the server's side. Check the address and try again.` |
| `DocumentProcessingException` from `indexPayload` | IAE | `Cannot parse the URL content as <format>. Check its syntax and format. Nothing was indexed.` |
| `SolrServerException`, `SolrException` or `IOException` from `indexPayload` | ISE | `Solr could not complete URL indexing. Check collection availability and field types with get-schema, then verify the indexed count before retrying; some documents may already be indexed.` |

`RestClient` wraps I/O failures in `org.springframework.web.client.ResourceAccessException`.
`UrlFetcher.fetch` catches it and rethrows the cause if the cause is an `IOException`,
otherwise wraps it in a new `IOException(cause)`. Consequently `fetch` throws exactly
two exception types, `IllegalArgumentException` and `IOException`, and the service
applies the two ISE rows above by checking whether the `IOException` or anything in its
cause chain is a `java.net.SocketTimeoutException`.

### 4.6 Configuration

Add to `application.properties` (not to a profile file; the tool exists in both):

```properties
# index-url: which hosts may be fetched (exact host, *.suffix, or * for any), the
# connect and per-read timeouts, and the maximum body size. Link-local and
# cloud-metadata addresses are refused regardless of the allow-list.
solr.index-url.allowed-hosts=raw.githubusercontent.com,*.githubusercontent.com,github.com
solr.index-url.connect-timeout=10s
solr.index-url.read-timeout=30s
solr.index-url.max-bytes=10MB
```

Environment names via Boot's relaxed binding: `SOLR_INDEX_URL_ALLOWED_HOSTS`
(comma-separated), `SOLR_INDEX_URL_CONNECT_TIMEOUT`, `SOLR_INDEX_URL_READ_TIMEOUT`,
`SOLR_INDEX_URL_MAX_BYTES`. Documentation uses the environment names.

Request factory: a subclass of `SimpleClientHttpRequestFactory` overriding
`prepareConnection(HttpURLConnection, String)` to call `super`, then
`connection.setInstanceFollowRedirects(false)`; `setConnectTimeout` and
`setReadTimeout` from the properties. The `RestClient` is built once in the
`UrlIndexingService` public constructor and passed to `UrlFetcher`.

### 4.7 Native image

`RestClient` over `HttpURLConnection` and the JDK `com.sun.net.httpserver.HttpServer`
used by tests are already exercised natively elsewhere in the Spring ecosystem, and a
GraalVM spike on 2026-09-15 confirmed `HttpServer` and https fetches work in this
project's native build without hints. A `@ConfigurationProperties` record needs no
hint under Spring AOT. Do not add to `SolrNativeHints` unless `nativeTest` proves it
necessary.

---

## 5. Tests

Naming: `*Test` = unit (Mockito allowed, and then `@DisabledInNativeImage`),
`*IntegrationTest` = Testcontainers Solr. Every new test class except the one Mockito
class runs natively.

| Test | Kind | Asserts |
|---|---|---|
| `UrlTargetPolicyTest` | unit, no mocks | Addresses built with `InetAddress.getByName` on literals, so no DNS. Allow-list: exact match is case-insensitive and ignores a trailing dot; `*.githubusercontent.com` matches `raw.githubusercontent.com` and not `githubusercontent.com`; `*` matches `10.0.0.1` and `localhost`; an empty list rejects `raw.githubusercontent.com`; a non-listed host is rejected with the allow-list message. D2: `169.254.169.254`, `fe80::1`, `fd00:ec2::254`, `::ffff:169.254.169.254` rejected even with `*`; one link-local address among several rejects. Syntax: `ftp://`, `file://`, `data.json`, `/data.json`, `http:///data.json` rejected with `INVALID_URL`; `http://user:pw@host/` rejected with the credentials message. Allowed: `127.0.0.1`, `::1`, `10.0.0.1`, `93.184.216.34` with `*`. |
| `IndexFormatsTest` | unit, no mocks | `json,csv,xml,md,markdown` in any case with surrounding spaces normalise; `""`, `"  "`, `yaml`, `txt`, `html`, `jsonl` throw with the exact message. |
| `UrlFetcherTest` | unit, no mocks | A JDK `HttpServer` on `127.0.0.1:0` with a cached-thread-pool executor (a sleeping handler must not block the dispatcher). Two fetchers: `open` (allow-list `*`) and `restricted` (allow-list `127.0.0.1` only); both connect 5s, read **1s**, cap **1 KB**. Cases with `open` unless stated: 200 with `application/json; charset=utf-8` returns media type `application/json`, UTF-8 and the bytes; `charset=iso-8859-1` honoured; no `Content-Type` gives `""` and UTF-8; `charset=no-such-charset` errors; 404 errors before any body is exposed; absolute and relative `Location` both resolve to the final URI; a chain of exactly 5 redirects succeeds and a chain of 6 fails with the redirect message; a `/loop` that redirects to itself fails with the redirect message; `redirectTarget("https://a/x", "http://a/y")` fails with the downgrade message and `redirectTarget("http://a/x", "https://a/y")` succeeds (static method, no server); a redirect to `http://169.254.169.254/` fails with the D2 message; with `restricted`, a redirect to `http://example.invalid/` fails with the allow-list message and no DNS lookup is attempted (the test cannot observe DNS directly; it asserts the message and that the run takes under one second); `http://169.254.169.254/` fails with the D2 message and the server records no request; `http://nonexistent.invalid/x` throws `UnknownHostException`; a handler that declares `Content-Length: 2048` fails with the size message and the server observes the body was never requested past the headers (assert via the message and that the handler's `getResponseBody().write` was never reached, using a flag); a chunked handler that sends 1,025 bytes fails with the size message; a handler that sends 10 bytes then sleeps 3 s makes `fetch` throw `java.net.SocketTimeoutException` (the unwrapped cause) within 5 s; a header-recording handler sees `User-agent: solr-mcp` and an `Accept` header and no `Authorization` or `Cookie`. |
| `UrlIndexingServiceTest` | unit, Mockito, `@DisabledInNativeImage` | `UrlFetcher` and `IndexingService` mocked. One case per row of §4.5 that the service maps (not the fetcher-internal ones, which pass through unchanged and are asserted once); explicit format wins over `.json` extension; `.csv?token=x` resolves to `csv` over `text/plain`; `/data` with `text/xml` resolves to `xml`; the final URI's extension is used when the requested URI has none; `text/plain` with `.txt` errors and `indexPayload` is never called; `text/html` errors with the HTML suffix; unknown explicit format errors before `fetch` is called; blank collection and blank URL error before `fetch` is called. Note: the test URL constant must **not** end in `.json` when the case relies on the media type. |
| `IndexingServiceTest` | existing, **unmodified** | Passes after the `indexPayload` refactor. |
| `UrlIndexingIntegrationTest` | integration | `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)`, with `solr.index-url.allowed-hosts=127.0.0.1` and `solr.index-url.max-bytes=64KB` via `@TestPropertySource` (this is the one end-to-end check that the allow-list binds from configuration). A JDK `HttpServer` on `127.0.0.1:0` serves: `/shows.json` as `text/plain; charset=utf-8` (61 records indexed, count 61 via `SolrQuery("*:*")`, summary does not contain "Stranger Things"); generated `/shows.csv` (50 rows), `/shows.xml` (40 `<show>` elements under `<shows>`), `/shows.md` (one document); `/export.csv?token=abc` (30 rows, CSV by extension); `/data` with `application/json` (61); `/moved` 302 to `/shows.json`. Failure cases, each asserting the exact message and, after an explicit `solrClient.commit`, a collection count of 0: `/data.txt` as `text/plain`; `/page` as `text/html`; `/missing` 404; `/big.csv` (a generated 100 KB CSV with `Content-Length`, over the 64 KB test cap); and `http://example.invalid/x.json` (allow-list message; no DNS is attempted because the allow-list check precedes resolution). The metadata refusal is **not** tested here because the allow-list check would reject `169.254.169.254` first with the allow-list message; it is covered end to end by the client tests, which use `*`. Each test method creates its own `_default` collection named `url_<case>_<nanoTime>`. |
| `McpClientIntegrationTestBase` | existing, edited | `listToolsReturnsExpectedTools` also asserts `index-url` is present; `toolsExposeBehaviorHints` asserts `assertHint(tools, "index-url", false, true, true)` and `openWorldHint == TRUE`. Gains `serveShowsJson()` returning a loopback `HttpServer` serving `shows.json` as `text/plain`, and `showsJsonUrl(server)`. Both client tests run with allow-list `*` so that the metadata refusal is reachable: HTTP adds `"solr.index-url.allowed-hosts=*"` to its `@SpringBootTest(properties = ...)`; STDIO adds `.addEnvVar("SOLR_INDEX_URL_ALLOWED_HOSTS", "*")` to its `ServerParameters`. |
| `McpClientIntegrationTest` (HTTP) | existing, edited | One round trip: `create-collection shows-url-copy`, `index-url` from the test server, summary contains `61 of 61` and not `Stranger Things`, `search` count 61. One call to `http://169.254.169.254/latest/meta-data/` is an MCP tool error (`isError() == TRUE`) whose text contains `link-local or cloud-metadata`. |
| `McpClientStdioIntegrationTest` | existing, edited | The same round trip and the same refused call. |
| `McpToolRegistrationTest` | existing, edited | Add `UrlIndexingService.class` to the unique-names list and to the `@PreAuthorize` list. It is reflection-only and cannot assert per-transport registration; the base class assertions do that. |

---

## 6. Model-facing and user-facing text

### 6.1 Model-facing

**`IndexingService.indexDataPrompt`, step 3** — on `main` the step reads, verbatim:

```
3. Index the documents.
   - Call `%s` with `collection=%s` and `%s=<the document payload>`.
   - The tool batches internally and commits at the end. ...
   - On error, read the message carefully: ...
```

Replace only the first bullet (`- Call `%s` with ...`) with the three bullets below, in
this order, leaving the other two bullets and every `%s` placeholder and its
`String.format` argument unchanged (the third new bullet reuses the same three
placeholders in the same order as the bullet it replaces):

> - If the data is reachable at an http(s) URL and is within the server's size limit
>   (10 MB unless the operator changed it), prefer `index-url` with `collection` and
>   `url`; optionally override the detected `format`. The URL is fetched by the MCP
>   server, so it must be reachable from the server's network and its host must be on
>   the server's allow-list (GitHub raw content by default).
> - If the data is larger than that limit, or is a file on the user's machine that is
>   too large to paste, do not push it through this conversation. Give the user this
>   command to run where the file is, with their collection name and Solr URL filled
>   in, then continue with step 4:
>   `bin/solr post -c <collection> <file>`
>   or `curl -X POST '<solr-url>/<collection>/update?commit=true' -H 'Content-Type: application/json' --data-binary @<file>`
>   (use `Content-Type: text/csv` or `application/xml` for those formats).
> - Otherwise, for small pasted or attached data, call `%s` with `collection=%s` and
>   `%s=<the document payload>`. Use one path only; do not also send inline data after a
>   successful URL call.

**`spring.ai.mcp.server.instructions`** in `application.properties` — on `main` the
value is three sentences: "This server provides tools …", "Discover before acting: …
before searching or indexing.", "Surface boundaries: …". Insert the following as a new
sentence between the second and the third (i.e. immediately before "Surface
boundaries:"), as its own continuation line ending in ` \`:

> For data at an http(s) URL under the size limit, prefer index-url; for larger
> datasets tell the user to run bin/solr post or curl against Solr's /update handler;
> use the inline indexing tools only for small pasted data.

### 6.2 User-facing

- **`README.md` tool table**: add, directly after the `index-json-documents` row,
  `| index-url | Index a UTF-8 JSON, CSV, XML or Markdown document from an http(s) URL on the allow-list (both transports; default 10 MB limit) |`.
- **`README.md`**, after the "Before indexing" paragraph, a paragraph headed
  **Index from a URL:** giving the `shows.json` raw GitHub example call, stating that the
  server fetches from its own network with no credentials, that only allow-listed hosts
  are fetched with the default list spelled out, that `SOLR_INDEX_URL_ALLOWED_HOSTS`
  accepts exact hosts, `*.suffix` patterns or `*`, that the default limit is 10 MB via
  `SOLR_INDEX_URL_MAX_BYTES`, and that larger datasets go straight to Solr with
  `bin/solr post`. Mention `SOLR_INDEX_URL_CONNECT_TIMEOUT` (10s) and
  `SOLR_INDEX_URL_READ_TIMEOUT` (30s) in one sentence.
- **`docs/tutorial.md`**: in the ingestion section, replace the sentences that say the
  server does not fetch URLs with: use `index-url` with the raw GitHub URL of
  `shows.json`; the host is on the default allow-list; for a file on your own machine
  either paste small data or run `bin/solr post -c shows shows.json` where the file is,
  because the client cannot send files to the server and the server cannot see your
  disk.
- **`docs/security/stdio.md`**, after the `SOLR_URL` bullet: one bullet stating
  `index-url` is the only tool that makes an outbound request to a caller-supplied
  address; hosts are allow-listed (GitHub raw content by default), link-local and
  metadata addresses are always refused, no credentials or caller headers are sent,
  and the body is capped.
- **`docs/security/http.md`**, in the "Forbidden" list: one bullet, "Setting
  `SOLR_INDEX_URL_ALLOWED_HOSTS=*` on a deployment whose network has internal services
  you would not expose to every authenticated MCP caller."

---

## 7. `THREAT_MODEL.md` edits (all required)

1. **§8 property 5** — change "so the AI client cannot repoint the server or inject a
   target URL" to "so the AI client cannot repoint the server's **Solr backend** or its
   credentials"; change the *Violation* clause to "a tool argument alters the Solr
   backend target or a credential". Append: "`index-url` performs an outbound GET to a
   caller-supplied `http(s)` URL whose host must be on an operator allow-list
   (GitHub raw content by default); that is a §9-bounded property, not a backend
   target, and it never carries credentials or caller-supplied headers." Add
   `UrlFetcher` to the documented-in list.
2. **§9** — add as the first bullet: "**It does not verify what an allow-listed URL
   serves.** `index-url` fetches any `http(s)` URL whose host matches
   `SOLR_INDEX_URL_ALLOWED_HOSTS` (default: GitHub raw-content hosts; `*` allows any
   host the server can reach, including loopback and RFC1918). Link-local and
   cloud-metadata addresses are refused on every redirect hop regardless. The fetch
   carries no credentials or caller headers, refuses an https→http redirect, and reads
   at most `SOLR_INDEX_URL_MAX_BYTES` (default 10 MB). The address check runs on the
   resolved addresses before the connection is made, so a DNS answer that changes in
   between (DNS rebinding) can bypass it; with the default allow-list that requires
   control of a GitHub host's DNS, and with `*` the operator has accepted the network
   boundary. *(documented — `UrlTargetPolicy`, `UrlFetcher`.)*"
3. **§11** — add: "**Setting `SOLR_INDEX_URL_ALLOWED_HOSTS=*` on a network with
   reachable internal services** that you would not expose to every authenticated MCP
   caller. *(documented — docs/security/http.md.)*"
4. **§11a** — change the `SOLR_URL` non-finding to say "The *Solr* target is
   deployer-only startup config". Add: "**\"`index-url` allows SSRF.\"** With the default
   allow-list the server fetches only GitHub raw-content hosts: `KNOWN-NON-FINDING`.
   With `*` the operator has chosen the boundary: `OUT-OF-MODEL: trusted-input`. A
   report is `VALID` only if it shows a non-allow-listed host being fetched, a
   credential or caller header being forwarded, a refused address being reached other
   than through DNS rebinding (§9), or an https→http downgrade being followed."
5. **§12** — replace the "backend-target or credential from a tool argument" bullet
   with: "Allowing a **credential** to originate from a tool argument or per-request
   input (would open credential-relay). *The backend-target half was exercised
   deliberately on 2026-09-15 by `index-url`
   ([#208](https://github.com/apache/solr-mcp/issues/208)) behind an operator
   allow-list; see §8.5 and §9.*" Add a new bullet: "Changing the **default** of
   `SOLR_INDEX_URL_ALLOWED_HOSTS` to `*`."
6. **§13** — in the `VALID` row change "tool-arg repoints backend" to "tool-arg repoints
   the Solr backend or forwards a credential, `index-url` fetches a non-allow-listed
   host". In the `OUT-OF-MODEL: trusted-input` row add `SOLR_INDEX_URL_ALLOWED_HOSTS=*`
   to the examples.
7. **§5a** — add rows: `SOLR_INDEX_URL_ALLOWED_HOSTS` (default
   `raw.githubusercontent.com,*.githubusercontent.com,github.com`; "which hosts
   `index-url` may fetch; `*` widens the boundary to the server's whole network");
   `SOLR_INDEX_URL_MAX_BYTES` (`10MB`; "caps one fetch"); `SOLR_INDEX_URL_READ_TIMEOUT`
   (`30s`; "bounds how long a remote endpoint can hold a tool call open"). The connect
   timeout is operational and is not listed.
8. **§5a "How HTTP mode enforces auth"** — "all 11 tools" becomes "all 13 tools".
   `main` at `b4ffe18` has 12 `@McpTool` methods (`git grep -c "@McpTool("` summed
   over `src/main/java`); the document's "11" was already stale. This PR adds one.
9. **§1** — "It exposes eleven tools (search, three indexing formats, …)" becomes
   "It exposes thirteen tools (search, four inline indexing formats, URL ingestion,
   collection create/list/stats/health, schema get/add-fields/add-field-types)"; the
   §2 component table's "Write/index tools" row becomes
   `index-json/csv/xml/markdown-documents`, `index-url` with "writes backend Solr
   index; `index-url` also makes an outbound GET to an allow-listed host" in the
   "Touches outside process?" column.

---

## 8. Delivery and definition of done

**One PR** from `feat/index-url` (fork) against `apache/solr-mcp` `main`, opened as a
**draft** first so the threat-model changes can be reviewed while the rest is
finished. The description cites #208 (design), #194 (why there is no `index-file`),
#197 (why one tool), and #205 (no conflict: this PR adds no parser code and touches
only the four inline tool methods to extract `indexPayload`). It states the two
D12 defaults as the points needing maintainer confirmation.

**Implementation order:** `IndexFormats` + test → `UrlTargetPolicy` + test →
`UrlIndexingProperties` and `SolrConfig` registration → `UrlFetcher` + test →
`IndexingService.indexPayload` refactor (run `IndexingServiceTest` unmodified) →
`UrlIndexingService` + unit test → `McpToolRegistrationTest` lists → integration test →
client tests → §6 → §7 → §8 checks. Each step is test-first: write the test, run it
and see it fail for the expected reason, then implement.

**Done means all of the following, run serially with JDK 25 on `JAVA_HOME`, with the
outputs pasted in the PR:**

```bash
./gradlew spotlessApply
./gradlew build                                   # rat, spotlessCheck, buildSrc tests, all tests
grep -L 'skipped="0"' build/test-results/test/*.xml
#   must print exactly one file: TEST-...OtlpExportIntegrationTest.xml, which is
#   @Disabled on main since 375a710 and is not this PR's concern
./gradlew nativeTest -Pnative                     # GraalVM JDK 25 on JAVA_HOME
grep -B1 '<skipped' build/test-results/test-native/TEST-junit-jupiter.xml \
  | grep -o 'classname="[^"]*"' | sort -u
#   every class listed must carry @DisabledInNativeImage or @Disabled in its source;
#   among the new classes only UrlIndexingServiceTest may appear
```

plus one manual STDIO run against
`https://raw.githubusercontent.com/apache/solr-mcp/main/src/test/resources/shows.json`
with Solr from `docker compose up -d`, pasting the tool result into the PR.

---

## 9. What this replaces, and where it is

An earlier design (issue #208 as first filed; branch `wip/index-url-streaming` @
`6c9b6f4` on the author's fork, fully green under `build` and `nativeTest -Pnative`)
streamed the body through server-side parsers with a hand-written idle watchdog and a
byte-cap stream, kept a STDIO-only `index-file` tool, and allowed any host by default.
It was dropped for three reasons, in order of weight:

1. It duplicated parsing that Solr's `/update` handlers already do, and the streaming
   spine lived in `CsvDocumentCreator` and `XmlDocumentCreator`, which open PR #205
   deletes in favour of forwarding to those handlers.
2. Keeping `index-file` reversed the position recorded when PR #194 was closed, for a
   case (a local file too large to paste) that `bin/solr post` serves better and that
   also works for the HTTP transport.
3. Allowing any host by default made every HTTP deployment an SSRF primitive on day
   one, and the mitigations (watchdog, cap, eight threat-model edits) were defending a
   design that a default allow-list makes unnecessary.

Of that work, the following carries over almost verbatim into this spec: the policy
checks other than the allow-list, the format table, most error messages, the client-test
parity assertions, the `@Autowired` finding, the observation that `PipedInputStream`
cannot serve as a stall fixture, and about half of the threat-model text.

`feat/file-ingest-required-params` is unchanged and remains parked as it was after
#194. Commit `318e086` on it (explicit `required` on `@McpToolParam`/`@McpArg`) is
independent housekeeping that can be its own PR at any time.

---

## 10. Phase 2 (not in this PR): unlimited size by proxying to Solr

After #205 merges, `index-url` can keep its exact contract and, for `json`, `csv` and
`xml`, stream the fetched body straight into Solr's `/update` handlers through a
`ContentStreamUpdateRequest` instead of parsing it, removing the size cap for those
formats (Markdown stays parsed and capped). The trade-off is that JSON loses the
server's field-name sanitising and nested-object flattening, which #205 already
accepted for CSV and XML. Everything in §4.3 up to and including the size check on
`Content-Length` stays; step 5.9 becomes "hand the stream to Solr". File a new issue
for it when #205 merges; do not implement it here.
