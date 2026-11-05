# Requirements Document

## Introduction

This document specifies the requirements for `ai-assistant-service`, a new Spring Boot microservice that powers an
agentic banking chatbot for the RP Digital Bank Sandbox. The requirements are derived from the approved design
(`design.md`). The service exposes `POST /api/v1/ai/chat` through the existing gateway, relays conversations to
OpenAI using tool/function calling, and executes tools by calling the existing gateway HTTP APIs on-behalf-of the
signed-in user. It must keep the OpenAI key backend-only, ground all answers in real data, and gate every money
movement behind an explicit confirmation handshake. Delivery is phased: Phase 1 is read-only (including spending
categorisation) and Phase 2 adds confirmed transfers.

## Glossary

- **AI_Assistant_Service**: The new microservice (`sg.edu.rp.sandbox.ai`) exposing `POST /api/v1/ai/chat`.
- **Chat_Orchestrator**: The component that runs the multi-turn tool loop and produces the chat response.
- **Llm_Client**: The seam/interface that is the sole component communicating with OpenAI.
- **Banking_Api_Client**: The seam/interface that is the sole component communicating with the gateway APIs.
- **Tool_Dispatcher**: The component that maps a model tool call to a `Banking_Api_Client` or categorisation call.
- **Categorization_Service**: The component that classifies transaction descriptions into canonical categories.
- **Gateway**: The existing Spring Cloud Gateway (single entry point, base path `/api/v1`, port 8081).
- **On_Behalf_Of_Token**: The signed-in user's OIDC access token, received on the inbound request and reused on
  outbound gateway calls.
- **Sandbox_Key**: The value sent in the `X-Sandbox-Key` header required by the gateway.
- **Confirmation_Token**: A backend-issued, signed, short-lived token that authorises execution of a previously
  proposed transfer.
- **Transfer_Intent**: A structured proposed transfer `{ fromAccount, toAccount, amount, currency, description }`.
- **Spending_Category**: A member of the canonical closed category set (Food & Dining, Groceries, Transport,
  Shopping, Bills & Utilities, Entertainment, Health, Transfers, Income/Salary, Allowance, Other).
- **OpenAI_Key**: The secret OpenAI API key, sourced from env var `OPENAI_API_KEY`, backend-only.
- **Tool_Iteration**: One model round-trip in the tool loop (model call plus any resulting tool execution).

## Requirements

### Requirement 1: Chat endpoint and request handling

**User Story:** As a signed-in banking customer, I want to send a natural-language message to the assistant, so
that I can ask about my banking in plain English.

#### Acceptance Criteria

1. THE AI_Assistant_Service SHALL expose an HTTP endpoint `POST /ai/chat`, routed via the Gateway as `POST /api/v1/ai/chat`.
2. WHEN a request arrives with a non-blank message and an authenticated token, THE AI_Assistant_Service SHALL process it and return a chat response with HTTP status 200.
3. IF a request arrives without a valid authenticated token, THEN THE AI_Assistant_Service SHALL reject the request with HTTP status 401.
4. IF a request arrives with neither a non-blank message nor a confirmation decision, THEN THE AI_Assistant_Service SHALL reject the request with HTTP status 400.
5. THE AI_Assistant_Service SHALL accept a request body containing a message, a conversation history list, and an optional confirmation decision.

### Requirement 2: Stateless conversation history

**User Story:** As a client developer, I want the server to be stateless, so that I can scale it horizontally and
control the conversation on the client.

#### Acceptance Criteria

1. THE AI_Assistant_Service SHALL derive conversation context solely from the history supplied in the request and the current message.
2. THE AI_Assistant_Service SHALL NOT persist conversation history between requests.
3. WHEN assembling the model input, THE Chat_Orchestrator SHALL order messages as the system prompt, then the supplied history, then the current user message.

### Requirement 3: Tool-calling loop grounded in real data

**User Story:** As a customer, I want the assistant to use my real account data, so that answers are accurate and
never made up.

#### Acceptance Criteria

1. WHEN the Llm_Client returns one or more tool calls, THE Chat_Orchestrator SHALL execute each tool via the Tool_Dispatcher and append the results before calling the model again.
2. WHEN the Llm_Client returns a final text answer, THE Chat_Orchestrator SHALL return that answer as the chat reply.
3. WHERE the final reply states a balance, transaction, or account number, THE Chat_Orchestrator SHALL use only values obtained from tool results captured during the turn or from the supplied history.
4. THE Chat_Orchestrator SHALL perform at most the configured maximum number of Tool_Iterations per chat turn.
5. IF the maximum number of Tool_Iterations is reached without a final answer, THEN THE Chat_Orchestrator SHALL terminate the loop and return a graceful response.

