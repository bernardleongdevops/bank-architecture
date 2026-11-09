package sg.edu.rp.sandbox.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A bank account owned by a customer (identified by {@code customerRef}).
 * Balances use {@link BigDecimal} / DECIMAL to avoid floating-point money errors.
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_number", nullable = false, unique = true, updatable = false)
    private String accountNumber;

    @Column(name = "customer_ref", nullable = false, updatable = false)
    private String customerRef;

    @Column(name = "account_type", nullable = false)
    private String accountType;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    @Column(nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Optimistic-lock version. Guards the outbox poller's debit/credit writes
     * against concurrent/stale updates; a stale write triggers an optimistic-lock
     * failure so the enclosing poll transaction rolls back and retries later.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Account() {
        // for JPA
    }

    public Account(String accountNumber, String customerRef, String accountType,
                   String currency, BigDecimal balance, String status) {
        this.accountNumber = accountNumber;
        this.customerRef = customerRef;
        this.accountType = accountType;
        this.currency = currency;
        this.balance = balance;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getCustomerRef() {
        return customerRef;
    }

    public String getAccountType() {
        return accountType;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getVersion() {
        return version;
    }

    /** Decreases the balance by {@code amount} (source leg of a transfer). */
    public void debit(BigDecimal amount) {
        this.balance = this.balance.subtract(amount);
    }

    /** Increases the balance by {@code amount} (destination leg of a transfer). */
    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }
}
