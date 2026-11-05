# Implementation Plan: ai-assistant-service

## Overview

This plan builds the `ai-assistant-service` Spring Boot module (package root `sg.edu.rp.sandbox.ai`) incrementally,
mirroring the existing services. It starts with the Maven module, Dockerfile, config, and resource-server security;
then the two seams (`LlmClient`, `BankingApiClient`) with stubs; then Phase 1 read-only tools, the tool loop, and
spending categorisation; then Phase 2 proposal + confirmation handshake and transfer-outcome surfacing; and finally
the gateway route, docker-compose entry, and end-to-end web-slice wiring. Each step builds on the previous and ends
with integration so there is no orphaned code. Language is Java 25 / Spring Boot 3.5.x, consistent with the repo.
Property-based tests use jqwik; unit/web-slice tests use Spring Boot test + spring-security-test. No real network in
tests (both seams are stubbed).

## Tasks

- [x] 1. Scaffold the Maven module, Dockerfile, and base config
  - [x] 1.1 Create the `ai-assistant-service` Maven module and register it in the parent pom
    - Add `<module>ai-assistant-service</module>` to the aggregator pom
    - Create `ai-assistant-service/pom.xml` inheriting the parent; add `spring-boot-starter-web`, `-actuator`, `-validation`, `security-adapters`, `common`, `springdoc-openapi-starter-webmvc-ui`, and test deps (`spring-boot-starter-test`, `spring-security-test`, `jqwik`)
    - Create the Spring Boot application class `sg.edu.rp.sandbox.ai.AiAssistantApplication`
    - _Requirements: 12.1_
  - [x] 1.2 Add the multi-stage Dockerfile (Java 25) and application.yml
    - Dockerfile `-pl ai-assistant-service -am` build, runtime `EXPOSE 8086`, consistent with other services
    - `application.yml`: `server.port: 8086`, app name, resource-server `issuer-uri`/`jwk-set-uri` (env `SANDBOX_ISSUER_URI`/`SANDBOX_JWKS_URI`), springdoc Swagger UI, actuator health/info exposure
    - Add `ai.*` config keys: `ai.model` (default `gpt-4o-mini`), `ai.max-tool-iterations` (default 5), `ai.gateway.base-url` (default `http://localhost:8081/api/v1`), `ai.sandbox-key`, read `OPENAI_API_KEY` from env; nothing secret hardcoded
    - _Requirements: 11.1, 11.4, 11.5, 12.2, 12.3_
  - [x]* 1.3 Write a config-binding test for `ai.*` properties
    - Assert `ai.model` defaults to `gpt-4o-mini` and is overridable; assert gateway base URL / sandbox key / issuer / jwks bind from config
    - _Requirements: 11.1, 11.4, 11.5_

- [x] 2. Wire resource-server security and the chat DTOs
  - [x] 2.1 Enable Keycloak resource-server security
    - Depend on `security-adapters` so `ResourceServerSecurityConfig` applies; confirm public paths (actuator health, OpenAPI, Swagger) and that everything else requires a valid bearer token (stateless)
    - _Requirements: 4.4, 4.5_
  - [x] 2.2 Define request/response DTOs
    - `ChatRequest(message, history, confirmation)`, `ChatMessage(role, content)`, `ConfirmationDecision(confirmationToken, confirmed)`, `ChatResponse(type, reply, intent, confirmationToken, humanSummary, transferResult, toolsUsed)`, `TransferIntent`, `TransferRequest`, `TransferResult`, `SpendingCategory` enum, `CategoryTotal`, `CategorizationResult`
    - Add bean validation (e.g. require message or confirmation)
    - _Requirements: 1.5, 2.1_
  - [x]* 2.3 Write unit tests for DTO validation and (de)serialization
    - Deserialize a full request; assert empty body (no message, no confirmation) fails validation
    - _Requirements: 1.4, 1.5_

