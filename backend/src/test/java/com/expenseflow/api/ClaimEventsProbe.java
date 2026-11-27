package com.expenseflow.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.expenseflow.service.ClaimEvents.ClaimRoutedEvent;

/** Test-only: records ClaimRoutedEvent so tests can check what the approval service will receive. */
@Component
public class ClaimEventsProbe {

    public final List<ClaimRoutedEvent> routed = new CopyOnWriteArrayList<>();

    @EventListener
    void on(ClaimRoutedEvent e) {
        routed.add(e);
    }
}
