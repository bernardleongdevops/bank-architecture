package sg.edu.rp.sandbox.ai.banking;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * The signed-in customer's record, as returned by {@code GET /api/v1/customers/me}.
 * Unknown/nested profile fields are ignored; this service only needs the headline
 * identity and KYC fields.
 *
 * @param customerRef e.g. CUST-ALICE
 * @param fullName    the customer's name
 * @param email       contact email
 * @param kycStatus   e.g. APPROVED
 * @param createdAt   when the customer was created
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomerProfile(
        String customerRef,
        String fullName,
        String email,
        String kycStatus,
        Instant createdAt) {
}
