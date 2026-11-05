package sg.edu.rp.sandbox.ai.tools;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 4: No money-moving tool is ever exposed to the model.
 *
 * <p>For Phase 1 the exposed tool set must contain none of the money-moving tool
 * names (no {@code makeTransfer}, no {@code proposeTransfer}), and no exposed tool
 * name hints at moving money. Also asserts the exact Phase 1 read set.
 *
 * <p>Validates: Requirements 5.5, 7.4
 */
@Tag("Feature: ai-assistant-service, Property 4")
class NoMoneyMovingToolProperties {

    private final ToolRegistry registry = new ToolRegistry();

    private static final List<String> MONEY_MOVING_NAMES = List.of(
            "makeTransfer", "proposeTransfer", "transfer", "pay", "payment",
            "sendMoney", "withdraw", "deposit");

    @Property(tries = 200)
    void phase1NeverExposesAnyMoneyMovingTool(
            @ForAll @AlphaChars @StringLength(min = 1, max = 20) String ignoredNoise) {
        List<String> exposed = registry.phase1Definitions().stream()
                .map(ToolDefinition::name)
                .toList();

        // The exposed set is exactly the five Phase 1 read tools.
        assertThat(exposed).containsExactlyInAnyOrder(
                "getCustomerProfile", "getAccounts", "getAccount", "getTransactions", "categorizeSpending");

        // None of the known money-moving tool names appear.
        for (String banned : MONEY_MOVING_NAMES) {
            assertThat(registry.isPhase1Tool(banned)).isFalse();
            assertThat(exposed).doesNotContain(banned);
        }

        // Defensively: no exposed Phase 1 tool name even hints at moving money.
        for (String name : exposed) {
            String lower = name.toLowerCase(Locale.ROOT);
            assertThat(lower).doesNotContain("transfer");
            assertThat(lower).doesNotContain("pay");
            assertThat(lower).doesNotContain("withdraw");
            assertThat(lower).doesNotContain("deposit");
            assertThat(lower).doesNotContain("send");
        }
    }

    @Property(tries = 200)
    void noMakeTransferToolInAnyPhase(@ForAll boolean transfersEnabled) {
        // Property 4 must hold even with Phase 2 (transfers) enabled: proposeTransfer
        // only DRAFTS an intent (it moves no money) and there is NEVER a makeTransfer
        // tool exposed to the model.
        List<String> exposed = registry.definitions(transfersEnabled).stream()
                .map(ToolDefinition::name)
                .toList();

        assertThat(registry.isPhase1Tool("makeTransfer")).isFalse();
        assertThat(exposed).doesNotContain("makeTransfer");
        for (String name : exposed) {
            assertThat(name.toLowerCase(Locale.ROOT)).doesNotContain("maketransfer");
        }

        if (transfersEnabled) {
            // Phase 2 exposes exactly the five read tools plus proposeTransfer.
            assertThat(exposed).containsExactlyInAnyOrder(
                    "getCustomerProfile", "getAccounts", "getAccount", "getTransactions",
                    "categorizeSpending", "proposeTransfer");
        } else {
            assertThat(exposed).containsExactlyInAnyOrder(
                    "getCustomerProfile", "getAccounts", "getAccount", "getTransactions",
                    "categorizeSpending");
        }
    }
}
