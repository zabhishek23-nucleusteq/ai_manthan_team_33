package com.expenseflow.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.ExpenseCategory;
import com.expenseflow.domain.PolicyAction;
import com.expenseflow.domain.PolicyOutcome;
import com.expenseflow.domain.RuleUnit;
import com.expenseflow.policy.PolicyModel.LineInput;
import com.expenseflow.policy.PolicyModel.PolicyContext;
import com.expenseflow.policy.PolicyModel.PolicyResult;
import com.expenseflow.policy.PolicyModel.RuleConfig;
import com.expenseflow.policy.PolicyModel.Violation;

/**
 * BR-1..BR-9 + BR-11/BR-12 boundaries (written before the engine, T-015). Pure unit tests, no Spring.
 * Every limit is tested at the limit (ok) and 0.01 above (violation).
 */
class PolicyEngineTest {

    // 2026-11-18 is a Wednesday, 2026-11-21 a Saturday, 2026-11-22 a Sunday
    static final LocalDate WED = LocalDate.of(2026, 11, 18);
    static final LocalDate SAT = LocalDate.of(2026, 11, 21);
    static final LocalDate SUN = LocalDate.of(2026, 11, 22);

    private final PolicyEngine engine = PolicyEngine.withDefaultRules();

    /** Same values as V4__policy_rules.sql. */
    static Map<String, RuleConfig> seededRules() {
        Map<String, RuleConfig> m = new LinkedHashMap<>();
        m.put("MEAL_DAILY_LIMIT", new RuleConfig("MEAL_DAILY_LIMIT", bd("1500"), RuleUnit.PER_DAY, null, PolicyAction.ROUTE, true));
        m.put("HOTEL_NIGHTLY_LIMIT", new RuleConfig("HOTEL_NIGHTLY_LIMIT", bd("6000"), RuleUnit.PER_NIGHT,
                Map.of("otherCitiesLimit", 4000, "tier1Cities", List.of("Mumbai", "Delhi", "New Delhi", "Bengaluru", "Bangalore", "Chennai", "Kolkata", "Hyderabad", "Pune", "Ahmedabad")),
                PolicyAction.ROUTE, true));
        m.put("CAB_TRIP_LIMIT", new RuleConfig("CAB_TRIP_LIMIT", bd("2000"), RuleUnit.PER_TRIP, null, PolicyAction.ROUTE, true));
        m.put("RECEIPT_REQUIRED", new RuleConfig("RECEIPT_REQUIRED", bd("500"), RuleUnit.PER_LINE, null, PolicyAction.REJECT, true));
        m.put("DUPLICATE_LINE", new RuleConfig("DUPLICATE_LINE", null, RuleUnit.PER_LINE, null, PolicyAction.REJECT, true));
        m.put("DUPLICATE_RECEIPT", new RuleConfig("DUPLICATE_RECEIPT", null, RuleUnit.PER_LINE, null, PolicyAction.REJECT, true));
        m.put("WEEKEND_SPEND", new RuleConfig("WEEKEND_SPEND", null, RuleUnit.PER_LINE, null, PolicyAction.ROUTE, true));
        m.put("AUTO_APPROVE_MAX", new RuleConfig("AUTO_APPROVE_MAX", bd("5000"), RuleUnit.PER_CLAIM, null, null, true));
        m.put("CHAIN_AUDITOR_ABOVE", new RuleConfig("CHAIN_AUDITOR_ABOVE", bd("25000"), RuleUnit.PER_CLAIM, null, null, true));
        m.put("CHAIN_HEAD_ABOVE", new RuleConfig("CHAIN_HEAD_ABOVE", bd("100000"), RuleUnit.PER_CLAIM, null, null, true));
        m.put("SAMPLE_AUDIT_RATE", new RuleConfig("SAMPLE_AUDIT_RATE", bd("0.10"), null, null, null, true));
        return m;
    }

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private int n = 0;

    private LineInput line(ExpenseCategory cat, LocalDate date, String amount, String vendor, String city, Integer nights, String sha) {
        n++;
        return new LineInput(n, (long) n, cat, date, bd(amount), vendor, city, nights, sha);
    }

