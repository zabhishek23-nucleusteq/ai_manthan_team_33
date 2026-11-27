package com.expenseflow.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * NFR-OBS gauges per approval level (MANAGER / AUDITOR / HEAD):
 * {@code expenseflow_approval_queue_size{level}} and {@code expenseflow_oldest_pending_approval_seconds{level}}
 * (alert: oldest pending > 48 h). One grouped query, cached for {@link #TTL} so scrapes stay cheap.
 */
@Component
public class ApprovalQueueMetrics {

    static final Duration TTL = Duration.ofSeconds(10);
    static final List<String> LEVELS = List.of("MANAGER", "AUDITOR", "HEAD");
    private static final Logger log = LoggerFactory.getLogger(ApprovalQueueMetrics.class);

    record LevelStats(long size, double oldestSeconds) {
    }

    private final JdbcTemplate jdbc;
    private final Clock clock = Clock.systemUTC();
    private volatile Map<String, LevelStats> snapshot = Map.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    public ApprovalQueueMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String level : LEVELS) {
            Gauge.builder("expenseflow.approval.queue.size", this, m -> m.stats(level).size())
                    .description("Approval steps waiting for a decision").tag("level", level).register(registry);
            Gauge.builder("expenseflow.oldest.pending.approval.seconds", this, m -> m.stats(level).oldestSeconds())
                    .description("Age of the oldest undecided approval step").tag("level", level).register(registry);
        }
    }

    LevelStats stats(String level) {
        refreshIfStale();
        return snapshot.getOrDefault(level, new LevelStats(0, 0));
    }

    /** Forces the next read to query the database (tests). */
    void invalidate() {
        loadedAt = Instant.EPOCH;
    }

    private synchronized void refreshIfStale() {
        Instant now = clock.instant();
        if (Duration.between(loadedAt, now).compareTo(TTL) < 0) {
            return;
        }
        try {
            Map<String, LevelStats> fresh = new HashMap<>();
            jdbc.query("select level, count(*), coalesce(extract(epoch from (now() - min(created_at))), 0)"
                    + " from approval_steps where decision is null group by level",
                    rs -> {
                        fresh.put(rs.getString(1), new LevelStats(rs.getLong(2), rs.getDouble(3)));
                    });
            snapshot = fresh;
        } catch (RuntimeException e) {
            log.warn("Approval queue metrics not refreshed: {}", e.getMessage()); // keep last values
        }
        loadedAt = now;
    }
}
