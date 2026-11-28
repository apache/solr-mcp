# Logging

Why logging is set up the way it is, and why stdout must stay clean. `AGENTS.md` keeps the
rules; this file has the reasoning behind them.

The STDIO transport uses stdout for JSON-RPC messages, so any stray stdout output
corrupts the protocol. The setup follows Spring Boot's conventions: **one** logback
configuration, `logback-spring.xml`, resolved by Boot by convention, plus one line of
code in `Main` for the window before Boot exists.

**Why the `-spring` name and nothing else.** Boot's rule: `<springProfile>` "cannot be
used in the standard `logback.xml` file because it is loaded too early." A standard-
location file is worse than useless here: `AbstractLoggingSystem.initializeWithConventions()`
finds it first, reinitializes from it and **returns**, so the `-spring` variant is never
loaded and every `<springProfile>` appender is silently dropped. HTTP mode would run with
no console logs and no OTLP log export, and startup failures would exit 1 showing only
the banner. `LoggingConfigurationTest` fails the build if a `logback.xml` (or
`logback-test.xml`) ever reappears, or if `logging.config` is set in
`application.properties` to paper over one. `LOGGING_CONFIG` in the environment still
works as Boot's normal operator override for an *external* file.

**Why `Main` sets `logback.statusListenerClass`.** Logback initializes itself on the
first `LoggerFactory` touch, before Boot's `LoggingApplicationListener` runs. In a
**native image** it cannot read its own manifest, so `ContextInitializer.checkVersions()`
always raises `|-WARN … Versions of logback-classic and ? are different or unknown`, and
`LogbackServiceProvider` then calls `StatusPrinter.printInCaseOfErrorsOrWarnings()`,
flushing the whole `|-INFO` status list to **stdout** — in the middle of the MCP
JSON-RPC stream. That provider skips the print whenever a status listener is installed,
and `ContextInitializer.autoConfig()` installs one from the `logback.statusListenerClass`
system property *after* the version check but *before* the print. `Main.main()` therefore
sets that property to `NopStatusListener` as its first statement, unless an operator has
already set it (so `-Dlogback.statusListenerClass=ch.qos.logback.core.status.OnConsoleStatusListener`
still works for debugging logback itself). On the JVM the version lookup succeeds and
nothing is printed, so this is reproducible *only* in the native image:
`DockerImageMcpClientStdioIntegrationTest` under `./gradlew dockerIntegrationTest
-Pnative` is the sole test that covers it end to end.

Contents of `logback-spring.xml`:

- A `NopStatusListener` suppressing logback's internal status messages during Boot's
  own (re)configuration, which are written straight to stdout and bypass the appenders.
- `<springProfile>` blocks scoping appenders per transport mode:
  - **HTTP**: Boot's own `console-appender.xml` (so `logging.pattern.console` /
    `logging.charset.console` / `logging.threshold.console` behave as in a stock Boot
    app) + OpenTelemetry appender (OTLP log export with `captureExperimentalAttributes`
    and `captureKeyValuePairAttributes` enabled).
  - **STDIO**: No appenders defined, so nothing can reach stdout. The OTEL appender is
    intentionally excluded too.
- `application-stdio.properties` must **not** set `logging.pattern.console=` (the
  empty-pattern idiom Spring AI documents for STDIO servers). Boot copies it into the
  JVM-wide `CONSOLE_LOG_PATTERN` system property (first writer wins) and logback rejects
  an empty pattern (`Empty or null pattern`) instead of silencing output. Spring
  Framework 7 pauses a test's ApplicationContext on context switch, which stops Boot's
  logging lifecycle bean and makes the next context re-initialise logback, so a
  stdio-profile test running first would break every later http-profile context in the
  same JVM (`DistributedTracingTest`). `LoggingConfigurationTest` enforces this.

Under AOT, `LogbackLoggingSystem` replays `META-INF/spring/logback-model` — the model
`processAot` serialized from `logback-spring.xml` — before looking at the classpath.
Verify with:

```bash
strings build/resources/aot/META-INF/spring/logback-model | grep -E 'SpringProfile|OpenTelemetry'
```

`SpringProfileModel` is serialized unresolved, so profiles are still evaluated at runtime;
the `logback-spring.xml` resource hint in `SolrNativeHints` is belt-and-braces for the
non-AOT path.

**Init order**: `Main` sets `logback.statusListenerClass` → first logger touch (logback
self-init, silent) → Spring Boot starts → logback-spring.xml → application-{profile}.properties

**Debugging tip**: if an HTTP-mode startup fails with no output, check that no
`logback.xml` has crept onto the classpath; `LoggingConfigurationTest` should already
have caught it.
