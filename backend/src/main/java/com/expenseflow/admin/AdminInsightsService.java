package com.expenseflow.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import com.expenseflow.admin.AdminDtos.AuditEventView;
import com.expenseflow.admin.AdminDtos.Health;
import com.expenseflow.admin.AdminDtos.Overview;
import com.expenseflow.admin.AdminDtos.Page;
import com.expenseflow.dashboard.KpiService;
import com.expenseflow.admin.AdminDtos.ServiceStatus;
import com.expenseflow.audit.AuditEvent;
import com.expenseflow.dashboard.DateRange;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.User;
import com.expenseflow.observability.HttpErrorRateTracker;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.AuthDtos.UserRef;

import tools.jackson.databind.json.JsonMapper;

/** Read side of the super admin console: audit search, overview, health (FR-15). */
@Service
public class AdminInsightsService {

    static final long DEGRADED_MS = 1000;

    private final EntityManager em;
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final HttpErrorRateTracker errorRate;
    private final KpiService kpis;
    private final RestClient mockBank;
    private final Clock clock = Clock.systemUTC();

    public AdminInsightsService(EntityManager em, UserRepository users, JdbcTemplate jdbc, JsonMapper json,
            HttpErrorRateTracker errorRate, KpiService kpis, @Value("${MOCK_BANK_URL:http://localhost:8081}") String mockBankUrl) {
        this.em = em;
        this.users = users;
        this.jdbc = jdbc;
        this.json = json;
        this.errorRate = errorRate;
        this.kpis = kpis;
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout(Duration.ofSeconds(1));
        rf.setReadTimeout(Duration.ofSeconds(2));
        this.mockBank = RestClient.builder().baseUrl(mockBankUrl).requestFactory(rf).build();
    }

    // ---- audit ----

    @Transactional(readOnly = true)
    public Page<AuditEventView> audit(DateRange range, Long actorId, String action, String entityType, int page,
            int size) {
        StringBuilder where = new StringBuilder(" where e.at >= :from and e.at < :to");
        Map<String, Object> params = new HashMap<>(Map.of("from", range.from(), "to", range.to()));
        if (actorId != null) {
            where.append(" and e.actorId = :actor");
            params.put("actor", actorId);
        }
        if (action != null && !action.isBlank()) {
            where.append(" and e.action = :action");
            params.put("action", action.trim().toUpperCase());
        }
        if (entityType != null && !entityType.isBlank()) {
            where.append(" and e.entityType = :entityType");
            params.put("entityType", entityType.trim().toUpperCase());
        }
        TypedQuery<AuditEvent> items = em.createQuery(
                "select e from AuditEvent e" + where + " order by e.at desc, e.id desc", AuditEvent.class);
        TypedQuery<Long> count = em.createQuery("select count(e) from AuditEvent e" + where, Long.class);
        params.forEach((k, v) -> {
            items.setParameter(k, v);
            count.setParameter(k, v);
        });
        List<AuditEvent> events = items.setFirstResult(page * size).setMaxResults(size).getResultList();

        List<Long> ids = new ArrayList<>();
        events.forEach(e -> {
            if (e.getActorId() != null) ids.add(e.getActorId());
            if (e.getOnBehalfOfId() != null) ids.add(e.getOnBehalfOfId());
        });
        Map<Long, UserRef> refs = users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> new UserRef(u.getId(), u.getName(), u.getEmail())));
        List<AuditEventView> views = events.stream().map(e -> new AuditEventView(e.getId(), e.getAt(),
                ref(refs, e.getActorId()), ref(refs, e.getOnBehalfOfId()), e.getAction(), e.getEntityType(),
                e.getEntityId(), parse(e.getBefore()), parse(e.getAfter()))).toList();
        return new Page<>(views, page, size, count.getSingleResult());
    }

    private static UserRef ref(Map<Long, UserRef> refs, Long id) {
        return id == null ? null : refs.getOrDefault(id, new UserRef(id, null, null));
    }

    private Object parse(String raw) {
        return raw == null ? null : json.readTree(raw);
    }

    // ---- overview ----

    @Transactional(readOnly = true)
    public Overview overview(DateRange range, long adminId, java.util.Collection<String> roles) {
        Map<String, Long> byRole = new LinkedHashMap<>();
        for (String code : List.of(Role.EMPLOYEE, Role.MANAGER, Role.FINANCE_AUDITOR, Role.FINANCE_HEAD,
                Role.SUPER_ADMIN)) {
            byRole.put(code, 0L);
        }
        em.createQuery("select r.code, count(u) from User u join u.roles r group by r.code", Object[].class)
                .getResultList().forEach(row -> byRole.put((String) row[0], (Long) row[1]));
        long active = em.createQuery("select count(u) from User u where u.enabled = true", Long.class)
                .getSingleResult();
        long newUsers = em.createQuery("select count(u) from User u where u.createdAt >= :from and u.createdAt < :to",
                Long.class).setParameter("from", range.from()).setParameter("to", range.to()).getSingleResult();
        return new Overview(kpis.kpis(range, adminId, roles, null), byRole, active, users.count(), newUsers);
    }

    // ---- health ----

    @Transactional(readOnly = true)
    public Health health() {
        List<ServiceStatus> services = List.of(
                new ServiceStatus("backend", "UP", 0),
                probe("database", () -> jdbc.queryForObject("select 1", Integer.class) == 1),
                probe("mock-bank", () -> {
                    String body = mockBank.get().uri("/actuator/health").retrieve()
                            .body(String.class);
                    return body != null && body.contains("\"UP\"");
                }));
        Instant oldest = em.createQuery(
                "select min(s.createdAt) from ApprovalStep s where s.decision is null", Instant.class)
                .getSingleResult();
        Double hours = oldest == null ? null
                : Math.round(Duration.between(oldest, Instant.now(clock)).toMinutes() / 6.0) / 10.0;
        return new Health(services, errorRate.errorRate5m(), hours);
    }

    private static ServiceStatus probe(String name, BooleanSupplier check) {
        long start = System.nanoTime();
        boolean up;
        try {
            up = check.getAsBoolean();
        } catch (RuntimeException e) {
            up = false;
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        String status = !up ? "DOWN" : ms > DEGRADED_MS ? "DEGRADED" : "UP";
        return new ServiceStatus(name, status, ms);
    }
}
