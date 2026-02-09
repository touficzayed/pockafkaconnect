package com.banking.kafka.transforms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Manager for PGP encryption rules based on event type and version.
 *
 * Rule format: EventType:Version:Action
 * - EventType: PAYMENT, REFUND, etc. (or * for wildcard)
 * - Version: v1, v2, 1.0, etc. (or * for wildcard)
 * - Action: ENCRYPT or SKIP
 *
 * Examples:
 * - PAYMENT:*:ENCRYPT     (all PAYMENT events encrypted)
 * - PAYMENT:v1:SKIP       (skip v1 payments)
 * - PAYMENT:v2:ENCRYPT    (only v2 payments encrypted)
 * - *:*:ENCRYPT           (encrypt all events)
 *
 * Rules are evaluated in order, first match wins.
 */
public class PGPEncryptionRuleManager {

    private static final Logger log = LoggerFactory.getLogger(PGPEncryptionRuleManager.class);

    private static final String WILDCARD = "*";
    private static final String RULE_SEPARATOR = ",";
    private static final String FIELD_SEPARATOR = ":";

    public enum Action {
        ENCRYPT,
        SKIP
    }

    /**
     * Rule for matching event type and version to an action
     */
    private static class EncryptionRule {
        final String eventType;
        final String version;
        final Action action;

        EncryptionRule(String eventType, String version, Action action) {
            this.eventType = eventType;
            this.version = version;
            this.action = action;
        }

        boolean matches(String event, String ver) {
            return (WILDCARD.equals(eventType) || eventType.equalsIgnoreCase(event))
                && (WILDCARD.equals(version) || version.equalsIgnoreCase(ver));
        }

        @Override
        public String toString() {
            return eventType + ":" + version + ":" + action;
        }
    }

    private final List<EncryptionRule> rules;
    private final Action defaultAction;

    /**
     * Create a rule manager with parsed rules.
     *
     * @param rulesString Comma-separated rules (e.g., "PAYMENT:*:ENCRYPT,REFUND:v1:SKIP")
     * @param defaultAction Default action if no rule matches (typically ENCRYPT)
     */
    public PGPEncryptionRuleManager(String rulesString, Action defaultAction) {
        this.rules = new ArrayList<>();
        this.defaultAction = defaultAction;

        if (rulesString != null && !rulesString.trim().isEmpty()) {
            parseRules(rulesString);
        }
    }

    /**
     * Parse rules from a comma-separated string.
     */
    private void parseRules(String rulesString) {
        String[] ruleParts = rulesString.split(RULE_SEPARATOR);

        for (String rulePart : ruleParts) {
            rulePart = rulePart.trim();
            if (rulePart.isEmpty()) {
                continue;
            }

            try {
                EncryptionRule rule = parseRule(rulePart);
                rules.add(rule);
                log.debug("Parsed encryption rule: {}", rule);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid encryption rule format: {}, skipping", rulePart);
            }
        }

        log.info("Loaded {} PGP encryption rules", rules.size());
    }

    /**
     * Parse a single rule string.
     *
     * @param ruleString Format: EventType:Version:Action
     * @return Parsed rule
     * @throws IllegalArgumentException if format is invalid
     */
    private EncryptionRule parseRule(String ruleString) {
        String[] parts = ruleString.split(FIELD_SEPARATOR);

        if (parts.length != 3) {
            throw new IllegalArgumentException(
                "Expected format EventType:Version:Action, got: " + ruleString);
        }

        String eventType = parts[0].trim();
        String version = parts[1].trim();
        String actionStr = parts[2].trim().toUpperCase();

        if (eventType.isEmpty() || version.isEmpty() || actionStr.isEmpty()) {
            throw new IllegalArgumentException(
                "Rule fields cannot be empty: " + ruleString);
        }

        Action action;
        try {
            action = Action.valueOf(actionStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                "Invalid action '" + actionStr + "', must be ENCRYPT or SKIP");
        }

        return new EncryptionRule(eventType, version, action);
    }

    /**
     * Determine if an event should be encrypted based on matching rules.
     *
     * @param eventType Event type (e.g., "PAYMENT", "REFUND")
     * @param version Event version (e.g., "v1", "2.0")
     * @return Action to take (ENCRYPT or SKIP)
     */
    public Action getAction(String eventType, String version) {
        if (eventType == null || version == null) {
            return defaultAction;
        }

        // Evaluate rules in order, first match wins
        for (EncryptionRule rule : rules) {
            if (rule.matches(eventType, version)) {
                log.debug("Rule matched for {}/{}: {}", eventType, version, rule.action);
                return rule.action;
            }
        }

        log.debug("No rule matched for {}/{}, using default action: {}",
            eventType, version, defaultAction);
        return defaultAction;
    }

    /**
     * Check if an event should be encrypted.
     *
     * @param eventType Event type
     * @param version Event version
     * @return true if should encrypt, false if should skip
     */
    public boolean shouldEncrypt(String eventType, String version) {
        return getAction(eventType, version) == Action.ENCRYPT;
    }

    public int getRuleCount() {
        return rules.size();
    }
}
