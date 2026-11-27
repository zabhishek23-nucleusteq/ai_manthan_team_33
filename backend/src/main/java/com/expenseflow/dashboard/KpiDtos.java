package com.expenseflow.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Response shape of GET /api/dashboard/kpis (contracts/openapi.yaml: Kpis). */
public final class KpiDtos {

    private KpiDtos() {
    }

    public enum Bucket { HOUR, DAY, WEEK }

    public enum Scope { OWN, TEAM, ALL }

    public record RangeUsed(Instant from, Instant to, Bucket bucket) {
    }

    public record Totals(BigDecimal spend, long claims, long pendingApprovals, long violations,
            double autoApprovedRate, Double avgApprovalHours) {
    }

    public record CategorySpend(String category, BigDecimal spend) {
    }

    public record DepartmentSpend(String department, BigDecimal spend) {
    }

    public record MonthSpend(String month, BigDecimal spend) {
    }

    public record SeriesPoint(Instant start, BigDecimal spend, long claims) {
    }

    public record RuleCount(String code, long count) {
    }

    public record LevelTime(String level, double avgHours) {
    }

    public record Kpis(RangeUsed range, Scope scope, Totals totals, List<CategorySpend> spendByCategory,
            List<DepartmentSpend> spendByDepartment, List<MonthSpend> spendByMonth, List<SeriesPoint> series,
            List<RuleCount> violationsByRule, List<LevelTime> approvalTimeByLevel) {
    }
}
