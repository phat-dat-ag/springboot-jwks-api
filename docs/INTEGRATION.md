# Integrating JWKS Service as a Git Submodule

This guide explains how to deploy `springboot-jwks-api` from another root repository and connect JWT consumers to it. The surrounding Spring Boot project is a reference implementation, not a required application structure. Commands run from the **new root repository** unless stated otherwise.

## 1. Implementation and version boundary

The implementation inspected for this guide is commit `c09d2a57ffa4c89e1b6897dc46499930069291fc`, which includes the public-key-only loader. It uses Spring Boot **4.1.2**, Java **17**, Spring MVC, Spring Security, Actuator, Lombok, Springdoc **3.2.0**, and Logstash Logback Encoder **8.1**. The Maven wrapper selects Maven **3.10.0**.

At inspection time, the reference root's committed submodule pointer still named `4ff232358a44c2d75600e76c2b04752bdf1ebbe6`, while the submodule working checkout was `c09d2a57ffa4c89e1b6897dc46499930069291fc`. A fresh checkout of the root's recorded revision therefore does not necessarily contain the public-key-only implementation. Review and commit the intended submodule pointer explicitly.

Older revisions loaded private/public pairs. The reference root `.env.jwks-api.example` still describes those older requirements and contains `APP_SECURITY_JWKS_KEYS_<n>_PRIVATE_KEY_PATH`. The inspected implementation has no such property. Use the public-only examples below and the submodule's own `.env.example`; do not restore private-key access to satisfy an obsolete template. Some existing README integration statements also predate the root's separate service env files.

### Source of truth

| Concern | Implementation in this submodule |
| --- | --- |
| Endpoint and cache header | [JwksController](../src/main/java/com/japaneselearning/jwks/controller/JwksController.java) |
| JWK fields and encoding | [JwksService](../src/main/java/com/japaneselearning/jwks/service/JwksService.java), [JwkResponse](../src/main/java/com/japaneselearning/jwks/dto/response/JwkResponse.java), [JwksResponse](../src/main/java/com/japaneselearning/jwks/dto/response/JwksResponse.java) |
| Key configuration and startup validation | [RsaKeyProperties](../src/main/java/com/japaneselearning/jwks/config/RsaKeyProperties.java), [RsaKeyProvider](../src/main/java/com/japaneselearning/jwks/security/RsaKeyProvider.java) |
| HTTP access rules | [SecurityConfig](../src/main/java/com/japaneselearning/jwks/config/SecurityConfig.java) |
| Application settings and defaults | [application.properties](../src/main/resources/application.properties), [.env.example](../.env.example) |
| Build, runtime, and build-context exclusions | [pom.xml](../pom.xml), [Dockerfile](../Dockerfile), [.dockerignore](../.dockerignore) |
| Logging | [logback-spring.xml](../src/main/resources/logback-spring.xml), [LOGGING.md](../LOGGING.md) |
| Public-only loading and rotation tests | [RsaKeyProviderTest](../src/test/java/com/japaneselearning/jwks/security/RsaKeyProviderTest.java) |

## 2. Service contract and responsibilities

The service loads configured RSA public-key files once during application startup, stores an immutable in-memory map, and publishes their public components. It does not generate keys, sign tokens, issue tokens, verify incoming application JWTs, register users, maintain a database, or contact the signing service.

### HTTP contract

| Property | Verified behavior |
| --- | --- |
| Method and path | `GET /.well-known/jwks.json` |
| Authentication | Anonymous GET is permitted; no bearer token or Basic credentials required |
| Success | HTTP 200 with a JSON object containing a top-level `keys` array |
| Caching | `Cache-Control: max-age=300, public` |
| JWK type, use, algorithm | `kty=RSA`, `use=sig`, `alg=RS256` |
| Key selector | Configured nonblank `kid`, unique within this service instance |
| Key components | `n` and `e`: unsigned RSA modulus/exponent, Base64url without padding |
| Published entries | All configured keys, including entries with `active=false` |
| Private components | Never loaded or returned by the inspected revision |

Structural example only; the placeholder modulus is not usable key material:

```json
{
  "keys": [
    {
      "kty": "RSA",
      "use": "sig",
      "alg": "RS256",
      "kid": "user-rsa-v1",
      "n": "BASE64URL_UNSIGNED_RSA_MODULUS",
      "e": "AQAB"
    }
  ]
}
```

`e` is derived from each loaded key; `AQAB` is an example, not a hardcoded exponent. There is no `success`, `data`, or `meta` envelope on the JWKS response. Do not add an application response envelope at a gateway.

There is no issuer-discovery document, token endpoint, rotation API, or reload API in the inspected source. Do not configure a consumer to discover OpenID Connect metadata from this service. The `RS256` declaration is fixed in `JwksService`; no environment variable selects a different algorithm.

### Key loading rules

Configuration binds directly to `app.security.jwks.keys`, using contiguous indices starting at zero. Startup fails for an empty list, blank IDs, blank public paths, duplicate IDs, unreadable or malformed files, non-RSA public keys, or an active-key count other than one.

