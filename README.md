# JWKS Service

Spring Boot 4.1.2 / Java 17 service that independently loads configured RSA keys
and publishes their public components at GET /.well-known/jwks.json.

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

## JWKS compatibility

`GET /.well-known/jwks.json` returns the RFC 7517 shape `{"keys":[...]}` directly,
without APIResponse or metadata. All configured public keys are published, including
inactive keys retained for rotation. Private key material is never returned.
The endpoint permits anonymous GET requests and uses `Cache-Control: max-age=300, public`.
Swagger documentation remains available at `/swagger-ui.html`.

## RSA configuration in Docker

The root Compose `jwks-api` service passes these variables from the root `.env`:

```dotenv
APP_SECURITY_JWKS_KEYS_0_KID=user-rsa-v1
APP_SECURITY_JWKS_KEYS_0_PRIVATE_KEY_PATH=/run/keys/jwt/userservice-private-v1.pem
APP_SECURITY_JWKS_KEYS_0_PUBLIC_KEY_PATH=/run/keys/jwt/userservice-public-v1.pem
APP_SECURITY_JWKS_KEYS_0_ACTIVE=false
APP_SECURITY_JWKS_KEYS_1_KID=user-rsa-v2
APP_SECURITY_JWKS_KEYS_1_PRIVATE_KEY_PATH=/run/keys/jwt/userservice-private-v2.pem
APP_SECURITY_JWKS_KEYS_1_PUBLIC_KEY_PATH=/run/keys/jwt/userservice-public-v2.pem
APP_SECURITY_JWKS_KEYS_1_ACTIVE=true
```

These are example values; no key IDs, paths, active flags, or list size are hardcoded
in the application. Spring Boot 4.1.2 binds these exact names to
`RsaKeyProperties.keys[0]` and `keys[1]`, including `privateKeyPath` and `publicKeyPath`.
Properties are registered by `@EnableConfigurationProperties` on `RsaKeyProvider`.
Use contiguous indices starting at zero. To configure more keys, pass the same four
variables with the next index through Compose as well as defining them in `.env`.

Compose mounts the root `./key` directory at `/run/keys/jwt:ro`. Place the configured
PKCS#8 PEM private keys and X.509 PEM public keys there; filenames must match the
container paths above, and files must be readable by container UID/GID 10001.
Never commit private key files. The service reads files locally and does not call
User Service. Sharing the existing read-only directory does not migrate key ownership.

Startup fails for an empty key list, blank key IDs or paths, unreadable/invalid key
files, mismatched RSA pairs, duplicate IDs, or anything other than one active key.
The User Service configuration and JWKS endpoint remain independent and unchanged.

From the root directory, validate with `docker compose config --quiet`, then build
and start only this service with `docker compose up -d --build --no-deps jwks-api`.
Check `http://127.0.0.1:8783/.well-known/jwks.json` (or the configured
`JWKS_API_HOST_PORT`).

## Tests

Run `mvn -B -ntp test` (or `mvnw.cmd -B -ntp test`).
Tests cover factories, Jackson serialization, exception status mapping,
validation, MVC errors, security pass-through, metadata, raw response
preservation, and existing structured logging. Test controllers are fixtures only;
no external services or RSA private keys are used.