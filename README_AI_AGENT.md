# AI DevOps Triage & Auto-Remediation Agent (`README_AI_AGENT.md`)

## Overview
This repository integrates an automated **AI DevOps Triage & Auto-Remediation Agent** powered by **Google Gemini 3.5 Flash-Lite** (`gemini-3.5-flash-lite`) into GitHub Actions. 

When a CI build, unit test suite, or self-hosted VM deployment fails, the agent automatically inspects the captured error logs, redacts sensitive credentials, diagnoses the root cause using structured JSON outputs, executes safe allowlisted recovery actions, and posts a readable summary directly to the GitHub Actions run page. Passing builds make **zero** API calls and incur no added latency or cost.

---

## Architecture & File Changes

| File Path | Purpose & Key Changes |
| :--- | :--- |
| **`.github/scripts/devops_agent.py`** | **New AI Agent Script.** Sends truncated build/deployment logs to `gemini-3.5-flash-lite`, strips secrets/JDBC URLs, enforces a Pydantic JSON schema (`RemediationPlan`), and outputs `remediation_plan.json`. |
| **`.github/workflows/ci.yml`** | **Updated CI/CD Pipeline.** Added `continue-on-error: true` and `tee` log capture to both `build-and-test` and `deploy-local`, wired failure-only AI triage steps, added `$GITHUB_STEP_SUMMARY` reporting, and implemented allowlisted recovery commands. |
| **`deploy/deploy-local.sh`** | **Existing Self-Hosted Deployment Script.** Builds versioned releases under `/opt/bank-lab/releases/backend-$SHA`, verifies Spring Boot `/actuator/health` endpoints, and updates `/opt/bank-lab/backend-current` only after all health checks pass. |
| **`README_AI_AGENT.md`** | **Documentation.** Documents setup, secrets configuration, safety guardrails, allowed remediation actions, and testing procedures. |

---

## Prerequisites & Secrets Setup

