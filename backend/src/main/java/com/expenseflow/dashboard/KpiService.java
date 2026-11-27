package com.expenseflow.dashboard;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.api.ApiException;
import com.expenseflow.api.ErrorCodes;
import com.expenseflow.dashboard.KpiDtos.Bucket;
import com.expenseflow.dashboard.KpiDtos.CategorySpend;
import com.expenseflow.dashboard.KpiDtos.DepartmentSpend;
import com.expenseflow.dashboard.KpiDtos.Kpis;
import com.expenseflow.dashboard.KpiDtos.LevelTime;
import com.expenseflow.dashboard.KpiDtos.MonthSpend;
import com.expenseflow.dashboard.KpiDtos.RangeUsed;
import com.expenseflow.dashboard.KpiDtos.RuleCount;
import com.expenseflow.dashboard.KpiDtos.Scope;
import com.expenseflow.dashboard.KpiDtos.SeriesPoint;
import com.expenseflow.dashboard.KpiDtos.Totals;
import com.expenseflow.domain.Role;

/**
 * FR-12 / NFR-DASH / BR-19 KPIs, aggregated in SQL. Scope by role (rbac.md): EMPLOYEE own claims, MANAGER
 * direct reports, FINANCE_AUDITOR / FINANCE_HEAD / SUPER_ADMIN everything.
 * <ul>
 * <li>"Claims in range" = submitted_at in [from, to) (drafts never have submitted_at).</li>
 * <li>spend = sum of those claims' totals excluding AUTO_REJECTED / REJECTED.</li>
 * <li>pendingApprovals = claims in scope waiting at any approval level now (point in time, not range).</li>
 * <li>avgApprovalHours (BR-19) = avg(decided_at - submitted_at) for claims decided by a person in range.</li>
 * </ul>
 * Buckets: range up to 2 days -> HOUR, up to 92 days -> DAY, longer -> WEEK (UTC); empty buckets are filled with 0.
 */
@Service
public class KpiService {

    static final Duration HOURLY_UP_TO = Duration.ofDays(2);
    static final Duration DAILY_UP_TO = Duration.ofDays(92);

    private static final String CLAIMS = " from claims c join users e on e.id = c.employee_id";
    private static final String LINES = " from claim_lines l join claims c on c.id = l.claim_id"
            + " join users e on e.id = c.employee_id";
    /** One row per violation; violations is a JSON array of {code|rule, action, message}. */
    private static final String VIOLATIONS = LINES + " cross join lateral jsonb_array_elements("
            + "case when jsonb_typeof(l.violations) = 'array' then l.violations else '[]'::jsonb end) v(item)";
    private static final String SUBMITTED_IN_RANGE = " and c.submitted_at >= :from and c.submitted_at < :to";
    private static final String COUNTS_AS_SPEND = "c.status not in ('AUTO_REJECTED', 'REJECTED')";
    private static final String PERSON_DECIDED = " and c.status in ('APPROVED', 'REJECTED', 'IN_PAYOUT', 'PAID')"
            + " and coalesce(c.policy_outcome, '') <> 'AUTO_APPROVED'";

    private final NamedParameterJdbcTemplate jdbc;

    public KpiService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static Scope scopeFor(Collection<String> roles) {
        if (roles.contains(Role.FINANCE_AUDITOR) || roles.contains(Role.FINANCE_HEAD)
                || roles.contains(Role.SUPER_ADMIN)) {
            return Scope.ALL;
        }
        return roles.contains(Role.MANAGER) ? Scope.TEAM : Scope.OWN;
    }

    public static Bucket bucketFor(DateRange range) {
        Duration d = Duration.between(range.from(), range.to());
        if (d.compareTo(HOURLY_UP_TO) <= 0) return Bucket.HOUR;
        return d.compareTo(DAILY_UP_TO) <= 0 ? Bucket.DAY : Bucket.WEEK;
    }

