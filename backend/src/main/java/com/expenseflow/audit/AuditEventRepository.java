package com.expenseflow.audit;

import java.util.List;

import org.springframework.data.repository.Repository;

/**
 * Insert and read only: extends the bare {@link Repository} so there is no delete or saveAll-with-update
 * method to call by mistake (failure-modes "Delete audit evidence").
 */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

    AuditEvent save(AuditEvent event);

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByAtAscIdAsc(String entityType, String entityId);

    long count();
}