Files must contain X.509 **SubjectPublicKeyInfo** PEM (`-----BEGIN PUBLIC KEY-----`). A certificate (`BEGIN CERTIFICATE`), PKCS#1 RSA public key (`BEGIN RSA PUBLIC KEY`), SSH public key, JWK JSON file, or private-key PEM is not the expected input. The loader uses `X509EncodedKeySpec` and `KeyFactory.getInstance("RSA")`.

`active` defaults to Java boolean `false` for each entry, but **exactly one entry must be true for startup to succeed**. It does not hide inactive keys and is not returned in the JWKS. JWKS itself does not sign anything. Files and flags are not watched after startup; restart/recreate the service to reload them.

## 3. Reference root integration

The current root contains one `docker-compose.yml`, with `jwks-api`, `user-api`, `learning-api`, databases, Flyway runners, and optional operational tooling around them.

| Setting | Reference root behavior |
| --- | --- |
| Submodule repository | `https://github.com/phat-dat-ag/springboot-jwks-api.git` |
| Build context | `./springboot-jwks-api`, using its `Dockerfile` |
| Container listener | `0.0.0.0:8080` |
| Host publication | Loopback only: `127.0.0.1:${JWKS_API_HOST_PORT:-8783}:8080` |
| Key env file | Root `./.env.jwks-api` supplied through `env_file` |
| Key mount | `${JWKS_KEYS_DIR:-./key}` to `/run/keys/jwt`, read-only |
| Log mount | Named volume `jwks_api_logs` at `/app/data/logs` |
| Network | Implicit Compose default network; no explicit networks or fixed container names |
| JWKS dependencies | No `depends_on`; no database, Flyway, broker, cache, or observability service required |
| Health | GET `/actuator/health/readiness` inside the container; probes explicitly enabled |
| Runtime limits | UID/GID 10001, read-only root filesystem, dropped capabilities, no-new-privileges, writable `/tmp`, 1 GiB memory, 2 CPUs, PID limit 256 |

The signing `user-api` reads private-key paths from `.env.user-api` and mounts `${USER_SIGNING_KEYS_DIR:-./key}` at `/run/keys/jwt`. JWKS reads public-key paths from its own env file. The reference defaults can mount the same host directory into both containers. That is a deployment convenience, not a requirement; a read-only directory still exposes every readable private file it contains. New deployments should give JWKS a public-only directory or individual public-file mounts.

Both consumers use `http://jwks-api:8080/.well-known/jwks.json` by default. The root maps `USER_JWT_JWK_SET_URI` and `LEARNING_JWT_JWK_SET_URI` to each container's `JWT_JWK_SET_URI`. `JWT_ISSUER` is required in the root and is passed to both consumers, not to JWKS. Learning also has a separate diagnostic client using `JWKS_SERVICE_BASE_URL` and `JWKS_SERVICE_GET_PUBLIC_KEYS`; these are not substitutes for the decoder's JWKS URI.

The root APIs depend on their own database/Flyway runner, but neither currently declares a JWKS startup dependency. Do not infer that API readiness proves JWKS connectivity or successful JWT verification. Fluent Bit and other log tooling consume JWKS logs but are not required for JWKS to serve requests.

## 4. Add and pin the Git submodule

Prerequisites: access to the source repository, Git, Docker Engine/Desktop with Compose v2, and outbound access for the build. OpenSSL is needed only for the optional key preparation commands. Host Java/Maven is not required for a Docker build; local Maven tests require a Java 17 JDK.

For a new integration:

```sh
git switch -c feat/integrate-jwks
git submodule add https://github.com/phat-dat-ag/springboot-jwks-api.git springboot-jwks-api
git -C springboot-jwks-api fetch origin --tags
git -C springboot-jwks-api checkout --detach c09d2a57ffa4c89e1b6897dc46499930069291fc
git -C springboot-jwks-api submodule update --init --recursive
git add .gitmodules springboot-jwks-api
```

The commit above is the inspected reference revision, not a promise that it is the latest release. Select another reviewed commit/tag when appropriate and recheck this contract. The root records an exact commit; its submodule declaration does not automatically follow a branch.

For an existing root checkout:

```sh
git submodule sync --recursive
git submodule update --init --recursive
git submodule status springboot-jwks-api
git -C springboot-jwks-api rev-parse HEAD
```

CI must initialize submodules before building. Git credentials and network access to this repository are CI requirements. Keep runtime configuration and keys in the root's deployment/secret-management system, outside the submodule. Do not copy the reference project's databases or other APIs merely to run JWKS.

## 5. Prepare and configure public keys

The following filenames and IDs are **examples**, using the same path convention as the reference project. They must match the actual signer and the files you provision. Neither the path `/run/keys/jwt` nor these filenames is hardcoded in the loader.

Prefer exporting the existing signer's public key through your organization's key-management process. JWKS needs only that public export. For development, these OpenSSL commands can generate an example signer key and export its public half. They are POSIX-shell commands; keep the private key outside the JWKS mount and never run them over an existing production key.

