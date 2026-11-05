package sg.edu.rp.sandbox.ai.orchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import sg.edu.rp.sandbox.ai.config.AiProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for phase-aware system-prompt composition.
 *
 * <p>When {@code ai.transfers-enabled=false} the prompt must be the read-only Phase 1
 * guidance and must NOT mention {@code proposeTransfer}. When
 * {@code ai.transfers-enabled=true} the prompt must mention {@code proposeTransfer} and
 * the confirm-before-move rule, and must NOT leave a flat read-only contradiction
 * ("you cannot move money" / "transfers are not available") standing.
 *
 * <p>Validates: Requirement 14 (read-only wording preserved when disabled) and the
 * Phase 2 transfers-enabled prompt guidance.
 */
class SystemPromptProviderTest {

    private static Resource base() {
        return new ClassPathResource("system-prompt.txt");
    }

    private static Resource transfersFragment() {
        return new ClassPathResource("system-prompt-transfers.txt");
    }

    private static AiProperties props(boolean transfersEnabled) {
        AiProperties p = new AiProperties();
        p.setTransfersEnabled(transfersEnabled);
        return p;
    }

    @Test
    void transfersDisabled_keepsReadOnlyGuidance_andDoesNotMentionProposeTransfer() {
        String prompt = new SystemPromptProvider(base(), transfersFragment(), props(false)).prompt();

        assertThat(prompt).contains("read-only assistant");
        assertThat(prompt).contains("You cannot move money");
        // The base prompt wraps this phrase across a line break; assert on the
        // distinctive fragment that survives wrapping.
        assertThat(prompt).contains("transfers are not available");
        assertThat(prompt).doesNotContain("proposeTransfer");
    }

    @Test
    void transfersEnabled_mentionsProposeTransfer_andConfirmBeforeMove() {
        String prompt = new SystemPromptProvider(base(), transfersFragment(), props(true)).prompt();

        assertThat(prompt).contains("proposeTransfer");
        // Confirm-before-move rule must be present.
        assertThat(prompt).contains("confirm");
        assertThat(prompt).containsIgnoringCase("never moves money by itself");
        // Injection resistance preserved (data, not instructions).
        assertThat(prompt).contains("DATA");
    }

    @Test
    void transfersEnabled_supersedesTheReadOnlyContradiction() {
        String prompt = new SystemPromptProvider(base(), transfersFragment(), props(true)).prompt();

        // The enabled prompt must explicitly supersede the read-only wording so the
        // model does not keep refusing. A superseding statement must be present.
        assertThat(prompt).containsIgnoringCase("SUPERSEDES");
        assertThat(prompt).containsIgnoringCase("available now");
    }
}
