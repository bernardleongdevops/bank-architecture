# RP Digital Bank Sandbox

An educational banking digital-services sandbox. It simulates core banking APIs
(customers, accounts, transactions, payments) plus identity and compliance, so
students can learn digital banking and then plug in their own applications
(wealth planner, chatbot, merchant app) written in **any language** against
plain REST/JSON + OAuth APIs.

Everything that would normally be an external vendor (identity/OIDC, eKYC,
AML/fraud, the event bus) is simulated internally but sits **behind an adapter
interface**, so a real vendor can be swapped in later via configuration alone -
no service code changes.

> Status: v1 in progress. This repo is built task-by-task; see the task list below.

## Architecture (v1)

```
Pluggable Application Layer   (any language: JS / Node / Next.js / Python / Flutter)
          |  REST/JSON + OAuth
API & Experience Layer        gateway (routing, API keys, rate limit, token validation)
          |
Digital Banking Services      customer | account | transaction | payment   (Java 25 / Spring Boot)
          |                        |                         |
Risk/Compliance adapters      KycProvider (simulated)   AmlProvider (simulated)
          |
Data & Event Layer            MySQL 8 + Flyway       EventPublisher (in-process now, Kafka later)
```

### The adapter pattern (why it is detachable)

Each replaceable capability is an interface in the `common` module with a
simulated implementation selected by a config flag:

| Capability | Interface (`common`)        | v1 implementation            | Config flag                 |
|------------|-----------------------------|------------------------------|-----------------------------|
| Identity   | `IdentityProvider`          | Keycloak (added in Task 3)   | `provider.identity=keycloak`|
| eKYC       | `KycProvider`               | `SimulatedKycProvider`       | `provider.kyc=simulated`    |
| AML/fraud  | `AmlProvider`               | `SimulatedAmlProvider`       | `provider.aml=simulated`    |
| Events     | `EventPublisher`            | in-process (added in Task 6) | `provider.events=in-process`|

To engage a vendor later: add a new implementation of the interface, gate it on
the matching `provider.*` value, and flip the config. Nothing else changes.

## Modules

| Module                | Description                                      | Port  |
|-----------------------|--------------------------------------------------|-------|
| `common`              | Shared DTOs, errors, and adapter interfaces      | (lib) |
| `compliance-adapters` | Simulated KYC + AML providers (auto-configured)  | (lib) |
| `security-adapters`   | Identity providers + shared JWT resource-server  | (lib) |
| `events-adapters`     | EventPublisher impls (in-process now, Kafka later)| (lib) |
| `gateway`             | API gateway / single entry point                 | 8081  |
| `customer-service`    | Customer onboarding + simulated eKYC             | 8082  |
| `account-service`     | Accounts and balances                            | 8083  |
| `transaction-service` | Append-only ledger and history                   | 8084  |
| `payment-service`     | Internal transfers + AML screening + events      | 8085  |
| (Keycloak)            | Identity provider, run natively                  | 8080  |

## Prerequisites (native-first, no Docker required)

- **JDK 25** (LTS). Verify: `java -version`.
- **MySQL 8** running on `localhost:3306`. Verify the `MySQL80` service is running.
- **Maven**: not required - the project ships the Maven Wrapper (`mvnw` / `mvnw.cmd`),
  which downloads Maven on first use.