- [x] 3. Define the LlmClient seam and a stub for tests
  - [x] 3.1 Create the `LlmClient` interface and model DTOs
    - `LlmClient.complete(LlmRequest) : LlmResponse`; `LlmRequest` carries model, messages, tool definitions; `LlmResponse` carries either assistant text or `tool_calls`; `LlmException` for failures
    - _Requirements: 13.1_
  - [x] 3.2 Implement `OpenAiLlmClient` (default)
    - Use Spring `RestClient`/`WebClient`; read key from `OPENAI_API_KEY`, model from `ai.model`; bounded timeout; never log the key; map transport/5xx errors to `LlmException`
    - _Requirements: 9.1, 11.1, 11.3, 11.4_
  - [x]* 3.3 Add a scriptable `StubLlmClient` test fixture
    - Returns a programmed sequence of tool-call responses then a final text; used by orchestrator/web tests with no network
    - _Requirements: 13.3_

- [x] 4. Define the BankingApiClient seam and a stub for tests (Phase 1 read methods; `makeTransfer` deferred to Phase 2)
  - [x] 4.1 Create the `BankingApiClient` interface and domain DTOs (Phase 1: no `makeTransfer` - deferred to Phase 2 task 10)
    - Methods `getCustomerProfile`, `getAccounts`, `getAccount`, `getTransactions`, `makeTransfer`; each takes the on-behalf-of token; DTOs for `Account`, `Transaction`, `CustomerProfile`
    - _Requirements: 13.2_
  - [x] 4.2 Implement `GatewayBankingApiClient` (default) (Phase 1 read statuses 200/401/403/404/409/422/429; transfer statuses 201/202 are Phase 2)
    - Call `ai.gateway.base-url`; always attach `Authorization: Bearer <token>` and `X-Sandbox-Key`; map statuses 200/201/202/422/409/403/401/429 to typed results/exceptions preserving the standard error body `{ timestamp, status, error, message, path }`
    - _Requirements: 4.1, 4.2, 4.3, 5.2, 5.3, 5.4, 7.5, 8.1, 8.2, 8.3, 8.4, 8.5, 9.3, 9.4, 9.5_
  - [x]* 4.3 Write property test for on-behalf-of headers
    - **Property 3: Outbound gateway calls are always authenticated on-behalf-of**
    - **Validates: Requirements 4.1, 4.2, 4.3, 13.4**
  - [ ]* 4.4 Write property test for transfer-outcome mapping
    - **Property 8: Transfer outcomes map to the correct user-facing result**
    - **Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 9.5**
  - [x]* 4.5 Add a configurable `StubBankingApiClient` test fixture
    - Returns canned accounts/transactions and lets a test force a specific HTTP outcome (202/422/409/403/429); records outbound headers for assertions
    - _Requirements: 13.3, 13.4_

- [x] 5. Build the tool registry and dispatcher (Phase 1 tools)
  - [x] 5.1 Implement `ToolRegistry` with Phase 1 tool JSON schemas
    - Expose `getCustomerProfile`, `getAccounts`, `getAccount`, `getTransactions`, `categorizeSpending`; Phase 1 exposes no money-moving tool
    - _Requirements: 5.1, 5.5_
  - [x] 5.2 Implement `ToolDispatcher` mapping tool calls to `BankingApiClient`
    - Parse/validate arguments; dispatch read tools to the client; reject unknown tools; on malformed args return a tool error result (do not throw)
    - _Requirements: 5.2, 5.3, 5.4, 9.2_
  - [x]* 5.3 Write property test that no money-moving tool is exposed
    - **Property 4: No money-moving tool is ever exposed to the model**
    - **Validates: Requirements 5.5, 7.4**
  - [x]* 5.4 Write unit tests for tool argument parsing and dispatch mapping
    - getAccount/getTransactions map to the correct client calls with params; malformed args produce a tool error, not an exception
    - _Requirements: 5.2, 5.3, 5.4, 9.2_

