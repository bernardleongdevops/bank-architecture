@org.junit.jupiter.api.Test
void intentionalFailureForAiTriage() {
	org.junit.jupiter.api.Assertions.fail("Simulated database connection pool exhaustion during integration test");
}
