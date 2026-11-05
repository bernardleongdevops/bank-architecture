package sg.edu.rp.sandbox.account;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import sg.edu.rp.sandbox.security.ResourceServerSecurityConfig;

/**
 * Account Service - accounts and balances.
 *
 * <p>Imports the shared resource-server security so accounts are protected and
 * scoped to the authenticated customer. {@code @EnableScheduling} drives the
 * {@code AccountOutboxPoller} that projects completed transfers onto balances.
 */
@SpringBootApplication
@EnableScheduling
@Import(ResourceServerSecurityConfig.class)
public class AccountServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AccountServiceApplication.class, args);
    }
}
