package com.expenseflow.idempotency;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Table {@code idempotency_keys} (V5). Plain JDBC in auto-commit on purpose: the IN_PROGRESS claim must be visible
 * to a parallel duplicate request before the business transaction even starts, and must survive its rollback.
 */
@Component
class IdempotencyStore {

    enum Status { IN_PROGRESS, COMPLETED }

    record Entry(String requestHash, Status status, Integer responseStatus, String contentType, String body) {
    }

    private final JdbcTemplate jdbc;

    IdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True if this call created the key (first request); false if the key already exists for this user. */
    boolean tryStart(long userId, String key, String requestHash) {
        return jdbc.update("""
                insert into idempotency_keys (user_id, idem_key, request_hash) values (?, ?, ?)
                on conflict (user_id, idem_key) do nothing""", userId, key, requestHash) == 1;
    }

    Optional<Entry> find(long userId, String key) {
        return jdbc.query("""
                select request_hash, status, response_status, response_content_type, response_body
                from idempotency_keys where user_id = ? and idem_key = ?""",
                (rs, n) -> new Entry(rs.getString(1), Status.valueOf(rs.getString(2)),
                        (Integer) rs.getObject(3), rs.getString(4), rs.getString(5)),
                userId, key).stream().findFirst();
    }

    void complete(long userId, String key, int status, String contentType, String body) {
        jdbc.update("""
                update idempotency_keys set status = 'COMPLETED', response_status = ?, response_content_type = ?,
                    response_body = ?, completed_at = now()
                where user_id = ? and idem_key = ? and status = 'IN_PROGRESS'""",
                status, contentType, body, userId, key);
    }

    /** Forget an unfinished key (5xx or crash in the handler) so the client may retry with it. */
    void release(long userId, String key) {
        jdbc.update("delete from idempotency_keys where user_id = ? and idem_key = ? and status = 'IN_PROGRESS'",
                userId, key);
    }
}