    /** Line with a receipt (so RECEIPT_REQUIRED never interferes) on a weekday. */
    private LineInput ok(ExpenseCategory cat, String amount) {
        return line(cat, WED, amount, "Vendor " + (n + 1), null, cat == ExpenseCategory.HOTEL ? 1 : null, "sha-" + (n + 1));
    }

    private PolicyResult run(LineInput... lines) {
        return engine.evaluate(List.of(lines), seededRules(), PolicyContext.empty());
    }

    private static List<String> codes(PolicyResult r) {
        return r.lineResults().stream().flatMap(l -> l.violations().stream()).map(Violation::code).distinct().toList();
    }

    @Nested
    class Br1MealDailyLimit {
        @Test
        void br1_exactly1500_ok() {
            assertThat(codes(run(ok(ExpenseCategory.MEAL, "1500.00")))).doesNotContain("MEAL_DAILY_LIMIT");
        }

        @Test
        void br1_1500_01_violation() {
            PolicyResult r = run(ok(ExpenseCategory.MEAL, "1500.01"));
            assertThat(codes(r)).containsExactly("MEAL_DAILY_LIMIT");
            assertThat(r.outcome()).isEqualTo(PolicyOutcome.ROUTED);
        }

        @Test
        void br1_twoMealsSameDayInOneClaim_summed() {
            PolicyResult r = run(ok(ExpenseCategory.MEAL, "800.00"), ok(ExpenseCategory.MEAL, "700.01"));
            assertThat(r.lineResults()).allSatisfy(l -> assertThat(l.violations()).extracting(Violation::code).containsExactly("MEAL_DAILY_LIMIT"));
            assertThat(r.reasons()).anySatisfy(s -> assertThat(s).contains("1,500.01").contains("1,500"));
        }

        @Test
        void br1_mealSplitAcrossClaims_violation() {
            PolicyContext ctx = new PolicyContext(Map.of(WED, bd("1000.00")), Set.of(), Set.of());
            PolicyResult r = engine.evaluate(List.of(ok(ExpenseCategory.MEAL, "500.01")), seededRules(), ctx);
            assertThat(codes(r)).containsExactly("MEAL_DAILY_LIMIT");
        }

        @Test
        void br1_mealSplitAcrossClaims_exactlyAtLimit_ok() {
            PolicyContext ctx = new PolicyContext(Map.of(WED, bd("1000.00")), Set.of(), Set.of());
            assertThat(codes(engine.evaluate(List.of(ok(ExpenseCategory.MEAL, "500.00")), seededRules(), ctx))).isEmpty();
        }

        @Test
        void br1_differentDays_notSummed() {
            LineInput a = ok(ExpenseCategory.MEAL, "1000.00");
            LineInput b = line(ExpenseCategory.MEAL, WED.minusDays(1), "1000.00", "Cafe B", null, null, "sha-b");
            assertThat(codes(run(a, b))).isEmpty();
        }
    }

    @Nested
    class Br2Br3HotelNightly {
        @Test
        void br2_tier1_exactly6000PerNight_ok() {
            assertThat(codes(run(line(ExpenseCategory.HOTEL, WED, "6000.00", "Taj", "Mumbai", 1, "h1")))).isEmpty();
        }

        @Test
        void br2_tier1_6000_01_violation() {
            assertThat(codes(run(line(ExpenseCategory.HOTEL, WED, "6000.01", "Taj", "mumbai ", 1, "h1")))).containsExactly("HOTEL_NIGHTLY_LIMIT");
        }

        @Test
        void br2_perNight_twoNights12000_ok() {
            assertThat(codes(run(line(ExpenseCategory.HOTEL, WED, "12000.00", "Taj", "Bengaluru", 2, "h1")))).isEmpty();
        }

        @Test
        void br2_perNight_twoNights12000_02_violation() {
            assertThat(codes(run(line(ExpenseCategory.HOTEL, WED, "12000.02", "Taj", "Bengaluru", 2, "h1")))).containsExactly("HOTEL_NIGHTLY_LIMIT");
        }

