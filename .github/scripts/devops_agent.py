import os
import re
import sys
from pydantic import BaseModel, Field
from google import genai
from google.genai import types

class RemediationPlan(BaseModel):
    job_stage: str = Field(description="Either 'maven_build' or 'local_deploy'")
    root_cause: str = Field(description="Concise technical summary of the failure.")
    category: str = Field(
        description="One of: maven_network_or_cache, unit_test_failure, compilation_error, docker_disk_or_build, service_startup_timeout, app_runtime_crash"
    )
    recommended_action: str = Field(
        description="One of: retry_maven, purge_m2_cache_and_retry, restart_compose_services, prune_docker_and_retry, rollback_to_previous, escalate_to_human"
    )
    confidence: float = Field(description="Confidence score between 0.0 and 1.0")
    suggested_fix_notes: str = Field(description="Actionable advice or failing service/class name for the developer.")

def redact_secrets(text: str) -> str:
    text = re.sub(r"(?i)(password|secret|token|apikey|api_key|confirmation_secret)\s*[:=]\s*\S+", r"\1=[REDACTED]", text)
    text = re.sub(r"jdbc:[a-z0-9]+://\S+", "jdbc:[REDACTED]", text)
    return text

def main():
    stage = sys.argv[1] if len(sys.argv) > 1 else "maven_build"
    log_path = sys.argv[2] if len(sys.argv) > 2 else "ci_failure.log"

    if not os.path.exists(log_path):
        print('{"recommended_action": "escalate_to_human", "root_cause": "Log file missing", "confidence": 0.0, "suggested_fix_notes": "Check runner permissions."}')
        return

    with open(log_path, "r", encoding="utf-8", errors="ignore") as f:
        raw_logs = f.read()[-12000:]

    clean_logs = redact_secrets(raw_logs)

    client = genai.Client()
    prompt = f"""
    You are a Senior Java & DevOps SRE monitoring a banking backend monorepo (Java 25, Maven, Spring Boot microservices, MySQL, Keycloak, Docker Compose).
    Analyze the failed '{stage}' log below.

    STRICT RULES FOR 'maven_build':
    - Choose 'retry_maven' or 'purge_m2_cache_and_retry' ONLY for Maven Central network/HTTP timeouts or corrupted .m2 jars.
    - Choose 'escalate_to_human' for ANY JUnit test failure or compilation error.

    STRICT RULES FOR 'local_deploy':
    - Choose 'prune_docker_and_retry' ONLY if Docker failed with 'no space left on device' during build or image pull.
    - Choose 'restart_compose_services' ONLY if a container (such as Keycloak or a Spring Boot service) timed out waiting for healthcheck/actuator readiness without a fatal Java exception.
    - Choose 'rollback_to_previous' if a newly built microservice crashed on startup (e.g., BeanCreationException, Flyway/Liquibase migration error, Port already in use, or non-zero exit code).

    LOG OUTPUT:
    {clean_logs}
    """

    response = client.models.generate_content(
        model="gemini-3.5-flash-lite",
        contents=prompt,
        config=types.GenerateContentConfig(
            response_mime_type="application/json",
            response_schema=RemediationPlan,
            temperature=0.1,
        ),
    )

    plan = RemediationPlan.model_validate_json(response.text)
    with open("remediation_plan.json", "w", encoding="utf-8") as out:
        out.write(plan.model_dump_json(indent=2))
    print(plan.model_dump_json(indent=2))

if __name__ == "__main__":
    main()
