package com.expenseflow.policy;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Test-only listener: records PolicyRuleChangedEvent so tests can assert the before/after audit payload. */
@Component
public class PolicyEventsProbe {

    public final List<PolicyRuleChangedEvent> events = new CopyOnWriteArrayList<>();

    @EventListener
    void on(PolicyRuleChangedEvent e) {
        events.add(e);
    }
}