        @Test
        void br3_otherCity_exactly4000_ok() {
            assertThat(codes(run(line(ExpenseCategory.HOTEL, WED, "4000.00", "Lemon Tree", "Indore", 1, "h1")))).isEmpty();
        }

        @Test
        void br3_otherCity_4000_01_violation() {
            PolicyResult r = run(line(ExpenseCategory.HOTEL, WED, "4000.01", "Lemon Tree", "Indore", 1, "h1"));
            assertThat(codes(r)).containsExactly("HOTEL_NIGHTLY_LIMIT");
            assertThat(r.reasons()).anySatisfy(s -> assertThat(s).contains("Indore").contains("4,000"));
        }
    }

    @Nested
    class Br4CabTrip {
        @Test
        void br4_exactly2000_ok() {
            assertThat(codes(run(ok(ExpenseCategory.CAB, "2000.00")))).isEmpty();
        }

        @Test
        void br4_2000_01_violation() {
            assertThat(codes(run(ok(ExpenseCategory.CAB, "2000.01")))).containsExactly("CAB_TRIP_LIMIT");
        }
    }

    @Nested
    class Br5ReceiptRequired {
        @Test
        void br5_exactly500_withoutReceipt_ok() {
            assertThat(codes(run(line(ExpenseCategory.OTHER, WED, "500.00", "Stationery", null, null, null)))).isEmpty();
        }

        @Test
        void br5_500_01_withoutReceipt_rejected() {
            PolicyResult r = run(line(ExpenseCategory.OTHER, WED, "500.01", "Stationery", null, null, null));
            assertThat(codes(r)).containsExactly("RECEIPT_REQUIRED");
            assertThat(r.outcome()).isEqualTo(PolicyOutcome.AUTO_REJECTED);
        }

        @Test
        void br5_500_01_withReceipt_ok() {
            assertThat(codes(run(line(ExpenseCategory.OTHER, WED, "500.01", "Stationery", null, null, "r1")))).isEmpty();
        }
    }

    @Nested
    class Br6DuplicateLine {
        @Test
        void br6_sameVendorAmountDateInEarlierClaim_vendorCaseInsensitive_rejected() {
            PolicyContext ctx = new PolicyContext(Map.of(), Set.of(PolicyContext.lineKey("Uber", bd("450"), WED)), Set.of());
            LineInput l = line(ExpenseCategory.CAB, WED, "450.00", "  UBER ", null, null, null);
            PolicyResult r = engine.evaluate(List.of(l), seededRules(), ctx);
            assertThat(codes(r)).containsExactly("DUPLICATE_LINE");
            assertThat(r.outcome()).isEqualTo(PolicyOutcome.AUTO_REJECTED);
        }

        @Test
        void br6_duplicateWithinSameClaim_onlyLaterLineFlagged() {
            LineInput first = line(ExpenseCategory.CAB, WED, "450.00", "Uber", null, null, null);
            LineInput second = line(ExpenseCategory.CAB, WED, "450.00", "uber", null, null, null);
            PolicyResult r = run(first, second);
            assertThat(r.lineResults().get(0).violations()).isEmpty();
            assertThat(r.lineResults().get(1).violations()).extracting(Violation::code).containsExactly("DUPLICATE_LINE");
        }

        @Test
        void br6_differentAmount_notDuplicate() {
            PolicyContext ctx = new PolicyContext(Map.of(), Set.of(PolicyContext.lineKey("Uber", bd("450"), WED)), Set.of());
            assertThat(codes(engine.evaluate(List.of(line(ExpenseCategory.CAB, WED, "450.01", "Uber", null, null, null)), seededRules(), ctx))).isEmpty();
        }
    }