- [x] 6. Implement the chat orchestrator (message assembly + tool loop)
  - [x] 6.1 Implement system-prompt assembly and message ordering
    - Externalise the system prompt (resource/property); assemble `system + history + current user message` in order; stateless (no persistence)
    - _Requirements: 2.1, 2.2, 2.3, 10.1_
  - [x] 6.2 Implement the multi-turn tool loop with grounding and bound
    - Loop: call `LlmClient`; while `tool_calls`, dispatch via `ToolDispatcher`, append results, re-invoke; stop at final text; enforce `ai.max-tool-iterations`; on bound reached return a graceful response; populate `toolsUsed`
    - _Requirements: 3.1, 3.2, 3.3, 3.4, 3.5, 10.4_
  - [x]* 6.3 Write property test for grounding
    - **Property 1: Answers are grounded in real tool results**
    - **Validates: Requirements 3.3, 10.4**
  - [x]* 6.4 Write property test for the tool-loop bound
    - **Property 7: Tool loop terminates within the configured bound**
    - **Validates: Requirements 3.4, 3.5**
  - [x]* 6.5 Write property test for message assembly/ordering
    - **Property 9: Model input reflects exactly the supplied conversation**
    - **Validates: Requirements 2.1, 2.3**
  - [x]* 6.6 Write unit tests for loop termination and LLM-failure handling
    - Final text returned as reply; `LlmClient` failure yields a temporarily-unavailable error response
    - _Requirements: 3.2, 9.1_

- [x] 7. Implement spending categorisation
  - [x] 7.1 Implement `CategorizationService`
    - Classify each transaction into exactly one `SpendingCategory` via the model (closed set; unknown -> OTHER); aggregate signed amounts and counts per category over the period; batch all descriptions in a single model call
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_
  - [x] 7.2 Wire `categorizeSpending` tool into the dispatcher
    - Dispatch fetches transactions via `BankingApiClient` then calls `CategorizationService`; return per-category totals to the model
    - _Requirements: 5.1, 6.1_
  - [x]* 7.3 Write property test for the canonical closed category set
    - **Property 5: Categorisation uses only the canonical closed set**
    - **Validates: Requirements 6.1, 6.2**
  - [x]* 7.4 Write property test for amount/count conservation
    - **Property 6: Categorisation conserves amounts and count**
    - **Validates: Requirements 6.4, 6.5**
  - [x]* 7.5 Write unit test for equivalent-description mapping
    - With a stubbed classifier, "pocket money" and "allowance" both map to ALLOWANCE
    - _Requirements: 6.3_

- [x] 8. Wire the ChatController and Phase 1 end-to-end
  - [x] 8.1 Implement `ChatController` `POST /ai/chat`
    - Extract the inbound bearer token (`@AuthenticationPrincipal Jwt`/request header); validate body; delegate to orchestrator; return `ChatResponse` with 200 for normal turns, 400 for empty body
    - _Requirements: 1.1, 1.2, 1.4, 4.3_
  - [x]* 8.2 Write web-slice tests for the chat endpoint (Phase 1)
    - MockMvc + spring-security-test with stubbed seams: valid authenticated request -> 200 grounded reply; no token -> 401; health reachable without token; assert outbound calls carried `X-Sandbox-Key`
    - _Requirements: 1.1, 1.2, 1.3, 4.5, 13.4_

- [x] 9. Checkpoint - Phase 1 complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 10. Implement Phase 2: transfer proposal + confirmation handshake
  - [x] 10.1 Add the `proposeTransfer` tool and intent building
    - Register `proposeTransfer` (Phase 2 only); on a proposeTransfer tool call, build `TransferIntent` (resolving source currency from the owned account) and a signed, short-lived `confirmationToken` bound to the intent and token subject; stop the loop and return `type:"confirm_transfer"` with intent + humanSummary + token; never call `makeTransfer` here
    - _Requirements: 7.1, 7.4_
  - [x] 10.2 Implement confirmation validation and execution
    - On a request with `confirmation.confirmed == true`: validate the token (unexpired, matches a proposed intent, subject == token owner); if valid, call `BankingApiClient.makeTransfer` with `{ fromAccount, toAccount, amount, description }`; if invalid/expired, refuse with an explanatory response; map the payment outcome to plain language
    - _Requirements: 7.2, 7.3, 7.5, 8.1, 8.2, 8.3, 8.4, 8.5_
  - [x]* 10.3 Write property test for no-write-without-confirmation (incl. injection resistance)
    - **Property 2: No write without an explicit valid confirmation**
    - **Validates: Requirements 7.1, 7.2, 7.3, 10.2, 10.3**
  - [x]* 10.4 Write unit tests for the confirmation handshake and outcome surfacing
    - Expired/garbage token -> no transfer, explanatory response; confirmed valid token -> makeTransfer called with mapped body; 202/422/409/403 surfaced in plain language
    - _Requirements: 7.3, 7.5, 8.2, 8.3, 8.4, 8.5_

