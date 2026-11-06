# Integrating the owner-isolated JWKS service

This service uses Spring Boot 4.1.2 and Java 17. Run it as a submodule or standalone;
no database or signing-service connection is required. It loads configured public files once
at startup and publishes immutable verification-key sets.

## API and trust

One controller mapping serves `GET /api/v1/jwks/{owner}`. Lowercase `user` and `learning` are
supported. Unknown or missing owners return 404, never all keys. The success format is standard
unwrapped `{"keys":[...]}` with `kty`, `kid`, `use`, `alg`, `n`, `e` and five-minute public caching.
An empty owner group returns an empty keys array. No owner metadata or private components are
returned. Other authentication, health, Swagger and logging rules are unchanged.

User's external decoder and Learning's inbound decoder must trust only `/api/v1/jwks/user`.
User's internal decoder trusts only `/api/v1/jwks/learning`. Do not choose URLs from unverified
JWT claims. `kid` selects a key within a trusted set; issuer, audience, type and time validation
remain consumer responsibilities. Preserve public keys during rotation until all tokens expire.

## Configuration

Copy the submodule `.env.example` for a standalone run. Spring does not automatically load
.env files: export their variables into the Java process. Compose deployments use root
`.env.jwks-api` via `env_file` instead. The root `.env` supplies Compose interpolation settings.

```dotenv
APP_SECURITY_JWKS_KEYS_0_KID=user-rsa-v1
APP_SECURITY_JWKS_KEYS_0_PUBLICKEYPATH=/run/keys/jwt/userservice-public-v1.pem
APP_SECURITY_JWKS_KEYS_0_OWNER=USER
APP_SECURITY_JWKS_KEYS_1_KID=learning-rsa-v1
APP_SECURITY_JWKS_KEYS_1_PUBLICKEYPATH=/run/keys/learning/learningservice-public-v1.pem
APP_SECURITY_JWKS_KEYS_1_OWNER=LEARNING
```

Repeat contiguous indices for more keys. IDs must be globally unique. PUBLICKEYPATH is the
canonical environment spelling for publicKeyPath. OWNER is required (USER or LEARNING).
The loader rejects missing owners, invalid enum values, missing files, malformed/non-RSA public
PEMs and duplicate IDs. PEMs use X.509 SubjectPublicKeyInfo (`BEGIN PUBLIC KEY`). Private PEMs,
certificates, SSH keys and PKCS#1 RSA public PEMs are not this loader's input format.

JWKS does not select an active signing key; there is no active-key-count requirement.
Signers independently select exactly one active private key. JWKS publishes all configured
public keys assigned to the requested owner, including retained keys for rotation.

## Docker

Mount only explicit public files, read-only, and disable automatic host-path creation:

```yaml
volumes:
  - type: bind
    source: ./key/userservice-public-v1.pem
    target: /run/keys/jwt/userservice-public-v1.pem
    read_only: true
    bind:
      create_host_path: false
  - type: bind
    source: ./data/keys/learning/learningservice-public-v1.pem
    target: /run/keys/learning/learningservice-public-v1.pem
    read_only: true
    bind:
      create_host_path: false
```

The reference root also retains User v2 through its own public-file mount and indexed entry.
Do not mount a directory that contains private keys. Files must be readable by UID 10001.
Windows host paths identify bind sources; /run/keys paths identify files inside Linux containers.
Standalone Java must use host paths instead. Never bake keys or env secrets into the image.

Root deployment example:

```cmd
docker compose config --quiet
docker compose -p springboot_project up -d --build --no-deps jwks-api
curl.exe http://localhost:8783/api/v1/jwks/user
curl.exe http://localhost:8783/api/v1/jwks/learning
```

Internal Docker consumers use `http://jwks-api:8080`, never host loopback addresses.
For a complete API migration, recreate affected consumers too; do not delete persistent data.

## Verification and operational limits

Run `mvnw.cmd -B -ntp verify`. Tests cover owner isolation, multiple keys, configuration binding,
invalid owners, duplicates, exact JWK encoding, HTTP status/cache headers, validation and logging.
Compare published n/e with the configured public PEMs. Consumers must independently test valid,
expired, wrong-key, wrong-type and wrong-issuer tokens. Signature verification is not authorization.

Files are read only at startup. After changing key files/configuration, recreate the service.
Caches can retain old keys; publish new keys before switching signers and keep old keys during
the overlap window. Use trusted network/TLS transport in production and restrict configuration
write access: a wrong operator-assigned owner can create an incorrect trust association.

See README.md for response conventions and root JWT-AUTHENTICATION.md for the reference
project's external/internal decoder separation and environment-variable table.
