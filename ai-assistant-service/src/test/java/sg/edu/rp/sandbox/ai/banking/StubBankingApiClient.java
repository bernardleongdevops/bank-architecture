package sg.edu.rp.sandbox.ai.banking;

import sg.edu.rp.sandbox.ai.dto.TransferRequest;
import sg.edu.rp.sandbox.ai.dto.TransferResult;
import sg.edu.rp.sandbox.common.error.ApiError;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Test fixture {@link BankingApiClient}: returns canned accounts/transactions, can
 * be forced to throw a specific HTTP outcome (401/403/404/409/422/429), and records
 * the on-behalf-of token it was called with (so web tests can assert the forwarded
 * bearer and that the client path attaches the sandbox key via the dispatcher).
 *
 * <p>Because this is a seam stub it performs no network I/O.
 */
public class StubBankingApiClient implements BankingApiClient {

    private final List<AccessToken> tokensSeen = new ArrayList<>();
    private final List<String> callsSeen = new ArrayList<>();
    private final List<TransferRequest> transfersSeen = new ArrayList<>();

    private List<Account> accounts = new ArrayList<>(List.of(
            new Account("ACC-1001", "SAVINGS", "SGD", new java.math.BigDecimal("5000.00"), "ACTIVE"),
            new Account("ACC-1002", "CURRENT", "SGD", new java.math.BigDecimal("1200.50"), "ACTIVE")));
    private List<Transaction> transactions = new ArrayList<>(List.of(
            new Transaction("TXN-1", "ACC-1001", "DEBIT", new java.math.BigDecimal("4.50"), "SGD",
                    "kopi at the hawker", "STALL-12", Instant.parse("2026-09-01T08:00:00Z")),
            new Transaction("TXN-2", "ACC-1001", "DEBIT", new java.math.BigDecimal("30.00"), "SGD",
                    "grab ride", "GRAB", Instant.parse("2026-09-02T18:00:00Z")),
            new Transaction("TXN-3", "ACC-1001", "CREDIT", new java.math.BigDecimal("3200.00"), "SGD",
                    "monthly salary", "ACME", Instant.parse("2026-09-25T00:00:00Z"))));
    private CustomerProfile profile = new CustomerProfile(
            "CUST-ALICE", "Alice Tan", "alice@example.com", "APPROVED",
            Instant.parse("2026-01-01T00:00:00Z"));

    private BankingApiException forcedError;

    // Default transfer outcome returned by makeTransfer; tests override per-case.
    private TransferResult transferResult =
            new TransferResult(201, "COMPLETED", "Done - your transfer was completed.");

    // ---- configuration ------------------------------------------------------

    public StubBankingApiClient withAccounts(List<Account> accounts) {
        this.accounts = new ArrayList<>(accounts);
        return this;
    }

    public StubBankingApiClient withTransactions(List<Transaction> transactions) {
        this.transactions = new ArrayList<>(transactions);
        return this;
    }

    public StubBankingApiClient withProfile(CustomerProfile profile) {
        this.profile = profile;
        return this;
    }

    /** Force the next call(s) to throw the given HTTP outcome. */
    public StubBankingApiClient forceStatus(int status, String errorCode, String message) {
        this.forcedError = new BankingApiException(status, errorCode, message,
                new ApiError(Instant.now(), status, errorCode, message, "/stub"));
        return this;
    }

    public void clearForcedStatus() {
        this.forcedError = null;
    }

    /** Force the outcome returned by {@link #makeTransfer}. */
    public StubBankingApiClient withTransferResult(TransferResult result) {
        this.transferResult = result;
        return this;
    }

    // ---- recording ----------------------------------------------------------

    public List<AccessToken> tokensSeen() {
        return List.copyOf(tokensSeen);
    }

    public AccessToken lastToken() {
        return tokensSeen.isEmpty() ? null : tokensSeen.get(tokensSeen.size() - 1);
    }

    public List<String> callsSeen() {
        return List.copyOf(callsSeen);
    }

    /** All transfer requests passed to {@link #makeTransfer}, in order. */
    public List<TransferRequest> transfersSeen() {
        return List.copyOf(transfersSeen);
    }

    /** @return true if {@link #makeTransfer} was ever invoked. */
    public boolean transferWasCalled() {
        return !transfersSeen.isEmpty();
    }

    public TransferRequest lastTransfer() {
        return transfersSeen.isEmpty() ? null : transfersSeen.get(transfersSeen.size() - 1);
    }

    // ---- BankingApiClient ---------------------------------------------------

    @Override
    public CustomerProfile getCustomerProfile(AccessToken token) {
        record("getCustomerProfile", token);
        return profile;
    }

    @Override
    public List<Account> getAccounts(AccessToken token) {
        record("getAccounts", token);
        return List.copyOf(accounts);
    }

    @Override
    public Account getAccount(AccessToken token, String accountNumber) {
        record("getAccount:" + accountNumber, token);
        return accounts.stream()
                .filter(a -> a.accountNumber().equals(accountNumber))
                .findFirst()
                .orElseThrow(() -> new BankingApiException(404, "NOT_FOUND",
                        "Account not found.", null));
    }

    @Override
    public List<Transaction> getTransactions(AccessToken token, String accountNumber, String from, String to) {
        record("getTransactions:" + accountNumber, token);
        return transactions.stream()
                .filter(t -> t.accountNumber().equals(accountNumber))
                .toList();
    }

    @Override
    public TransferResult makeTransfer(AccessToken token, TransferRequest request) {
        // Record the write distinctly so tests can assert it happened IFF a valid
        // confirmation arrived (Property 2). Record BEFORE honouring a forced error so
        // an attempted write is still visible to tests.
        callsSeen.add("makeTransfer:" + request.fromAccount() + "->" + request.toAccount());
        tokensSeen.add(token);
        transfersSeen.add(request);
        if (forcedError != null) {
            throw forcedError;
        }
        return transferResult;
    }

    private void record(String call, AccessToken token) {
        callsSeen.add(call);
        tokensSeen.add(token);
        if (forcedError != null) {
            throw forcedError;
        }
    }
}