1. **Google AI Studio API Key:** Generate an API key from [Google AI Studio](https://google.com) with access to `gemini-3.5-flash-lite`.
2. **GitHub Repository Secret:**
   * Navigate to **Settings > Secrets and variables > Actions** in this GitHub repository.
   * Under **Repository secrets**, add a secret named **`GEMINI_API_KEY`**.
   *(Note: Use Repository secrets rather than Environment secrets so both `build-and-test` and `deploy-local` jobs can access the key without environment-level overrides.)*
3. **Runner Dependencies:** Both GitHub-hosted (`ubuntu-latest`) and self-hosted (`[self-hosted, linux, x64, bank-backend]`) runners require `python3`, `pip`, and `jq` installed.

---

## How the Pipeline Works

### 1. Build & Test Stage (`build-and-test`)
* Runs `./mvnw -B -ntp verify` on Java 25 (Temurin) and streams output to `maven_build.log`.
* **On Success:** The AI step is skipped completely (`if: steps.maven_verify.outcome == 'failure'`).
* **On Failure:**
  1. Invokes `python3 .github/scripts/devops_agent.py maven_build maven_build.log`.
  2. Writes the root cause, recommended action, confidence score, and developer notes to `$GITHUB_STEP_SUMMARY`.
  3. Uploads `remediation_plan.json` alongside Surefire XML reports in the `surefire-and-ai-reports` artifact.
  4. Executes an allowlisted recovery action if `confidence >= 0.85`, or exits with status `1` to flag the build for human review.

### 2. Self-Hosted Deployment Stage (`deploy-local`)
* Runs only on pushes or manual dispatches to `main` on the self-hosted laptop VM runner (`bank-backend`).
* Executes `bash deploy/deploy-local.sh` and streams output to `deploy.log`.
* **On Failure:**
  1. Appends the last 60 lines of Docker Compose container logs (`docker compose logs --tail=60`) to `deploy.log` so Java/Spring Boot or Keycloak startup exceptions are visible to the model.
  2. Invokes `python3 .github/scripts/devops_agent.py local_deploy deploy.log`.
  3. Posts the **🤖 Gemini Deployment Triage** summary to the GitHub Actions run page.
  4. Executes safe local recovery or rolls back to the previous healthy release recorded in `/opt/bank-lab/backend-current`.

---

## Allowlisted Auto-Remediation Actions

To prevent destructive AI behavior, `ci.yml` maps Gemini's structured `recommended_action` output strictly to pre-approved shell commands:

| Stage | `recommended_action` | Trigger Condition | Automated Action Executed |
| :--- | :--- | :--- | :--- |
| **`maven_build`** | `retry_maven` | Transient Maven Central HTTP/network timeout (`confidence >= 0.85`) | Re-runs `./mvnw -B -ntp verify`. |
| **`maven_build`** | `purge_m2_cache_and_retry` | Corrupted `~/.m2` dependency artifact (`confidence >= 0.85`) | Deletes `~/.m2/repository` and re-runs `./mvnw -B -ntp verify`. |
| **`maven_build`** | `escalate_to_human` | Any JUnit test failure, assertion error, or compilation error | Fails the workflow (`exit 1`) and displays the failing class/notes in Step Summary. |
| **`local_deploy`** | `prune_docker_and_retry` | `No space left on device` during `docker compose build` (`confidence >= 0.85`) | Runs `docker builder prune -f` and `docker image prune -f` (preserving `mysql-data` and `keycloak-data` volumes), then re-runs `deploy-local.sh`. |
| **`local_deploy`** | `restart_compose_services` | Container healthcheck or `/actuator/health` cold-start timeout without Java exceptions (`confidence >= 0.85`) | Re-runs `docker compose up -d --no-build --wait --wait-timeout 300` and updates `backend-current`. |
| **`local_deploy`** | `rollback_to_previous` | New release crashes on startup (e.g., `BeanCreationException`, Flyway error, port conflict) | Reads `$PREV_SHA` from `/opt/bank-lab/backend-current`, brings up the previous healthy `compose.local.yml`, and exits `1` so GitHub still alerts on the broken commit. |
| **`local_deploy`** | `escalate_to_human` | Unrecognized infrastructure or configuration error | Fails the workflow (`exit 1`) for manual inspection on the VM. |

---

## Security & Data Privacy Guardrails
* **Secret Redaction:** Before sending any log snippet to the Gemini API, `redact_secrets()` in `devops_agent.py` masks passwords, API tokens, confirmation secrets, and `jdbc:` connection strings with `[REDACTED]`.
* **Log Truncation:** Only the trailing `12,000` characters of `maven_build.log` or `deploy.log` are transmitted to keep token usage minimal and focused on stack traces.
* **Volume Protection:** Automated Docker pruning never runs `docker volume prune`, ensuring persistent database (`mysql-data`) and identity (`keycloak-data`) volumes on `/opt/bank-lab` are never deleted.
* **No Arbitrary Command Execution:** Gemini outputs an enum category (`recommended_action`); the workflow never executes raw shell commands generated by the LLM.

---

## Testing & Verification

A dedicated isolation branch (`test/ai-triage-agent`) can be maintained to verify the AI triage agent without affecting `main` or triggering `deploy-local`:

1. Switch to the test branch and sync latest workflow changes from `main`:
   ```bash
   git checkout test/ai-triage-agent
   git merge main
   ```
2. Keep an intentional failing JUnit test (`AgentSmokeTest.java`) on `test/ai-triage-agent` and push:
   ```bash
   git push origin test/ai-triage-agent
   ```
3. Open **GitHub > Actions**, select the workflow run, click **Summary** in the top-left sidebar, and confirm the **🤖 Gemini DevOps Triage** section renders with `escalate_to_human` and the exact failing test details.
4. Switch back to `main` for normal development (`git checkout main`).