- [x] 11. Implement secret-isolation safeguards and logging redaction
  - [x] 11.1 Ensure secrets never leak to responses or logs
    - Confirm `OPENAI_API_KEY` is never placed in any `ChatResponse`; add logging redaction for `Authorization` and the OpenAI key; verify error mapping does not echo the bearer token
    - _Requirements: 11.2, 11.3_
  - [x]* 11.2 Write property test for secret non-leak
    - **Property 10: Secrets never appear in responses or logs**
    - **Validates: Requirements 11.2, 11.3**

- [x] 12. Integrate the service into the gateway and docker-compose
  - [x] 12.1 Add the gateway route and docs route for the AI service
    - Add route `id: ai-service`, `uri: ${AI_URI:http://localhost:8086}`, `Path=/api/v1/ai/**`, `StripPrefix=2`; add the `ai-service` OpenAPI docs route and Swagger dropdown entry, consistent with the other services
    - _Requirements: 12.4, 12.5_
  - [x] 12.2 Add the docker-compose service entry
    - New `ai-assistant-service` service on port 8086 with `OPENAI_API_KEY`, `SANDBOX_ISSUER_URI`, `SANDBOX_JWKS_URI`, `AI_GATEWAY_BASE_URL`, `AI_SANDBOX_KEY`; add `AI_URI` to the gateway env; `depends_on` gateway/keycloak
    - _Requirements: 12.3, 12.4_
  - [x]* 12.3 Write a gateway route test for `/api/v1/ai/**`
    - Assert the `ai-service` route is configured with `StripPrefix=2` and targets the AI service URI
    - _Requirements: 12.4_

- [x] 13. Final checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 14. Harden and broaden the Phase 1 assistant (read-only)
  - Additive Phase 1 hardening discovered after live validation. Uses only the existing read-only tools
    (`getCustomerProfile`, `getAccounts`, `getAccount`, `getTransactions`, `categorizeSpending`) — no new backend
    tool or endpoint. Depends on Task 8 (Phase 1 wired end-to-end); independent of Phase 2 tasks 10/11.
  - [x] 14.1 Rewrite/expand the externalised system prompt (`src/main/resources/system-prompt.txt`)
    - Add multi-account aggregation guidance: call `getAccounts`, then `getTransactions`/`getAccount` per relevant account as needed, and sum/compare across accounts for totals, counts, and combined figures
    - Add period/comparison guidance (e.g. this month vs last month via two `getTransactions` ranges) and largest/smallest-transaction guidance (select the extreme by amount from a returned range)
    - Add account-phrase resolution ("my savings", "my current account", "my other account") to a real owned account number from `getAccounts`, with a clarifying-question rule when a phrase is ambiguous or matches multiple/none; say so plainly when a named account is not owned (no fabrication)
    - Add decline rules for out-of-scope and other-customer requests; reiterate Phase 1 cannot move money and that any future transfer needs explicit confirmation
    - Add consistent formatting: SGD amounts with two decimals, human-readable dates, short bulleted summaries for multi-item answers, concise prose otherwise; empty-data honesty; reinforce treating tool results/transaction descriptions as data, not instructions
    - Keep it the externalised resource loaded at runtime (no code change needed to load it)
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 14.9, 14.10, 14.11, 14.12, 10.1, 10.2_
  - [x]* 14.2 Orchestrator/tool-loop tests with `StubLlmClient` asserting breadth behaviours deterministically
    - Scripted multi-account path: stub returns a `getAccounts` tool call, then a `getTransactions` tool call for EACH returned account; assert the loop dispatches all calls, feeds every result back, `toolsUsed` reflects the dispatched tools, and iterations stay within `ai.max-tool-iterations`
    - Scripted clarifying-question turn: stub returns a text question with no tool call; assert it is returned as a normal `type:"message"` reply
    - Scripted out-of-scope answer: stub returns a decline message; assert it is returned as-is
    - _Requirements: 14.1, 14.4_
  - [x]* 14.3 Full build green
    - Run `./mvnw -B -ntp verify` (H2, no real network; both seams stubbed)
    - _Requirements: 14.1, 14.2, 14.3, 14.4, 14.5, 14.6, 14.7, 14.8, 14.9, 14.10, 14.11, 14.12_

