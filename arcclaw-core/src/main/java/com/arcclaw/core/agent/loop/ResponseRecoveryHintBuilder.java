package com.arcclaw.core.agent.loop;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Builds system hints and correction directives to help the LLM recover from common failures
 * like schema errors, model collapse, or malformed tool calls.
 */
@ApplicationScoped
public class ResponseRecoveryHintBuilder {

    public String buildDegradedResponseHint() {
        return "System: Your last response was empty, contained only one token, or lacked a FINISH answer. " +
               "This often happens due to infrastructure limits or model collapse after a rate-limit hit. " +
               "Please retry now with your full thought process and a complete FINISH answer.";
    }

    public String buildRawJsonInAnswerHint() {
        return "System: Your previous response contained raw JSON parameters in the prose answer field. " +
               "STRICT RULE: You MUST NOT output JSON in the 'answer' field. " +
               "If you want to perform an action, you MUST use a real structured tool call (e.g. schedule_task). " +
               "Please retry now using a proper tool call.";
    }

    public String buildHallucinatedAgentToolHint(String agentName) {
        return String.format(
            "System: You called '%s' as if it were a tool, but that is an agent name. " +
            "STRICT RULE: Only use 'delegate_to_agent' for delegation. " +
            "Example: delegate_to_agent(agent=\"%s\", goal=\"...\"). " +
            "Retry now with the correct tool call.",
            agentName, agentName);
    }

    public String buildSchemaCorrectionHint(String errorMessage) {
        return "System: Your previous tool call was rejected. Error: " + errorMessage +
               ". Please review the tool schema and correct your arguments.";
    }

    public String buildNullValueCorrectionHint(String toolRef) {
        return "System: Your previous tool call " + toolRef + "failed because you included an " +
               "optional field with a null value. " +
               "RULE: Omit optional fields entirely when you have no value for them. " +
               "Retry now with only the fields that have real values.";
    }

    public String buildMalformedDelegateHint() {
        return """
            System: Your delegate_to_agent call was malformed. STRICT RULES:
            1. Call delegate_to_agent EXACTLY ONCE per turn - never batch multiple agents.
            2. Pass exactly TWO flat string parameters only:
            {"agent": "<name>", "goal": "<self-contained task>"}
            3. Do NOT wrap in an array []. Do NOT nest under "parameters" or any other key.
            4. Valid agent names (one at a time): brahma, xiva, saraswati, brihaspati, dattatreya. 
            Retry now with a single flat call - one agent, one goal string.""";
    }

    public String buildGenericToolSchemaHint(String toolRef, String rawError) {
        return "System: Your previous tool call " + toolRef + "was rejected due to a schema validation error: "
               + rawError
               + ". Review the tool schema carefully, correct the invalid fields, and retry.";
    }

    public String getFallbackDirective() {
        return " If no tool is needed, do NOT call any function. " +
               "Produce a FINISH action with your response in the 'thought' field only.";
    }
}
