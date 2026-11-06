# JWKS Service

Spring Boot 4.1.2 / Java 17 service that independently loads configured RSA public keys
and publishes their public components at GET /api/v1/jwks/user.

## API responses

Ordinary controllers opt in with `APIResponse.success(code, message, data)`.
The factory does not choose an HTTP status; controllers use `ResponseEntity`
for the appropriate success status (normally 200, or 201 for creation).
Do not attach a response body to 204 responses.

```json
{"success":true,"data":{"ready":true},"meta":{"timestamp":"2026-10-29T04:00:00Z","correlationId":"request-123","traceId":"trace-123"}}
```

Errors use `APIResponse.failure` and the centralized exception handler:

```json
{"success":false,"code":"RESOURCE_NOT_FOUND","message":"Resource not found","meta":{"timestamp":"2026-10-29T04:00:00Z","correlationId":"request-123"}}
```

Validation adds field details:

```json
{"success":false,"code":"VALIDATION_ERROR","message":"Request validation failed","error":{"details":[{"field":"name","code":"INVALID_FIELD","message":"Invalid value"}]},"meta":{"timestamp":"2026-10-29T04:00:00Z","correlationId":"request-123"}}
```

Unexpected errors return a safe message:

```json
{"success":false,"code":"INTERNAL_SERVER_ERROR","message":"An unexpected error occurred","meta":{"timestamp":"2026-10-29T04:00:00Z","correlationId":"request-123"}}
```

These are illustrative payloads, not new endpoints. Null fields are omitted by
annotations on the response records, compatible with Boot's Jackson 3 mapper.
The JSON names and factory signatures match User Service. Timestamp is UTC.
Correlation and trace IDs are copied from MDC when available; no extra IDs are
generated. The existing filter validates/reuses `X-Correlation-ID` and returns it
in the response header. Trace IDs require existing instrumentation.

## Error handling

| Condition | HTTP status | Code |
| --- | --- | --- |
| Missing/invalid parameters or unreadable JSON | 400 | INVALID_REQUEST |
| Body, parameter or constraint validation | 400 | VALIDATION_ERROR |
| Missing route/resource | 404 | RESOURCE_NOT_FOUND |
| Unsupported HTTP method | 405 | METHOD_NOT_ALLOWED |
| Unacceptable response format | 406 | NOT_ACCEPTABLE |
| Unsupported request content type | 415 | UNSUPPORTED_MEDIA_TYPE |
| Unexpected failure or return-value validation failure | 500 | INTERNAL_SERVER_ERROR |

`BusinessException` uses its `ErrorCode` status and message and can retain a cause.
MVC exceptions retain Spring's status and protocol headers, including `Allow`.
Other framework 4xx/5xx statuses remain intact and use INVALID_REQUEST /
INTERNAL_SERVER_ERROR respectively. If the client excludes JSON entirely, a 406
response may have no JSON body. Committed responses are not rewritten.
Authentication and authorization remain owned by Spring Security.

Validation messages deliberately use "Invalid value" instead of interpolated
validator messages or rejected values. Exception messages, internal paths and
stack traces are never returned. See [logging](LOGGING.md): the request filter
logs 4xx at WARN and 5xx at ERROR, with one completion event carrying safe server
diagnostics. The advice records handled server exceptions for that event.
A fallback ERROR log is used only when no request logging state exists.

## JWKS contract and ownership

`GET /api/v1/jwks/{owner}` accepts `user` and `learning` (lowercase).
Each response is standard unwrapped `{"keys":[...]}` with RSA fields
`kty`, `use`, `alg`, `kid`, `n`, and `e`; owner and private material are never returned.
Only keys explicitly configured for the requested owner are published. Unknown or missing
owners return controlled 404 responses; empty configured groups return an empty key set.
Responses are publicly cached for five minutes. There are no legacy compatibility endpoints.

## Public-key configuration

Compose reads `.env.jwks-api`. Copy the root `.env.jwks-api.example` on a fresh checkout.
Use contiguous indices and globally unique key IDs. Example:

```dotenv
APP_SECURITY_JWKS_KEYS_0_KID=user-rsa-v1
APP_SECURITY_JWKS_KEYS_0_PUBLICKEYPATH=/run/keys/jwt/userservice-public-v1.pem
APP_SECURITY_JWKS_KEYS_0_OWNER=USER
APP_SECURITY_JWKS_KEYS_1_KID=user-rsa-v2
APP_SECURITY_JWKS_KEYS_1_PUBLICKEYPATH=/run/keys/jwt/userservice-public-v2.pem
APP_SECURITY_JWKS_KEYS_1_OWNER=USER
APP_SECURITY_JWKS_KEYS_2_KID=learning-rsa-v1
APP_SECURITY_JWKS_KEYS_2_PUBLICKEYPATH=/run/keys/learning/learningservice-public-v1.pem
APP_SECURITY_JWKS_KEYS_2_OWNER=LEARNING
```

`PUBLICKEYPATH` is Spring's canonical environment spelling for `publicKeyPath`; underscores
between those words create a different property. Missing/invalid owners, invalid RSA public
PEMs, missing files and duplicate IDs fail startup. Ownership is trusted deployment configuration,
not inferred from names or token claims. JWKS has no active signing key and imposes no active count.
User and Learning independently require exactly one active private signing key each.

The root Compose mounts only three individual public PEM files, read-only, with host-path
creation disabled. No private key or signing directory is mounted in JWKS. The existing host
files stay in `key/` and `data/keys/learning/`; no copying or key regeneration is required.
Future rotation needs another public file mount and indexed entry; retain old public keys
until all issued tokens and applicable caches have expired.

User and Learning's user-token decoders trust `/api/v1/jwks/user`. User's internal decoder
trusts only `/api/v1/jwks/learning`, with its own issuer/audience/type/time rules. Key discovery
is not authorization. No decoder chooses its key source from unverified token claims.

```cmd
docker compose config --quiet
docker compose -p springboot_project up -d --build --no-deps jwks-api
curl.exe http://localhost:8783/api/v1/jwks/user
curl.exe http://localhost:8783/api/v1/jwks/learning
```

## Tests

Run `mvn -B -ntp test` (or `mvnw.cmd -B -ntp test`).
Tests cover factories, Jackson serialization, exception status mapping,
validation, MVC errors, security pass-through, metadata, raw response
preservation, and existing structured logging. Test controllers are fixtures only;
no external services or RSA private keys are used.
