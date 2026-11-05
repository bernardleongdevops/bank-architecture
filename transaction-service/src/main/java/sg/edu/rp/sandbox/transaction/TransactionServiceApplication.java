package sg.edu.rp.sandbox.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import sg.edu.rp.sandbox.security.ResourceServerSecurityConfig;

/**
 * Transaction Service - append-only ledger and history.
 *
 * <p>Imports the shared resource-server security so transaction history is
 * scoped to the authenticated customer. Scheduling is enabled so the
 * {@code TransactionOutboxPoller} runs on a fixed delay.
 */
@SpringBootApplication
@EnableScheduling
@Import(ResourceServerSecurityConfig.class)
public class TransactionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}