- **Keycloak**: needed from Task 3 onward; run natively (see that task's docs). Not needed yet.
- **Docker**: not required for v1. It becomes an optional one-command convenience in Task 12.

## Database credentials (never hardcoded)

Services read MySQL credentials from environment variables, with sane local
defaults. Set these in your shell before running (PowerShell):

```powershell
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "<your-mysql-root-password>"
```

Other overridable variables: `DB_HOST` (default `localhost`), `DB_PORT`
(default `3306`), and `DB_NAME` (defaults to a per-service schema, e.g.
`sandbox_customer`). Each service creates its schema automatically on first run.

## Build and test

Windows (PowerShell or cmd):

```
build.cmd verify
```

or directly with the wrapper:

```
mvnw.cmd verify
```

This compiles every module and runs all tests. Tests use an in-memory H2
database, so **a running MySQL is not required to build or test** - only to run
the services for real.

## Run a service natively

Each service is a standalone Spring Boot app. For example, the customer service:

```powershell
$env:DB_USERNAME = "root"; $env:DB_PASSWORD = "<pwd>"
mvnw.cmd -pl customer-service spring-boot:run
```

Then check health:

```
curl http://localhost:8082/actuator/health
```

Interactive API docs (Swagger UI) for that service:

```
http://localhost:8082/swagger-ui.html
```

### Try the customer + eKYC flow (Task 2)

```powershell
# Approved by the deterministic simulated KYC rules:
curl -X POST http://localhost:8082/customers -H "Content-Type: application/json" `
  -d '{"fullName":"Alice Tan","email":"alice@example.com","nationalId":"S1234567A"}'

# Deterministically REJECTED (name contains "FRAUD"):
curl -X POST http://localhost:8082/customers -H "Content-Type: application/json" `
  -d '{"fullName":"Fraud Test","email":"fraud@example.com"}'
```

Simulated KYC rules: a name containing `FRAUD` is REJECTED, a name containing
`REVIEW` goes to MANUAL_REVIEW, everything else is APPROVED.

## Aggregated API docs

One Swagger UI at the gateway lists every service's API:

```
http://localhost:8081/swagger-ui.html
```

Pick a service from the dropdown. A full walkthrough (start the stack, get a
token, call the APIs, build your own app) is in [GETTING_STARTED.md](GETTING_STARTED.md).

## Calling through the API gateway (Task 7)

The gateway (port 8081) is the single entry point. Clients call `/api/v1/...`
and must present a sandbox API key header; the gateway validates the key, applies
a per-key rate limit, validates the JWT, then routes to the right service.

```
# via the gateway (note the /api/v1 prefix + API key + bearer token)
curl http://localhost:8081/api/v1/accounts `
  -H "X-Sandbox-Key: demo-key-123" `
  -H "Authorization: Bearer <access_token>"
```

- API key header: `X-Sandbox-Key` (demo keys: `demo-key-123`, `student-key-456`).
- Rate limit: 60 requests/min per key (configurable), returns `429` when exceeded.
- Missing/invalid key returns `401`.
- Public paths (actuator health, OpenAPI/Swagger) need no key.

## Task progress

- [x] Task 0 - Environment setup and verification
- [x] Task 1 - Project skeleton (Maven monorepo, Java 25, MySQL)
- [x] Task 2 - Customer Service + simulated eKYC behind an adapter
- [x] Task 3 - Simulated Identity via Keycloak behind the IdentityProvider adapter
- [x] Task 4 - Account Service with pre-seeded synthetic data
- [x] Task 5 - Transaction Service (ledger + history)
- [x] Task 6 - Payment Service (internal transfers) + simulated AML + events
- [x] Task 7 - API Gateway (API keys, rate limiting)
- [x] Task 8 - Aggregated OpenAPI / Swagger UI + onboarding docs
- [x] Task 9 - Instructor admin endpoint for configurable compliance outcomes
- [x] Task 10 - Reference client application
- [x] Task 11 - GitHub Actions CI/CD
- [x] Task 12 - Containerization + Kubernetes-readiness

## Reference client app

A framework-free HTML + JavaScript example (OIDC login, list accounts, view a
statement, make a transfer) lives in [`reference-app/`](reference-app/README.md).
Serve it on port 3000 (`npx serve -l 3000 reference-app`) and log in as
`alice` / `password`. Use it as a template for your own app in any language.

## Running with Docker (optional)

Native-first needs no Docker, but once Docker is installed you can start the whole
stack with one command:

```
docker compose up --build
```

This runs MySQL, Keycloak (realm imported), the four services, and the gateway.

## Kubernetes (optional graduation)

Plain manifests under [`k8s/`](k8s/README.md) run the same services on a cluster
(Deployments + Services, ConfigMap/Secret, gateway Ingress). Adapter switching
stays configuration-only via the `PROVIDER_*` keys. Validate with
`kubectl apply --dry-run=client -f k8s/`.

## Continuous integration

A GitHub Actions workflow ([`.github/workflows/ci.yml`](.github/workflows/ci.yml))
builds the monorepo and runs all tests on every push and pull request using
JDK 25 (tests use H2, so no MySQL is needed in CI).

## CI/CD & AI Pipeline Triage

This repository uses GitHub Actions for automated Maven builds, unit testing, and self-hosted VM deployments, paired with a **Gemini 3.5 Flash-Lite** failure triage and auto-remediation agent.

* **Full Setup, Guardrails & Remediation Guide:** [README_AI_AGENT.md](./README_AI_AGENT.md)
* **Workflow Configuration:** [`.github/workflows/ci.yml`](./.github/workflows/ci.yml)
* **Triage Agent Script:** [`.github/scripts/devops_agent.py`](./.github/scripts/devops_agent.py)