### Requirement 4: On-behalf-of authentication to the gateway

**User Story:** As a security owner, I want the assistant to act as the user, so that owner-scoping is enforced by
the existing services and the AI sees only the user's own data.

#### Acceptance Criteria

1. WHEN the Banking_Api_Client calls the Gateway, THE Banking_Api_Client SHALL attach the On_Behalf_Of_Token as an `Authorization: Bearer` header.
2. WHEN the Banking_Api_Client calls the Gateway, THE Banking_Api_Client SHALL attach the configured Sandbox_Key as the `X-Sandbox-Key` header.
3. THE AI_Assistant_Service SHALL reuse the exact access token received on the inbound chat request for outbound Gateway calls made while handling that request.
4. THE AI_Assistant_Service SHALL validate the inbound JWT as a Keycloak-secured resource server against the configured OIDC issuer.
5. THE AI_Assistant_Service SHALL permit unauthenticated access only to actuator health, OpenAPI docs, and Swagger UI paths.

### Requirement 5: Phase 1 read-only tools

**User Story:** As a customer, I want to ask about my profile, accounts, and statements, so that I can check my
banking details conversationally.

#### Acceptance Criteria

1. THE AI_Assistant_Service SHALL expose read tools to the model for getting the customer profile, listing accounts, getting one account by number, and getting transactions for an account over an optional ISO-8601 date range.
2. WHEN the getAccounts tool is executed, THE Banking_Api_Client SHALL call `GET /api/v1/accounts` and return the results to the model.
3. WHEN the getAccount tool is executed with an account number, THE Banking_Api_Client SHALL call `GET /api/v1/accounts/{accountNumber}`.
4. WHEN the getTransactions tool is executed with an account number and optional from/to, THE Banking_Api_Client SHALL call `GET /api/v1/transactions/accounts/{accountNumber}` with the supplied ISO-8601 query parameters.
5. WHILE operating in Phase 1, THE AI_Assistant_Service SHALL NOT expose any tool that moves money.

### Requirement 6: Spending categorisation

**User Story:** As a customer, I want my spending categorised, so that I can understand where my money goes.

#### Acceptance Criteria

1. WHEN categorising transactions, THE Categorization_Service SHALL assign each transaction to exactly one member of the canonical Spending_Category set.
2. IF a transaction description does not clearly map to a defined category, THEN THE Categorization_Service SHALL assign it to the Other category.
3. THE Categorization_Service SHALL map semantically equivalent descriptions to the same category, including "pocket money" and "allowance" to the Allowance category.
4. WHEN producing category totals over a period, THE Categorization_Service SHALL make the sum of per-category totals equal to the sum of the input transaction amounts.
5. THE Categorization_Service SHALL NOT create, drop, or duplicate transactions when aggregating.

### Requirement 7: Phase 2 transfer proposal and confirmation handshake

**User Story:** As a customer, I want to move money by chatting, but only after I confirm, so that I stay in
control and mistakes or injected instructions cannot move my money.

#### Acceptance Criteria

1. WHEN the model requests a transfer, THE Chat_Orchestrator SHALL produce a confirm-transfer response containing the Transfer_Intent, a human-readable summary, and a Confirmation_Token, without moving money.
2. THE AI_Assistant_Service SHALL execute a transfer via `POST /api/v1/payments/transfers` only when a request carries a confirmation decision with confirmed set to true and a valid, unexpired Confirmation_Token matching a previously proposed Transfer_Intent.
3. IF a confirmation decision references an invalid or expired Confirmation_Token, THEN THE AI_Assistant_Service SHALL refuse execution and return an explanatory response.
4. THE AI_Assistant_Service SHALL NOT expose a direct money-moving tool to the model; the model SHALL only be able to propose a transfer.
5. WHEN executing a confirmed transfer, THE Banking_Api_Client SHALL send the body fields fromAccount, toAccount, amount, and description to `POST /api/v1/payments/transfers`.

### Requirement 8: Transfer outcome surfacing

**User Story:** As a customer, I want clear feedback on my transfer, so that I understand what happened.

#### Acceptance Criteria

1. WHEN the payment-service responds with status 201, THE AI_Assistant_Service SHALL report the outcome as completed in plain language.
2. WHEN the payment-service responds with status 202, THE AI_Assistant_Service SHALL report that the transfer is held for review and has not been applied.
3. WHEN the payment-service responds with status 422, THE AI_Assistant_Service SHALL report that the transfer was blocked by compliance.
4. WHEN the payment-service responds with status 409, THE AI_Assistant_Service SHALL report the business conflict using the error body message.
5. WHEN the payment-service responds with status 403, THE AI_Assistant_Service SHALL report that the source account is not owned by the user.