```sh
mkdir -p signer-keys key
umask 077
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out signer-keys/userservice-private-v1.pem
openssl pkey -in signer-keys/userservice-private-v1.pem -pubout -out key/userservice-public-v1.pem
openssl pkey -pubin -in key/userservice-public-v1.pem -noout -pubcheck
```

For an existing private key, run only the public export and validation commands using its real location. The `signer-keys` directory is an example deployment choice, not a directory read by JWKS. Provision RSA keys of at least 2048 bits for RS256; the loader itself does not enforce a key-size floor. [RFC 7518, section 3.3](https://www.rfc-editor.org/rfc/rfc7518.html#section-3.3) specifies that minimum for these RSA signatures.

The public export must be readable by container UID/GID **10001:10001**. On a Linux deployment host, give that identity read/traverse access to the public directory and files. One option for the example public-only directory is:

```sh
sudo chown 10001:10001 key key/userservice-public-v1.pem
sudo chmod 0750 key
sudo chmod 0640 key/userservice-public-v1.pem
```

Adapt permissions to rootless Docker, host ACLs, or your secret manager; Windows/Docker Desktop bind permissions differ from Linux ownership. Never loosen private-key permissions to fix a JWKS read failure. Public-key integrity is security-sensitive even though the contents are public: restrict who can replace them.

Create root `.env.jwks-api`:

```dotenv
# Required key 0: replace the ID/path to match the signer and mounted public file.
APP_SECURITY_JWKS_KEYS_0_KID=user-rsa-v1
APP_SECURITY_JWKS_KEYS_0_PUBLIC_KEY_PATH=/run/keys/jwt/userservice-public-v1.pem
APP_SECURITY_JWKS_KEYS_0_ACTIVE=true

# Optional retained/next key: uncomment all fields only when its file is present.
# APP_SECURITY_JWKS_KEYS_1_KID=user-rsa-v2
# APP_SECURITY_JWKS_KEYS_1_PUBLIC_KEY_PATH=/run/keys/jwt/userservice-public-v2.pem
# APP_SECURITY_JWKS_KEYS_1_ACTIVE=false
```

There is no `PRIVATE_KEY_PATH` entry for JWKS at the inspected revision. A signer retains its own private-key configuration, for example the reference User Service's `APP_SECURITY_SIGNING_KEYS_<n>_*` variables. The two services coordinate **key IDs and matching cryptographic material**, not matching host filenames. Since JWKS no longer receives private keys, it cannot check that its public key corresponds to the signer's private key; verify that during provisioning and with an end-to-end token test.

Create root `.env.jwks-api.example` with blank required IDs/paths and clear active-flag guidance. Example values above are not configured production values. Add appropriate ignores to the new root, for example:

```gitignore
.env
.env.*
!.env.example
!.env.*.example
key/
signer-keys/
```

These patterns are example root changes; the submodule's own ignore rules do not protect files elsewhere in the root. Keep keys outside Docker build contexts. The submodule `.dockerignore` allows only Maven build inputs and main source; it excludes env files, key material, tests, generated files, and this documentation.

## 6. Environment variables and precedence

### Required key configuration inside the JWKS process

| Variable | Required/default | Meaning |
| --- | --- | --- |
| `APP_SECURITY_JWKS_KEYS_<n>_KID` | Required per entry; no default | Nonblank unique key ID, matching the signer's JWT header |
| `APP_SECURITY_JWKS_KEYS_<n>_PUBLIC_KEY_PATH` | Required per entry; no default | Readable RSA SubjectPublicKeyInfo PEM path **inside** the container |
| `APP_SECURITY_JWKS_KEYS_<n>_ACTIVE` | Optional per entry; `false` | Boolean; exactly one configured entry must be `true` overall |

### Optional JWKS process settings

| Variable | Default and behavior |
| --- | --- |
| `SPRING_APPLICATION_NAME` | `jwks-service`; structured JSON log `service` label |
| `SERVER_ADDRESS` | Dockerfile sets `0.0.0.0`; outside Docker, Boot binds all interfaces when unset |
| `SERVER_PORT` | `8080`; keep port publication and healthcheck aligned if changed |
| `APP_LOG_DIR` | Logback fallback `./logs`; Dockerfile/root Compose set `/app/data/logs`; must be writable |
| `APP_NAME` | `jwks-api`; log filename basename, without directory or extension; not `spring.application.name` |
| `LOG_LEVEL` | `INFO`; root logging level |
| `LOGGING_LEVEL_COM_JAPANESELEARNING_JWKS` | Falls back to `LOG_LEVEL`, then `INFO`; application package override |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `none`; keep unless trusted reverse-proxy behavior is deliberately configured |
| `MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE` | `health`; comma-separated Actuator endpoint IDs |
| `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED` | Boot's environment-dependent detection when absent; set `true` for the documented Compose readiness probe |

Spring Boot can bind other standard settings, but the table documents settings explicitly used in the inspected deployment/source. No database credentials, `JWT_ISSUER`, JWT TTL, caller credentials, or private keys are required by JWKS.

### Optional root Compose inputs

The example in the next section uses the existing reference root variable names. These are **Compose inputs**, not automatically Spring application properties:

```dotenv
# Root .env: optional defaults used by this guide's Compose service.
JWKS_API_HOST_PORT=8783
JWKS_API_APP_NAME=jwks-api
JWKS_API_LOG_LEVEL=INFO
JWKS_KEYS_DIR=./key
```

`JWKS_API_APP_NAME` maps to container `APP_NAME`; `JWKS_API_LOG_LEVEL` maps to container `LOG_LEVEL`; `JWKS_KEYS_DIR` chooses a host bind source. Put the key list in `.env.jwks-api`, not solely in root `.env`.

Compose uses root `.env`/`--env-file` and shell settings for `${...}` interpolation; shell values can override root dotenv values. Root `.env` is not implicitly injected into a container. A service's `env_file` supplies container variables, and its explicit `environment` values take precedence over `env_file`; image `ENV` supplies fallback values. Spring Boot does not automatically read `.env` files. Preserve quoting/escaping and avoid sourcing dotenv files as shell scripts.

## 7. Configure Docker Compose

This is a **standalone integration example** for a new root, based on the reference service. It uses a long-form, fail-if-missing bind mount and requires that the mounted host directory contain **public files only**. The reference root currently uses a short-form directory mount. Do not duplicate an existing `services` or `volumes` mapping when merging the example into another Compose file.

```yaml
services:
  jwks-api:
    build:
      context: ./springboot-jwks-api
      dockerfile: Dockerfile
    restart: unless-stopped
    init: true
    user: "10001:10001"
    read_only: true
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    ulimits:
      core: 0
    mem_limit: 1g
    memswap_limit: 1g
    cpus: 2
    pids_limit: 256
    stop_grace_period: 45s
    env_file:
      - ./.env.jwks-api
    environment:
      SERVER_ADDRESS: 0.0.0.0
      SERVER_PORT: 8080
      APP_LOG_DIR: /app/data/logs
      APP_NAME: ${JWKS_API_APP_NAME:-jwks-api}
      LOG_LEVEL: ${JWKS_API_LOG_LEVEL:-INFO}
      MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE: health
      MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED: "true"
    tmpfs:
      - /tmp:rw,nosuid,nodev,size=256m,mode=1777
    volumes:
      - jwks_api_logs:/app/data/logs
      - type: bind
        source: ${JWKS_KEYS_DIR:-./key}
        target: /run/keys/jwt
        read_only: true
        bind:
          create_host_path: false
    ports:
      - "127.0.0.1:${JWKS_API_HOST_PORT:-8783}:8080"
    healthcheck:
      test: ["CMD", "curl", "--fail", "--silent", "--output", "/dev/null", "--max-time", "5", "http://127.0.0.1:8080/actuator/health/readiness"]
      interval: 10s
      timeout: 6s
      retries: 12
      start_period: 30s

volumes:
  jwks_api_logs:
```

Relative bind paths are resolved against the Compose project/base-file directory. A host path in `.env` is not the path passed to `PUBLIC_KEY_PATH`: that path must exist inside the container. Keep the env file path outside the build context; do not copy it into the image.

If a shared host directory contains private files, replace the directory bind with individual public-file binds instead. Example for one configured key:

```yaml
      - type: bind
        source: ${JWKS_KEYS_DIR:-./key}/userservice-public-v1.pem
        target: /run/keys/jwt/userservice-public-v1.pem
        read_only: true
        bind:
          create_host_path: false
```

Retain the log volume and add one file bind for each configured public key. A missing bind source must be fixed by provisioning the file, not by creating a directory named after the PEM file.

### Runtime and build requirements

The Dockerfile builds with `eclipse-temurin:17-jdk-jammy`, normalizes the Unix Maven wrapper's CRLF endings, and executes `clean package -DskipTests`. Runtime uses `eclipse-temurin:17-jre-jammy`, installs `curl`, and runs `java -jar /app/app.jar` as UID/GID 10001. It exposes 8080 but does not itself publish a host port or define a Docker `HEALTHCHECK`.

Builds need Maven wrapper/dependency download access, container-registry access, and Ubuntu package repositories for `curl`; runtime does not require Maven or those download services. Docker builds skip test execution, and the build context excludes test sources. Run tests separately in CI. Base-image tags and package downloads are not digest/package-version pinned in the inspected Dockerfile; manage supply-chain pinning and image scanning in the owning delivery process.

Writable `/tmp` supports Java temporary files with a read-only root filesystem. `/app/data/logs` and its `archive` directory must be writable. A new named volume normally inherits image directory ownership; an existing volume or host bind may require deployment-specific permissions. No database, Flyway migration, Redis, OpenSearch, Loki, Grafana, or signing-service container is required to start JWKS.

## 8. Networking and JWT-consuming services

### Discovery and startup

Compose creates a project-scoped default network for the example. Consumers on that network resolve the service name `jwks-api` and call container port **8080**, regardless of the published host port. `localhost` inside a consumer refers to the consumer itself. Host applications use `127.0.0.1:8783` only when using the example publication; remote applications require a reachable, deployment-owned address.

You can omit `ports` entirely for container-only access. If using custom networks, attach JWKS and its consumers to a common network. Different Compose projects do not share their default networks; use a deliberately managed shared network/alias or an authenticated HTTPS ingress appropriate to the deployment. Avoid `container_name` and fixed global network/volume names when independent roots deploy their own instances; duplicate `jwks-api` aliases on a shared network can cause ambiguous discovery.

An optional addition to an existing consumer service is:

```yaml
    depends_on:
      jwks-api:
        condition: service_healthy
```

Merge this with existing dependencies. It gates initial startup; it does not provide runtime retries, continuous dependency supervision, or proof that consumer validation works. The reference root does not currently add this dependency.

### Reference consumers' configuration

These snippets belong under the **existing** service definitions, which must retain their image/build, database, and other application configuration:

```yaml
  user-api:
    environment:
      JWT_JWK_SET_URI: ${USER_JWT_JWK_SET_URI:-http://jwks-api:8080/.well-known/jwks.json}
      JWT_ISSUER: ${JWT_ISSUER:?Set JWT_ISSUER in .env}

  learning-api:
    environment:
      JWT_JWK_SET_URI: ${LEARNING_JWT_JWK_SET_URI:-http://jwks-api:8080/.well-known/jwks.json}
      JWT_ISSUER: ${JWT_ISSUER:?Set JWT_ISSUER in .env}
      JWT_CLOCK_SKEW: ${JWT_CLOCK_SKEW:-60s}
      JWKS_SERVICE_BASE_URL: ${JWKS_SERVICE_BASE_URL:-http://jwks-api:8080}
      JWKS_SERVICE_GET_PUBLIC_KEYS: ${JWKS_SERVICE_GET_PUBLIC_KEYS:-/.well-known/jwks.json}
```

For these reference consumers, root `.env` must include a real nonblank `JWT_ISSUER` matching the token signer. The custom variables above work because those applications map them in `application.properties` and construct their own `NimbusJwtDecoder`. They are not a universal configuration contract for every Spring Boot application.

The reference User Service explicitly accepts RS256 and installs `JwtValidators.createDefaultWithIssuer`. Learning explicitly accepts RS256, validates timestamps with `JWT_CLOCK_SKEW` (default `60s`, nonnegative), validates the issuer, and additionally requires nonblank `kid` and subject, `exp`, `iat`, a bounded future `iat`, and `exp > iat`. Neither reference decoder installs an audience validator; the reference signer also does not emit an `aud` claim. Add an audience policy and corresponding signer support in a new application when required by its trust boundaries.

For another stack, configure its JWT library to use the full JWKS URL and independently enforce the intended issuer, algorithm allowlist, timestamps, required claims, and audience policy. A JWKS document contains public keys, not an issuer policy. Successful key retrieval alone does not authenticate a user. Do not trust a token-supplied arbitrary key URL or use the JWKS service URL as the issuer unless the signer actually uses that exact issuer.

For a conventional Spring Security resource server that has no custom decoder, use its documented explicit `jwk-set-uri` configuration with issuer validation rather than relying on metadata discovery from this JWKS service. The resource-server and JOSE dependencies are needed in the consumer; adding this submodule does not add those dependencies to another application. See [Spring Security's JWT resource-server guide](https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/jwt.html), and verify the behavior of the consumer's selected library/version.

### Signer, issuer, algorithm, and `kid`

- The signer keeps its private RSA key and signs with RS256. In the reference User Service, `JwtTokenService` uses `SHA256withRSA`, emits `alg=RS256`, and sets `kid` from its active signing-key configuration.
- The matching public key is published under the same `kid`. An ID match alone is insufficient: the public key must verify signatures made by that private key.
- The issuer (`iss`) is a claim chosen by the signer and checked by each consumer. JWKS neither configures nor enforces it.
- Authorization rules and role conversion remain responsibilities of consuming applications. Possessing a valid signature does not imply permission for every endpoint.

Apply explicit algorithm and issuer/audience validation appropriate to the application; these are separate from fetching keys. [RFC 8725, sections 3.1, 3.8, and 3.9](https://www.rfc-editor.org/rfc/rfc8725.html#section-3) describe these JWT validation requirements.

## 9. Start and validate

Before starting, verify that the submodule is initialized, `.env.jwks-api` has at least one complete key entry with exactly one active entry, and each file exists with the expected ownership. Do not print private-key files or resolved environment configuration.

```sh
docker compose config --quiet
docker compose build jwks-api
docker compose up -d --no-deps jwks-api
docker compose ps jwks-api
docker compose logs --tail=100 jwks-api
```

When using the full reference root, `config --quiet` validates the entire project; unrelated required database/Grafana variables must also be configured. It does not read/parse PEM contents or prove runtime permissions. Use `--quiet` rather than sharing rendered Compose output, which may contain other services' credentials.

Check readiness and the actual endpoint inside the container:

```sh
docker compose exec -T jwks-api curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health/readiness
docker compose exec -T jwks-api curl --fail --silent --show-error http://127.0.0.1:8080/.well-known/jwks.json
```

With the example host port, test the public GET and cache header:

```sh
curl --fail --silent --show-error -i http://127.0.0.1:8783/.well-known/jwks.json
```

In Windows PowerShell, use `curl.exe` for host curl commands to avoid an `Invoke-WebRequest` alias. Substitute the chosen `JWKS_API_HOST_PORT` when it differs from 8783; do not assume the reference root's real `.env` uses the documented fallback.

Optional caller metadata test:

```sh
curl --fail --silent --show-error -i -H "X-Caller-Service: integration-check" -H "X-Caller-Instance: local-check" -H "X-Caller-Version: v1" -H "X-Correlation-ID: jwks-integration-check" http://127.0.0.1:8783/.well-known/jwks.json
```

Expect HTTP 200, `keys` at the root, the intended IDs, RSA/RS256 entries, nonempty `n`/`e`, and a public 300-second cache policy. The response should contain no private-key parameters (`d`, `p`, `q`, `dp`, `dq`, `qi`) or application response envelope. The correlation ID is returned in `X-Correlation-ID` when valid.

Test DNS/HTTP from the actual consumer environment as well. If that image already contains curl, for example:

```sh
docker compose exec -T learning-api curl --fail --silent --show-error http://jwks-api:8080/.well-known/jwks.json
```

Use the consumer's HTTP tooling if curl is absent. Complete validation by obtaining a token through the integrating application's legitimate authentication flow and invoking one of its protected routes. Confirm rejection of expired tokens, wrong issuers, unknown `kid`, invalid signatures, and disallowed algorithms. The root reference's token claims and role policy must not be assumed for another application. Do not commit tokens or publish authorization headers in troubleshooting output.

Readiness indicates that Boot considers the process ready after startup; it does not compare keys with a signer, refetch files, validate a consumer token, or test network paths from other containers.

## 10. Rotation, caching, and availability

Rotation is a coordinated deployment operation, not an endpoint call:

1. Generate/provision a new signer private key and its matching public export with a **new unique `kid`**. Do not replace key material under an ID that clients may have cached.
2. Add the new public file and complete indexed entry to JWKS. Keep the old public entry. At this stage, retain exactly one active flag (for example, the old entry true and the new entry false).
3. Recreate/restart all JWKS replicas and verify both IDs are published. Before issuing tokens with the new key, allow consumer/proxy caches to refresh and test fetching the new ID. The HTTP response advertises 300 seconds, but actual decoder cache and unknown-ID refresh behavior are consumer-specific.
4. Switch the signer to the new private key and `kid`, retaining its own exactly-one-active-key invariant. Coordinate JWKS flags so its configuration also has exactly one active entry; JWKS publishes both entries regardless of those flags.
5. Verify both new tokens and still-valid tokens signed with the old key. Keep the old public key until the last old-key token has expired, plus the deployment's allowed clock skew and rollout safety margin. Include the maximum token lifetime of **all** signers using that key.
6. Remove the old public entry/file only after that retention window. Keep list indices contiguous, recreate JWKS, and verify consumers again.

Plain `docker compose restart jwks-api` reloads files visible through existing mounts but does not apply changed env-file values or mount definitions. After changing the key list, env file, or mounts, use:

```sh
docker compose up -d --force-recreate --no-deps jwks-api
```

For a single instance, recreation can cause a brief outage. Multi-replica deployment and coordinated rolling updates belong to the root deployment platform; there is no built-in leader election, reload orchestration, or key distribution. All replicas behind one JWKS URL must publish a consistent key set. Cached consumers may continue working during an outage only while they have a usable trusted key; uncached/new IDs can fail authentication. Exact cache TTLs, retries, and network timeouts are not explicitly configured by the inspected consumer decoders and must be tested for their resolved dependencies.

Removing a key is not immediate revocation: cached public keys can still validate tokens. The service has no token denylist, introspection, or emergency revocation endpoint.

## 11. Security, logging, and deployment isolation

### Public surface and transport

`SecurityConfig` permits anonymous GET to JWKS, Swagger UI/OpenAPI routes (`/swagger-ui.html`, `/swagger-ui/**`, `/v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml`) and permits the health endpoint. Other requests require authentication; HTTP Basic and form login are enabled. CSRF is not explicitly disabled. No custom user store, service-to-service authentication contract, or application TLS setup is provided here.

Fetching JWKS needs no credentials. Do not disable security wholesale to make GET work. Non-GET probes or unlisted paths can have different authentication/CSRF behavior. CORS is enabled through framework defaults, but no explicit trusted-origin policy is configured; browser access should be tested/configured separately if needed.

For remote/untrusted networks, terminate TLS with a trusted deployment component and configure consumers to trust its certificate. The reference HTTP URL is a same-network example, not a secure public transport prescription. Permit anonymous JWKS retrieval through the proxy, preserve the exact path/raw JSON/cache policy, and do not redirect decoder requests to a login page. Protect health and documentation exposure at the deployment edge as required. Rate limiting and trusted proxy policy are deployment concerns, not implemented controls in this service.

### Keys and configuration

Mount only public keys read-only; do not mount the signer's private directory merely because JWKS now ignores private paths. Set key IDs, filesystem mappings, issuer policy, URLs, ports, and logging labels per deployment rather than changing source constants or baking key material into an image. Stable internal container paths and port 8080 may remain fixed when the root consistently maps mounts/healthchecks to them.

The application does not establish per-issuer/per-tenant key ownership. If one endpoint publishes keys from unrelated trust domains, consumers must enforce the intended issuer-to-key trust relationship; a common `iss` check alone is insufficient if any unrelated trusted private key can sign that issuer string. Prefer separate runtime key sets/endpoints for independent trust domains. Key-strength policy and key lifecycle ownership are not enforced by the loader.

### Logging and health operation

Console and file logs use structured JSON. The file is `${APP_LOG_DIR}/${APP_NAME}.log`; archives rotate at 100 MB, retain up to 30 history periods with a 2 GB total archive cap, and clean history at startup. Files are not a reason to run the container as root. Set log-volume permissions and disk/collection policies in the root.

The request filter logs method, safe route, status, elapsed time, peer address, and bounded caller metadata; sensitive request bodies/authorization headers are not its logging inputs. `X-Caller-Service`, `X-Caller-Instance`, `X-Caller-Version`, and `X-Correlation-ID` are optional. Caller IDs use `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`; missing/invalid caller identity becomes `unknown`, and invalid/ambiguous correlation IDs are replaced. Caller identity is explicitly **self-reported**, not authorization or proof of caller identity. The reference User/Learning HTTP interceptors attach it for diagnostics.

Lifecycle/key-loading events include `rsa.keys.loading`, `rsa.keys.loaded`, `rsa.keys.failed`, and `application.ready`. Failure diagnostics include stage and key index; the custom throwable converter omits exception messages. Never depend on dumping environment variables or private files to diagnose startup failures. Keep proxy header handling at `none` unless proxy trust is configured deliberately.

### Reusing source across root repositories

The submodule shares **source and build logic**, not running containers, networks, env files, volumes, issuers, keys, or consumer trust. Each root selects a commit and owns its runtime configuration. Two roots using the same commit can use entirely different key sets and issuers.

To intentionally share one running JWKS instance, explicitly define its reachable HTTPS address/network, trusted signers, rotation owner, uptime/SLA, cache policy, and cross-root incident process. A common Git URL does not create this runtime relationship. Independently deployed roots should avoid host-port collisions and global volume/network names, and never share production private keys merely to reuse code.

The inspected source tree has no tracked license file and its POM license metadata is empty. Confirm permission to redistribute/reuse the repository with its owner before broader distribution; this guide cannot establish licensing rights.

## 12. Test and update the submodule

Run the submodule tests separately from the Docker build. Linux/macOS, from the submodule directory:

```sh
cd springboot-jwks-api
./mvnw -B -ntp test
cd ..
```

If the Unix wrapper lacks execute permission, invoke it with `sh ./mvnw -B -ntp test`. On Windows, from the root:

```powershell
.\springboot-jwks-api\mvnw.cmd -f .\springboot-jwks-api\pom.xml -B -ntp test
```

Existing tests cover public-only loading, invalid configuration/files, retained keys, JWK encoding, response/error handling, and logging. They do not replace an integration test of your deployed network, signer, and consumer.

To review and adopt an update, first ensure the submodule has no uncommitted changes:

```sh
git -C springboot-jwks-api status --short
git -C springboot-jwks-api fetch origin --tags
git -C springboot-jwks-api log --all --oneline -10
git -C springboot-jwks-api rev-parse HEAD
```

Record that last commit as the rollback revision. Replace `REVIEWED_COMMIT_OR_TAG` below with a real reviewed revision; it is not an existing tag name:

```sh
git -C springboot-jwks-api checkout --detach REVIEWED_COMMIT_OR_TAG
git -C springboot-jwks-api submodule update --init --recursive
git diff --submodule=log -- springboot-jwks-api
```

Review upstream configuration changes, run tests and the deployment/JWT checks above, and recreate the service with the updated image:

```sh
docker compose up -d --build --force-recreate --no-deps jwks-api
git add springboot-jwks-api
git commit -m "Update JWKS service submodule"
git push
```

Do not use a blind branch-tip update for production. The root commit must record the tested gitlink; CI should deploy that revision. Source changes made in the submodule must be committed/pushed in the submodule repository before a root can reliably reference them. Committing only in the root does not publish a submodule's files.

For rollback, check out the recorded earlier submodule commit, restore any compatible deployment configuration/key list through the owning root's review process, rebuild/recreate, validate, and commit the reverted gitlink. Do not discard uncommitted work or remove keys needed for still-valid tokens.

## 13. Troubleshooting

| Symptom | Check and action |
| --- | --- |
| Empty/missing submodule build context or `.mvn` | Run `git submodule update --init --recursive`; verify checkout and build context point at the submodule root. |
| `cannot find symbol getPrivateKeyPath()` | Source/model mismatch between old pair-loading code and public-only properties. Select a consistent reviewed revision containing both changes; do not add a private-key field to the public-only model as a deployment workaround. |
| Maven repository warning / dependency download failure | Distinguish nonfatal metadata warnings from the final failing task. Verify build network/proxy/CA configuration and repository access. Runtime env files cannot fix Java compilation errors. |
| Wrapper permission or `^M` error | The Dockerfile normalizes CRLF and grants execute permission; for local Unix builds use LF endings and a callable wrapper. Do not confuse Windows `mvnw.cmd` with the Unix wrapper. |
| Key-list/active-key startup failure | Supply `.env.jwks-api` via `env_file`, contiguous indices, nonblank unique IDs/paths, and exactly one true flag. Setting keys only in root `.env` is insufficient unless explicitly passed. |
| `Failed to load RSA keys` / key-read stage | Verify the container path, bind source existence, host permissions/ACLs, and public PEM format. Check as UID 10001; do not print file contents. All entries, including inactive ones, must be readable and valid. |
| File path is unexpectedly a directory | A short bind syntax may have created a missing host source directory. Provision the intended PEM file and use long bind syntax with `create_host_path: false`. |
| Log-file permission errors / read-only filesystem | Verify `/app/data/logs` volume ownership, archive-directory write access, and writable `/tmp`; preserve the non-root runtime. |
| Readiness 404 or unhealthy container | Set `MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED=true`, expose `health`, align listener/probe ports, and inspect startup/key-loading events. The Dockerfile alone has no healthcheck. |
| Host curl works, consumer cannot resolve/connect | Use common Docker network, service DNS `jwks-api`, and port 8080; host loopback/published port are not container service discovery. Check custom network aliases or cross-project boundaries. |
| GET returns 401/redirect/HTML | Verify the exact `/.well-known/jwks.json` GET path, reverse-proxy rules, and selected service/version. The application permits that GET anonymously; gateways must not impose an interactive login. |
| Consumer rejects token despite HTTP 200 JWKS | Check token `alg`, `kid`, signature/key correspondence, exact issuer, timestamps/skew, audience policy, and authorization/role mapping. A healthy key server is not proof of a valid token. |
| New key not visible after replacing a file | Keys load only at startup. Recreate after env/mount changes; verify all replicas and consumer/proxy caches. |
| Old key still visible with `ACTIVE=false` | Expected: inactive keys are published for rotation. Remove an entry only after old tokens' retention window. |
| Changed `.env` value has no effect | Check shell interpolation overrides, `environment` versus `env_file`, and container recreation. `JWKS_API_*` are root inputs, not direct application settings unless mapped. |
| Older clone still requires private keys | Check the actual gitlink/checkout. This guide describes `c09d2a57ffa4c89e1b6897dc46499930069291fc`; older revisions have a different key contract. |

## 14. Integration checklist and verification limits

- [ ] Confirm source reuse permissions and repository/CI access.
- [ ] Add/initialize the submodule and pin a reviewed public-only revision; commit the root gitlink.
- [ ] Keep the submodule clean and runtime configuration outside its source tree.
- [ ] Provision RSA public PEM exports matching each signer's private key; never mount private material into JWKS.
- [ ] Use unique, coordinated IDs and contiguous indices; configure exactly one active entry.
- [ ] Create `.env.jwks-api`, a safe corresponding example, root Compose inputs, and ignore rules.
- [ ] Verify root interpolation versus container injection, quoting, and precedence.
- [ ] Configure correct build context, UID/GID permissions, public-only read-only mounts, writable logs and `/tmp`.
- [ ] Choose per-project ports/network/volume isolation and an appropriate production HTTPS/proxy policy.
- [ ] Configure consumer JWKS URI, explicit algorithm/issuer/claim/audience policy, and appropriate HTTP/cache behavior.
- [ ] Enable readiness probes, validate Compose quietly, run tests separately, build, and inspect startup events.
- [ ] Test the raw JWKS shape/cache policy from host and consumer networks; check all intended IDs and absence of private components.
- [ ] Test a real signer-to-consumer JWT flow and negative cases; do not treat readiness as that test.
- [ ] Establish key rotation timing, old-token retention, replica consistency, rollback, and outage procedures.
- [ ] Review and commit only safe deployment/examples and submodule pointers; keep real keys/env files/tokens untracked.

This guide is based on static inspection of the checked-out code, tests, root Compose, and env templates. Actual production key correspondence, filesystem ACLs, image builds, registry/repository reachability, deployed TLS/proxy behavior, and cross-service JWT acceptance require validation in the integrating environment. No live production deployment or signer token was exercised to write this guide.

Known implementation/deployment gaps include no hot reload or automated rotation, no key-size enforcement in the loader, no issuer/tenant binding inside JWKS, no built-in revocation/introspection, no custom rate limiting or TLS deployment, no explicit consumer cache/timeouts policy, and no audience validation in the reference consumer configuration. The root template/version drift described at the beginning must be reconciled by each deployment owner; this documentation does not modify it.
