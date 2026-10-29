# JWKS logging

The service uses SLF4J, Logback and logstash-logback-encoder 8.1, matching User Service.
Both appenders use the same JSON encoder and MDC allowlist.

## Configuration

| Variable | Local default | Docker Compose value |
| --- | --- | --- |
| APP_LOG_DIR | ./logs | /app/data/logs |
| APP_NAME | jwks-api | jwks-api |
| LOG_LEVEL | INFO | JWKS_API_LOG_LEVEL from root .env, default INFO |

Spring Boot does not automatically load a local .env. For local runs, supply these
as process environment variables or JVM system properties. No root .env changes
are required. To change only this service's Compose log level, optionally add
`JWKS_API_LOG_LEVEL=DEBUG`.

The service label comes from `spring.application.name` (currently `jwks-service`).
`APP_NAME` controls the file basename, not the application identity.

## Request logging

`logging.filter.RequestLoggingFilter` owns correlation and completion logging.
Incoming `X-Correlation-ID` values are accepted only when there is a single header,
1-64 characters long, beginning with an ASCII letter/digit and containing only
ASCII letters, digits, dot, underscore or hyphen. Otherwise a UUID is generated.
The resulting ID is returned in the response header and added to MDC.

Completion events use the existing fields `httpMethod`, `uri`, `status` and
`elapsed_ms`. The `uri` field contains the matched route template, or `UNMATCHED`;
raw paths and query strings are not logged. Duration uses a monotonic clock.
2xx/3xx responses use INFO, 4xx use WARN, and 5xx/unexpected failures use ERROR.
An uncommitted escaping exception is recorded as 500; a committed response retains
its actual status. Exceptions are rethrown to preserve existing error handling.

Asynchronous servlet completion is logged once. Correlation context is restored on
async dispatch/completion, then cleaned. This does not install MDC propagation into
arbitrary application-created executor tasks.

Trace/span IDs are included when supplied by existing instrumentation through MDC.
No tracing SDK or external exporter is added, and incoming trace headers are not
treated as trusted log metadata.

## Sensitive data and exceptions

The request filter does not read bodies, credentials, cookies or authorization
headers. Unknown MDC keys are excluded from JSON output. Keep application log
messages fixed or restricted to approved safe metadata.

`logging.SafeThrowableConverter` retains exception types, stack frames, suppressed
exceptions and cause chains, but deliberately omits exception messages because they
can contain credentials or key material. Cause traversal is bounded and cycle-safe.
The starter's generated development-password logger is disabled without changing
authentication behavior.

There are currently no JWKS business operations or global exception handlers.
This implementation adds no endpoints or authentication changes.

## Files and retention

Active: `${APP_LOG_DIR}/${APP_NAME}.log`

Archives: `${APP_LOG_DIR}/archive/${APP_NAME}.yyyy-MM-dd.i.log.gz`

Logback rotates daily and at 100 MB, retaining 30 daily periods with a 2 GB archive
cap. Cleanup runs during rollover and at startup. The cap applies to archives,
not the active file. Compose preserves files in the existing `jwks_api_logs` volume.
No log shipping is configured by this change.

## Validation

Run `mvnw.cmd -B -ntp test` from this submodule. Tests cover request metadata,
asynchronous completion, sensitive data exclusion, JSON encoding, actual HTTP
readiness, console/file output and loaded rolling-policy settings. They do not
simulate daily rollover, gzip creation or 30-day expiry.