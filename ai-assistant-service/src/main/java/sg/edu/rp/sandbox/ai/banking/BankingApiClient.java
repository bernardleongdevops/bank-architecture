package sg.edu.rp.sandbox.ai.banking;

import sg.edu.rp.sandbox.ai.dto.TransferRequest;
import sg.edu.rp.sandbox.ai.dto.TransferResult;

import java.util.List;

/**
 * Seam for the gateway/banking APIs. The <strong>only</strong> component that
 * talks to the gateway. An interface so tests can stub gateway responses.
 *
 * <p>Every method takes the on-behalf-of {@link AccessToken} and the
 * implementation always attaches {@code Authorization: Bearer <token>} and
 * {@code X-Sandbox-Key} so the existing services enforce ownership.
 *
 * <p>Phase 1 is read-only. {@link #makeTransfer} is the single Phase 2 write path;
 * it is only ever reached through the backend confirmation handshake and is never
 * exposed to the model as a tool.
 */
public interface BankingApiClient {

    /** {@code GET /customers/me} - the signed-in customer's own profile. */
    CustomerProfile getCustomerProfile(AccessToken token);

    /** {@code GET /accounts} - the signed-in customer's own accounts. */
    List<Account> getAccounts(AccessToken token);

    /** {@code GET /accounts/{accountNumber}} - one owned account by number. */
    Account getAccount(AccessToken token, String accountNumber);

    /**
     * {@code GET /transactions/accounts/{accountNumber}} - statement history over an
     * optional ISO-8601 date range.
     *
     * @param from optional inclusive lower bound (ISO-8601), or null
     * @param to   optional inclusive upper bound (ISO-8601), or null
     */
    List<Transaction> getTransactions(AccessToken token, String accountNumber, String from, String to);

    /**
     * {@code POST /payments/transfers} - execute a confirmed transfer (Phase 2).
     *
     * <p>Reached only from the backend confirmation handshake, never from a model
     * tool call. Sends {@code { fromAccount, toAccount, amount, description }} with
     * the on-behalf-of bearer and {@code X-Sandbox-Key}; ownership is enforced by
     * payment-service. Maps the payment-service response status (201/202/422/409/403)
     * to a {@link TransferResult} with a canonical outcome and a plain-language
     * message, preserving the gateway error body for a 409 conflict.
     *
     * @param token   the on-behalf-of access token
     * @param request the transfer body reconstructed from the verified intent
     * @return the mapped transfer result (never throws for 201/202/422/409/403)
     */
    TransferResult makeTransfer(AccessToken token, TransferRequest request);
}