    @Nested
    class Br7DuplicateReceipt {
        @Test
        void br7_sameBytesDifferentFileName_rejected() {
            // the hash is of the bytes, so a renamed copy of the same file has the same SHA-256
            PolicyContext ctx = new PolicyContext(Map.of(), Set.of(), Set.of("abc123"));
            PolicyResult r = engine.evaluate(List.of(line(ExpenseCategory.MEAL, WED, "900.00", "Cafe", null, null, "abc123")), seededRules(), ctx);
            assertThat(codes(r)).containsExactly("DUPLICATE_RECEIPT");
            assertThat(r.outcome()).isEqualTo(PolicyOutcome.AUTO_REJECTED);
        }

        @Test
        void br7_sameReceiptTwiceInOneClaim_laterLineFlagged() {
            PolicyResult r = run(line(ExpenseCategory.MEAL, WED, "600.00", "Cafe A", null, null, "same"),
                    line(ExpenseCategory.CAB, WED, "300.00", "Ola", null, null, "same"));
            assertThat(r.lineResults().get(1).violations()).extracting(Violation::code).containsExactly("DUPLICATE_RECEIPT");
        }
    }

    @Nested
    class Br8Weekend {
        @Test
        void br8_saturdayAndSunday_routed() {
            PolicyResult r = run(line(ExpenseCategory.MEAL, SAT, "300.00", "Cafe", null, null, null),
                    line(ExpenseCategory.MEAL, SUN, "300.00", "Cafe 2", null, null, null));
            assertThat(r.lineResults()).allSatisfy(l -> assertThat(l.violations()).extracting(Violation::code).containsExactly("WEEKEND_SPEND"));
            assertThat(r.outcome()).isEqualTo(PolicyOutcome.ROUTED);
        }

        @Test
        void br8_weekday_notFlagged() {
            assertThat(codes(run(line(ExpenseCategory.MEAL, WED, "300.00", "Cafe", null, null, null)))).isEmpty();
        }
    }

    @Nested
    class Br9OutcomeAndChain {
        @Test
        void outcome_rejectWins() {
            PolicyResult r = run(ok(ExpenseCategory.CAB, "2500.00"),                                  // ROUTE
                    line(ExpenseCategory.OTHER, WED, "800.00", "Shop", null, null, null));         // REJECT
            assertThat(r.outcome()).isEqualTo(PolicyOutcome.AUTO_REJECTED);
            assertThat(codes(r)).containsExactlyInAnyOrder("CAB_TRIP_LIMIT", "RECEIPT_REQUIRED");
            assertThat(r.requiredLevels()).isEmpty();
            assertThat(r.reasons()).hasSizeGreaterThanOrEqualTo(2);
        }

        @Test
        void clean_exactly5000_autoApproved() {
            PolicyResult r = run(ok(ExpenseCategory.TRAVEL, "5000.00"));
            assertThat(r.outcome()).isEqualTo(PolicyOutcome.AUTO_APPROVED);
            assertThat(r.requiredLevels()).isEmpty();
        }

        @Test
        void clean_5000_01_routedToManagerOnly() {
            PolicyResult r = run(ok(ExpenseCategory.TRAVEL, "5000.01"));
            assertThat(r.outcome()).isEqualTo(PolicyOutcome.ROUTED);
            assertThat(r.requiredLevels()).containsExactly(ApprovalLevel.MANAGER);
            assertThat(r.reasons()).anySatisfy(s -> assertThat(s).contains("auto-approve"));
        }

        @Test
        void br11_exactly25000_managerOnly_and_25000_01_addsAuditor() {
            assertThat(run(ok(ExpenseCategory.TRAVEL, "25000.00")).requiredLevels()).containsExactly(ApprovalLevel.MANAGER);
            assertThat(run(ok(ExpenseCategory.TRAVEL, "25000.01")).requiredLevels()).containsExactly(ApprovalLevel.MANAGER, ApprovalLevel.AUDITOR);
        }

        @Test
        void br12_exactly100000_stopsAtAuditor_and_100000_01_addsHead() {
            assertThat(run(ok(ExpenseCategory.TRAVEL, "100000.00")).requiredLevels()).containsExactly(ApprovalLevel.MANAGER, ApprovalLevel.AUDITOR);
            assertThat(run(ok(ExpenseCategory.TRAVEL, "100000.01")).requiredLevels())
                    .containsExactly(ApprovalLevel.MANAGER, ApprovalLevel.AUDITOR, ApprovalLevel.HEAD);
        }

