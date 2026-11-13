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
        description="One of: maven_network_or_cache, unit_test_failure, compilation_error, vm_resource_or_port, service_crash"
    )
    recommended_action: str = Field(
        description="One of: retry_maven, purge_m2_cache_and_retry, restart_backend_service, escalate_to_human"
    )
    confidence: float = Field(description="Confidence score between 0.0 and 1.0")
    suggested_fix_notes: str = Field(description="Actionable advice or exact class/line number for the developer.")

def redact_secrets(text: str) -> str:
    # Strip common tokens, passwords, and JDBC URLs before sending logs to Gemini
    text = re.sub(r"(?i)(password|secret|token|apikey|api_key)\s*[:=]\s*\S+", r"\1=[REDACTED]", text)
    text = re.sub(r"jdbc:[a-z0-9]+://\S+", "jdbc:[REDACTED]", text)
    return text

def main():
    stage = sys.argv[1] if len(sys.argv) > 1 else "maven_build"
    log_path = sys.argv[2] if len(sys.argv) > 2 else "ci_failure.log"

    if not os.path.exists(log_path):
        print('{"recommended_action": "escalate_to_human", "root_cause": "Log file missing"}')
        return

    with open(log_path, "r", encoding="utf-8", errors="ignore") as f:
        raw_logs = f.read()[-10000:]  # Keep the last 10,000 chars where Maven/deploy errors appear

    clean_logs = redact_secrets(raw_logs)

    client = genai.Client()
    prompt = f"""
    You are a Senior Java & DevOps SRE monitoring a banking backend monorepo (Java 25, Maven, Spring/H2).
    Analyze the failed '{stage}' log below.

    STRICT RULES:
    - Only choose 'retry_maven' or 'purge_m2_cache_and_retry' if the error is clearly a Maven Central timeout, 502/503 HTTP error, or corrupted .m2 artifact.
    - Only choose 'restart_backend_service' if 'local_deploy' failed due to a stale process/port lock or transient startup timeout.
    - For ANY failed JUnit/Surefire test, assertion error, compilation error, or database schema issue, you MUST choose 'escalate_to_human'.

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