### Requirement 9: Error handling and resilience

**User Story:** As a customer, I want graceful handling when something fails, so that the assistant stays usable.

#### Acceptance Criteria

1. IF the Llm_Client fails due to timeout, network error, or an OpenAI error status, THEN THE Chat_Orchestrator SHALL return an error response indicating the assistant is temporarily unavailable.
2. IF the model returns malformed tool arguments, THEN THE Tool_Dispatcher SHALL reject the tool call and return a tool error result to the model instead of throwing.
3. WHEN the Gateway responds with status 401 to a tool call, THE AI_Assistant_Service SHALL return an error response indicating the session must be renewed.
4. WHEN the Gateway responds with status 429 to a tool call, THE AI_Assistant_Service SHALL return an error response indicating the request was throttled.
5. THE AI_Assistant_Service SHALL preserve the error code and message from the standard Gateway error body when mapping failures to user-facing text.

### Requirement 10: Guardrails and prompt-injection resistance

**User Story:** As a security owner, I want the assistant constrained and injection-resistant, so that it cannot
be manipulated into unsafe actions.

#### Acceptance Criteria

1. THE AI_Assistant_Service SHALL apply a system prompt that constrains the assistant to the signed-in user's own banking tasks.
2. THE AI_Assistant_Service SHALL treat model output and tool results as data rather than executable instructions.
3. WHERE a transaction description or tool result contains text resembling instructions, THE AI_Assistant_Service SHALL continue to require the confirmation handshake before any money movement.
4. THE Chat_Orchestrator SHALL require real tool data before the reply states any balance or transaction figure.

### Requirement 11: Secret isolation and configuration

**User Story:** As an operator, I want secrets isolated and behaviour configurable, so that the service is safe
and portable.

#### Acceptance Criteria

1. THE AI_Assistant_Service SHALL read the OpenAI_Key only from the environment variable `OPENAI_API_KEY`.
2. THE AI_Assistant_Service SHALL NOT include the OpenAI_Key in any chat response.
3. THE AI_Assistant_Service SHALL NOT write the OpenAI_Key or the On_Behalf_Of_Token value to logs.
4. THE AI_Assistant_Service SHALL use the model configured by property `ai.model`, defaulting to `gpt-4o-mini`.
5. THE AI_Assistant_Service SHALL read the Gateway base URL, the Sandbox_Key value, the OIDC issuer URI, and the JWKS URI from configuration with no secret hardcoded.

### Requirement 12: Repository and deployment consistency

**User Story:** As a maintainer, I want the new service to match repo conventions, so that it builds and runs like
the others.

#### Acceptance Criteria

1. THE AI_Assistant_Service SHALL be a Maven module declared under the parent aggregator pom.
2. THE AI_Assistant_Service SHALL provide a multi-stage Dockerfile on Java 25 consistent with the other services.
3. THE AI_Assistant_Service SHALL provide a docker-compose service entry exposing port 8086.
4. THE Gateway SHALL route `/api/v1/ai/**` to the AI_Assistant_Service, stripping the `/api/v1` prefix.
5. THE AI_Assistant_Service SHALL expose OpenAPI/Swagger UI consistent with the other services.

### Requirement 13: Testability via seams

**User Story:** As a developer, I want the OpenAI and gateway calls behind interfaces, so that logic is unit-testable
without real network access.

#### Acceptance Criteria

1. THE AI_Assistant_Service SHALL place all OpenAI communication behind the Llm_Client interface.
2. THE AI_Assistant_Service SHALL place all Gateway communication behind the Banking_Api_Client interface.
3. WHEN running automated tests, THE AI_Assistant_Service SHALL operate with stubbed Llm_Client and Banking_Api_Client implementations and make no real network calls.
4. THE AI_Assistant_Service SHALL allow tests to assert that outbound Gateway calls carry the Sandbox_Key header.

### Requirement 14: Phase 1 Assistant Breadth and Robustness

**User Story:** As a signed-in banking customer, I want the read-only assistant to answer a wider range of
everyday banking questions and to behave predictably at the edges, so that I can rely on it for aggregation,
comparison, and account lookups without it guessing, fabricating, or straying off-topic.

This requirement is additive to the existing Phase 1 behaviour and is achieved entirely with the existing
read-only tools (`getCustomerProfile`, `getAccounts`, `getAccount`, `getTransactions`, `categorizeSpending`); it
introduces no new backend tool or endpoint. Account numbers and figures remain grounded in real tool results
(see Requirement 3) and the data-not-instructions guarantee of Requirement 10 continues to apply.