        @Test
        void totalIsSumOfLines() {
            assertThat(run(ok(ExpenseCategory.TRAVEL, "100.10"), ok(ExpenseCategory.OTHER, "200.20")).total()).isEqualByComparingTo("300.30");
        }
    }

    @Nested
    class Configuration {
        @Test
        void disabledRule_isSkipped() {
            Map<String, RuleConfig> rules = new HashMap<>(seededRules());
            RuleConfig cab = rules.get("CAB_TRIP_LIMIT");
            rules.put("CAB_TRIP_LIMIT", new RuleConfig(cab.code(), cab.limitAmount(), cab.unit(), cab.params(), cab.action(), false));
            assertThat(codes(engine.evaluate(List.of(ok(ExpenseCategory.CAB, "9999.00")), rules, PolicyContext.empty())))
                    .doesNotContain("CAB_TRIP_LIMIT");
        }

        @Test
        void actionComesFromConfig_routeRuleSwitchedToReject() {
            Map<String, RuleConfig> rules = new HashMap<>(seededRules());
            RuleConfig cab = rules.get("CAB_TRIP_LIMIT");
            rules.put("CAB_TRIP_LIMIT", new RuleConfig(cab.code(), cab.limitAmount(), cab.unit(), cab.params(), PolicyAction.REJECT, true));
            assertThat(engine.evaluate(List.of(ok(ExpenseCategory.CAB, "2000.01")), rules, PolicyContext.empty()).outcome())
                    .isEqualTo(PolicyOutcome.AUTO_REJECTED);
        }

        @Test
        void ruleChange_appliesToNextSubmitOnly() {
            Map<String, RuleConfig> before = seededRules();
            PolicyResult first = engine.evaluate(List.of(ok(ExpenseCategory.CAB, "1800.00")), before, PolicyContext.empty());

            Map<String, RuleConfig> after = new HashMap<>(before);
            after.put("CAB_TRIP_LIMIT", new RuleConfig("CAB_TRIP_LIMIT", bd("1500"), RuleUnit.PER_TRIP, null, PolicyAction.ROUTE, true));
            PolicyResult second = engine.evaluate(List.of(ok(ExpenseCategory.CAB, "1800.00")), after, PolicyContext.empty());

            assertThat(codes(first)).isEmpty();
            assertThat(codes(second)).containsExactly("CAB_TRIP_LIMIT");
            // the first result keeps the rule values it was decided with (snapshot), unaffected by the change
            assertThat(first.ruleSnapshot().get("CAB_TRIP_LIMIT").limitAmount()).isEqualByComparingTo("2000");
            assertThat(second.ruleSnapshot().get("CAB_TRIP_LIMIT").limitAmount()).isEqualByComparingTo("1500");
        }

        @Test
        void allSevenRulesHaveAnEvaluator() {
            assertThat(PolicyEngine.withDefaultRules().ruleCodes()).containsExactlyInAnyOrder(
                    "MEAL_DAILY_LIMIT", "HOTEL_NIGHTLY_LIMIT", "CAB_TRIP_LIMIT", "RECEIPT_REQUIRED",
                    "DUPLICATE_LINE", "DUPLICATE_RECEIPT", "WEEKEND_SPEND");
        }

        @Test
        void lineResultsKeepLineOrderAndIds() {
            List<LineInput> lines = new ArrayList<>(List.of(ok(ExpenseCategory.MEAL, "100"), ok(ExpenseCategory.CAB, "100")));
            PolicyResult r = engine.evaluate(lines, seededRules(), PolicyContext.empty());
            assertThat(r.lineResults()).extracting(l -> l.lineNo()).containsExactly(1L, 2L);
            assertThat(r.lineResults()).extracting(l -> l.lineId()).containsExactly(1L, 2L);
        }
    }
}