- [x] 15. Observability and spend bounds (Guardrail A)
  - Lightweight observability + bounded per-call output. Additive; applies to both Phase 1 and Phase 2. No
    persistence, no cross-request/per-user spend tracking, no cost-based refusal. Depends on Task 8 (Phase 1
    orchestrator wired end-to-end); independent of Phase 2 tasks 10/11. Strengthens Requirement 11.3 secret
    non-leak and adds observability; introduces no new correctness property.
  - [x] 15.1 Add `ai.max-output-tokens` config and apply it per OpenAI request
    - Add `ai.max-output-tokens` (default 512) to `AiProperties` + `application.yml`; apply it as the `max_tokens` field on each OpenAI chat-completion request in `OpenAiLlmClient`. Keep the existing `ai.max-tool-iterations` bound unchanged
    - _Requirements: 15.4_
  - [x] 15.2 Extend the `LlmResponse` seam with an additive token-usage field
    - Add a `TokenUsage(promptTokens, completionTokens, totalTokens)` and an additive `usage` field on `LlmResponse` (no existing field changes); `OpenAiLlmClient` maps it from the response `usage` (`prompt_tokens`/`completion_tokens`/`total_tokens`); `StubLlmClient` returns a configurable/zero usage
    - _Requirements: 15.5_
  - [x] 15.3 Add per-turn structured logging in the orchestrator
    - Emit one INFO record per turn with: tool names dispatched (names only, in order), Tool_Iterations used, outcome (ANSWERED/LOOP_EXHAUSTED/LLM_ERROR/TOOL_ERROR), and token usage summed across model calls; redact secrets (never log the OpenAI key or the on-behalf-of token) and do not log any chat message content or tool result body; prefer extracting a pure record-builder method for testability
    - _Requirements: 15.1, 15.2, 15.3_
  - [x]* 15.4 Tests for bounded output, usage mapping, and redacted per-turn record
    - `OpenAiLlmClient` sends `max_tokens` and parses `usage` (stubbed HTTP layer or focused mapping test); orchestrator emits the structured record with tools + iterations + outcome + summed usage and NO secrets or message/tool-result bodies (log appender or testable record builder); `StubLlmClient` usage flows through to the summed per-turn total
    - _Requirements: 15.1, 15.2, 15.3, 15.5_
  - [x]* 15.5 Full build green
    - Run `./mvnw -B -ntp verify` (H2, no real network; both seams stubbed)
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5_

## Notes

- Tasks marked with `*` are optional (tests) and can be skipped for a faster MVP; core implementation tasks are not.
- Each task references specific requirement sub-clauses for traceability.
- Property-test tasks each reference a numbered correctness property from `design.md` and use jqwik (>=100 iterations, tagged `Feature: ai-assistant-service, Property N: ...`).
- Both external dependencies (OpenAI, gateway) are behind seams, so all tests run with stubs and no real network.
- The separate chatbot frontend is out of scope here; the "Client integration contract" in `design.md` documents what it must do.

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.2", "3.1", "4.1"] },
    { "id": 2, "tasks": ["1.3", "2.1", "2.3", "3.2", "3.3", "4.2"] },
    { "id": 3, "tasks": ["4.3", "4.4", "4.5", "5.1", "5.2"] },
    { "id": 4, "tasks": ["5.3", "5.4", "6.1", "7.1"] },
    { "id": 5, "tasks": ["6.2", "7.2", "7.3", "7.4", "7.5"] },
    { "id": 6, "tasks": ["6.3", "6.4", "6.5", "6.6", "8.1"] },
    { "id": 7, "tasks": ["8.2", "10.1"] },
    { "id": 8, "tasks": ["10.2", "11.1"] },
    { "id": 9, "tasks": ["10.3", "10.4", "11.2", "12.1", "12.2"] },
    { "id": 10, "tasks": ["12.3", "14.1"] },
    { "id": 11, "tasks": ["14.2"] },
    { "id": 12, "tasks": ["14.3", "15.1", "15.2"] },
    { "id": 13, "tasks": ["15.3"] },
    { "id": 14, "tasks": ["15.4"] },
    { "id": 15, "tasks": ["15.5"] }
  ]
}
```