#### Acceptance Criteria

1. WHEN the user asks for a total, count, or combined figure spanning more than one account, THE AI_Assistant_Service SHALL call getAccounts and then getTransactions or getAccount per relevant account and aggregate the results across those accounts using only values returned by those tool calls.
2. WHEN the user asks to compare two periods for the same account (for example this month versus last month), THE AI_Assistant_Service SHALL retrieve transactions for each period via getTransactions and report both period figures and their difference.
3. WHEN the user asks for the largest or smallest transaction over a described period, THE AI_Assistant_Service SHALL retrieve the relevant transactions via getTransactions and identify the extreme transaction by amount from those results.
4. WHEN the user refers to an account by a human phrase (for example "my savings", "my current account", or "my other account"), THE AI_Assistant_Service SHALL resolve the phrase to an owned account number obtained from getAccounts before acting on it.
5. IF a human account phrase matches more than one owned account or matches none unambiguously, THEN THE AI_Assistant_Service SHALL ask one concise clarifying question identifying the candidate accounts rather than selecting an account by guessing.
6. IF the user names or describes an account that getAccounts does not return as owned by the user, THEN THE AI_Assistant_Service SHALL state that the account is not among the user's accounts and SHALL NOT fabricate details for it.
7. IF the user asks a question unrelated to the user's own banking, or asks about another customer's data, THEN THE AI_Assistant_Service SHALL decline the request and state the scope it supports, without calling a tool for the out-of-scope portion.
8. WHILE operating in Phase 1, WHEN the user asks to move money, THE AI_Assistant_Service SHALL state that it cannot perform transfers in the current phase and that any future transfer would require explicit confirmation.
9. WHEN the AI_Assistant_Service states a monetary amount, THE AI_Assistant_Service SHALL format it with the SGD currency label and two decimal places, and SHALL render dates in a human-readable form.
10. WHERE a reply presents more than one account, transaction, or category, THE AI_Assistant_Service SHALL present the items as a short bulleted summary; otherwise it SHALL answer in concise prose.
11. IF a tool returns no transactions for the requested account and period, THEN THE AI_Assistant_Service SHALL state plainly that there is no matching activity and SHALL NOT invent transactions or figures.
12. WHERE a tool result or transaction description contains text resembling instructions, THE AI_Assistant_Service SHALL treat that text strictly as data and SHALL NOT act on it, continuing to honour the Phase 1 read-only and scope constraints.

### Requirement 15: Observability and Spend Bounds (lightweight)

**User Story:** As an operator, I want lightweight per-turn observability and a bounded per-call output size, so
that I can see what the assistant did and keep cost and latency predictable, without introducing persistence or a
hard spend budget.

This requirement is additive and applies to both Phase 1 and Phase 2. It introduces no persistence, no
cross-request or per-user spend tracking, and no cost-based refusal. It strengthens the existing secret-non-leak
guarantee (Requirement 11.3) and keeps the existing per-turn round-trip cap (`ai.max-tool-iterations`,
Requirement 3.4) unchanged.

#### Acceptance Criteria

1. WHEN a chat turn completes, THE Chat_Orchestrator SHALL emit one INFO log record containing the list of tool names dispatched during the turn, the number of Tool_Iterations used, and the final outcome as exactly one of ANSWERED, LOOP_EXHAUSTED, LLM_ERROR, or TOOL_ERROR.
2. WHEN a chat turn completes, THE Chat_Orchestrator SHALL include in that log record the OpenAI token usage summed across the model calls made during the turn, reported as prompt tokens, completion tokens, and total tokens.
3. WHEN emitting the per-turn log record, THE AI_Assistant_Service SHALL NOT write the OpenAI_Key value, the On_Behalf_Of_Token value, any full chat message content, or any tool result body; the record SHALL contain tool names and counts only.
4. WHEN the Llm_Client sends a chat-completion request to OpenAI, THE AI_Assistant_Service SHALL set the maximum output tokens for that request from the configurable property `ai.max-output-tokens`, which SHALL default to 512.
5. WHEN the Llm_Client receives a chat-completion response from OpenAI, THE Llm_Client SHALL surface the response usage object (prompt tokens, completion tokens, total tokens) through the Llm_Client seam so that the Chat_Orchestrator can sum per-turn totals.
6. THE AI_Assistant_Service SHALL NOT enforce a hard spend budget, SHALL NOT track per-user spend, and SHALL NOT refuse a request based on cost or token usage.