    @Transactional(readOnly = true)
    public Kpis kpis(DateRange range, long userId, Collection<String> roles, Long departmentId) {
        Scope scope = scopeFor(roles);
        if (departmentId != null && scope != Scope.ALL) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.FORBIDDEN,
                    "departmentId filter is for finance and admin only", null);
        }
        Bucket bucket = bucketFor(range);
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(range.from()))
                .addValue("to", Timestamp.from(range.to()))
                .addValue("me", userId)
                .addValue("dept", departmentId)
                .addValue("bucket", bucket.name().toLowerCase());
        String where = " where " + switch (scope) {
            case OWN -> "c.employee_id = :me";
            case TEAM -> "e.manager_id = :me";
            case ALL -> "1 = 1";
        } + (departmentId == null ? "" : " and e.department_id = :dept");
        String claimsInRange = CLAIMS + where + SUBMITTED_IN_RANGE;

        Map<String, Object> t = jdbc.queryForMap("select coalesce(sum(c.total_amount) filter (where "
                + COUNTS_AS_SPEND + "), 0) spend, count(*) claims,"
                + " count(*) filter (where c.policy_outcome = 'AUTO_APPROVED') auto" + claimsInRange, p);
        long pending = jdbc.queryForObject("select count(*)" + CLAIMS + where
                + " and c.status in ('PENDING_MANAGER', 'PENDING_AUDITOR', 'PENDING_HEAD')", p, Long.class);
        long violations = jdbc.queryForObject("select count(*)" + VIOLATIONS + where + SUBMITTED_IN_RANGE, p,
                Long.class);
        Double avgHours = jdbc.queryForObject("select avg(extract(epoch from (c.decided_at - c.submitted_at)))"
                + " / 3600.0" + CLAIMS + where + " and c.decided_at >= :from and c.decided_at < :to" + PERSON_DECIDED,
                p, Double.class);
        long claims = ((Number) t.get("claims")).longValue();
        long auto = ((Number) t.get("auto")).longValue();
        Totals totals = new Totals((BigDecimal) t.get("spend"), claims, pending, violations,
                claims == 0 ? 0.0 : round(auto / (double) claims, 1000), avgHours == null ? null : round(avgHours, 10));

        List<CategorySpend> byCategory = jdbc.query("select l.category, sum(l.amount) spend" + LINES + where
                        + SUBMITTED_IN_RANGE + " and " + COUNTS_AS_SPEND + " group by l.category order by spend desc", p,
                (rs, i) -> new CategorySpend(rs.getString(1), rs.getBigDecimal(2)));

        List<DepartmentSpend> byDept = jdbc.query("select coalesce(d.name, 'Unassigned') dept, sum(c.total_amount) spend"
                        + CLAIMS + " left join departments d on d.id = e.department_id" + where + SUBMITTED_IN_RANGE
                        + " and " + COUNTS_AS_SPEND + " group by dept order by spend desc", p,
                (rs, i) -> new DepartmentSpend(rs.getString(1), rs.getBigDecimal(2)));

        List<MonthSpend> byMonth = jdbc.query("select to_char(c.submitted_at at time zone 'UTC', 'YYYY-MM') m,"
                        + " sum(c.total_amount)" + claimsInRange + " and " + COUNTS_AS_SPEND + " group by m order by m", p,
                (rs, i) -> new MonthSpend(rs.getString(1), rs.getBigDecimal(2)));

        Map<Instant, SeriesPoint> raw = new LinkedHashMap<>();
        jdbc.query("select date_trunc(:bucket, c.submitted_at at time zone 'UTC') b,"
                        + " coalesce(sum(c.total_amount) filter (where " + COUNTS_AS_SPEND + "), 0), count(*)"
                        + claimsInRange + " group by b", p,
                rs -> {
                    Instant start = rs.getTimestamp(1).toLocalDateTime().toInstant(ZoneOffset.UTC);
                    raw.put(start, new SeriesPoint(start, rs.getBigDecimal(2), rs.getLong(3)));
                });

        List<RuleCount> byRule = jdbc.query("select coalesce(item->>'code', item->>'rule') code, count(*) n" + VIOLATIONS
                        + where + SUBMITTED_IN_RANGE + " group by 1 order by n desc, 1", p,
                (rs, i) -> new RuleCount(rs.getString(1), rs.getLong(2)));

        List<LevelTime> byLevel = jdbc.query("select s.level, avg(extract(epoch from (s.decided_at - s.created_at)))"
                        + " / 3600.0 from approval_steps s join claims c on c.id = s.claim_id"
                        + " join users e on e.id = c.employee_id" + where
                        + " and s.decision is not null and s.decided_at >= :from and s.decided_at < :to"
                        + " group by s.level order by s.level", p,
                (rs, i) -> new LevelTime(rs.getString(1), round(rs.getDouble(2), 10)));

        return new Kpis(new RangeUsed(range.from(), range.to(), bucket), scope, totals, byCategory, byDept, byMonth,
                fill(range, bucket, raw), byRule, byLevel);
    }

    /** One point per bucket from the bucket containing {@code from} up to (excluding) {@code to}. */
    static List<SeriesPoint> fill(DateRange range, Bucket bucket, Map<Instant, SeriesPoint> raw) {
        List<SeriesPoint> out = new ArrayList<>();
        for (Instant b = truncate(range.from(), bucket); b.isBefore(range.to()); b = next(b, bucket)) {
            out.add(raw.getOrDefault(b, new SeriesPoint(b, BigDecimal.ZERO, 0)));
        }
        return out;
    }

    static Instant truncate(Instant t, Bucket bucket) {
        return switch (bucket) {
            case HOUR -> t.truncatedTo(ChronoUnit.HOURS);
            case DAY -> t.truncatedTo(ChronoUnit.DAYS);
            case WEEK -> {
                Instant day = t.truncatedTo(ChronoUnit.DAYS);
                int dow = day.atZone(ZoneOffset.UTC).getDayOfWeek().getValue(); // Monday = 1, as date_trunc('week')
                yield day.minus(Duration.ofDays(dow - 1L));
            }
        };
    }

    private static Instant next(Instant b, Bucket bucket) {
        return switch (bucket) {
            case HOUR -> b.plus(Duration.ofHours(1));
            case DAY -> b.plus(Duration.ofDays(1));
            case WEEK -> b.plus(Duration.ofDays(7));
        };
    }

    private static double round(double v, int factor) {
        return Math.round(v * factor) / (double) factor;
    }
}
