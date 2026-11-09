package sg.edu.rp.sandbox.payment.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import sg.edu.rp.sandbox.common.event.EventPublisher;
import sg.edu.rp.sandbox.payment.domain.OutboxRepository;

/**
 * Registers the {@link OutboxEventPublisher} only when
 * {@code provider.events=outbox}.
 *
 * <p>The in-process publisher in {@code events-adapters} is guarded by
 * {@code @ConditionalOnProperty(provider.events=in-process, matchIfMissing=true)}:
 * with {@code outbox} its condition fails, so this bean is the single active
 * {@link EventPublisher}. Other services never set {@code provider.events} and
 * keep the in-process default without any outbox beans.
 */
@Configuration
public class OutboxPublisherConfiguration {

    @Bean
    @ConditionalOnProperty(name = "provider.events", havingValue = "outbox")
    EventPublisher outboxEventPublisher(OutboxRepository outbox, ObjectMapper objectMapper) {
        return new OutboxEventPublisher(outbox, objectMapper);
    }
}
