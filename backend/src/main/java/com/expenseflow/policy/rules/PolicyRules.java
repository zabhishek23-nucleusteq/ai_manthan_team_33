package com.expenseflow.policy.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

import com.expenseflow.domain.ExpenseCategory;
import com.expenseflow.policy.PolicyFormat;
import com.expenseflow.policy.PolicyModel.LineInput;
import com.expenseflow.policy.PolicyModel.PolicyContext;
import com.expenseflow.policy.PolicyModel.RuleConfig;
import com.expenseflow.policy.PolicyModel.Violation;
import com.expenseflow.policy.PolicyRuleEvaluator;

/**
 * The seven rule evaluators (BR-1..BR-8). One small class each, values and action from the rule row (ADR-002).
 * Pure functions: no Spring, no database - cross-claim facts arrive in {@link PolicyContext}.
 */
public final class PolicyRules {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter DOW_DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private PolicyRules() {
    }

    public static List<PolicyRuleEvaluator> all() {
        return List.of(new MealDailyLimitRule(), new HotelNightlyLimitRule(), new CabTripLimitRule(),
                new ReceiptRequiredRule(), new DuplicateLineRule(), new DuplicateReceiptRule(), new WeekendSpendRule());
    }

    private static Violation violation(RuleConfig rule, String message) {
        return new Violation(rule.code(), rule.action(), message);
    }

    /** BR-1: all MEAL lines of the employee on the same date (this claim + other submitted claims) <= limit. */
    public static final class MealDailyLimitRule implements PolicyRuleEvaluator {
        public String code() { return "MEAL_DAILY_LIMIT"; }

        public Optional<Violation> evaluate(LineInput line, List<LineInput> lines, RuleConfig rule, PolicyContext ctx) {
            if (line.category() != ExpenseCategory.MEAL || rule.limitAmount() == null) return Optional.empty();
            BigDecimal day = lines.stream()
                    .filter(l -> l.category() == ExpenseCategory.MEAL && line.expenseDate().equals(l.expenseDate()))
                    .map(LineInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                    .add(ctx.priorMealsByDate().getOrDefault(line.expenseDate(), BigDecimal.ZERO));
            if (day.compareTo(rule.limitAmount()) <= 0) return Optional.empty();
            return Optional.of(violation(rule, "Meals on " + DAY.format(line.expenseDate()) + " total "
                    + PolicyFormat.money(day) + " INR, limit " + PolicyFormat.money(rule.limitAmount()) + " per day"));
        }
    }

    /** BR-2/BR-3: amount / nights <= tier-1 limit (row) or otherCitiesLimit (params) by city. */
    public static final class HotelNightlyLimitRule implements PolicyRuleEvaluator {
        public String code() { return "HOTEL_NIGHTLY_LIMIT"; }

        public Optional<Violation> evaluate(LineInput line, List<LineInput> lines, RuleConfig rule, PolicyContext ctx) {
            if (line.category() != ExpenseCategory.HOTEL || rule.limitAmount() == null) return Optional.empty();
            int nights = line.nights() == null || line.nights() < 1 ? 1 : line.nights();
            BigDecimal perNight = line.amount().divide(BigDecimal.valueOf(nights), 2, RoundingMode.HALF_UP);
            String city = line.city() == null ? "" : line.city().trim();
            boolean tier1 = rule.paramList("tier1Cities").stream().anyMatch(c -> c.equalsIgnoreCase(city));
            BigDecimal limit = tier1 ? rule.limitAmount()
                    : Objects.requireNonNullElse(rule.paramAmount("otherCitiesLimit"), rule.limitAmount());
            if (perNight.compareTo(limit) <= 0) return Optional.empty();
            return Optional.of(violation(rule, "Hotel in " + (city.isEmpty() ? "unknown city" : city) + " "
                    + PolicyFormat.money(perNight) + " INR per night, limit " + PolicyFormat.money(limit)
                    + (tier1 ? " (tier-1 city)" : " (other cities)")));
        }
    }

    /** BR-4: one CAB line = one trip, <= limit. */
    public static final class CabTripLimitRule implements PolicyRuleEvaluator {
        public String code() { return "CAB_TRIP_LIMIT"; }

        public Optional<Violation> evaluate(LineInput line, List<LineInput> lines, RuleConfig rule, PolicyContext ctx) {
            if (line.category() != ExpenseCategory.CAB || rule.limitAmount() == null) return Optional.empty();
            if (line.amount().compareTo(rule.limitAmount()) <= 0) return Optional.empty();
            return Optional.of(violation(rule, "Cab trip " + PolicyFormat.money(line.amount()) + " INR, limit "
                    + PolicyFormat.money(rule.limitAmount()) + " per trip"));
        }
    }

    /** BR-5: receipt mandatory when amount > limit (exactly the limit without a receipt is fine). */
    public static final class ReceiptRequiredRule implements PolicyRuleEvaluator {
        public String code() { return "RECEIPT_REQUIRED"; }

        public Optional<Violation> evaluate(LineInput line, List<LineInput> lines, RuleConfig rule, PolicyContext ctx) {
            if (line.hasReceipt() || rule.limitAmount() == null || line.amount().compareTo(rule.limitAmount()) <= 0) {
                return Optional.empty();
            }
            return Optional.of(violation(rule, "Receipt required above " + PolicyFormat.money(rule.limitAmount())
                    + " INR (line " + line.lineNo() + ": " + PolicyFormat.money(line.amount()) + " INR)"));
        }
    }

    /** BR-6: same vendor (trimmed, case-insensitive) + amount + date as an earlier line - other claims or earlier in this one. */
    public static final class DuplicateLineRule implements PolicyRuleEvaluator {
        public String code() { return "DUPLICATE_LINE"; }

        public Optional<Violation> evaluate(LineInput line, List<LineInput> lines, RuleConfig rule, PolicyContext ctx) {
            String key = PolicyContext.lineKey(line.vendor(), line.amount(), line.expenseDate());
            if (key == null) return Optional.empty();
            boolean earlierInClaim = lines.stream().filter(l -> l.lineNo() < line.lineNo())
                    .anyMatch(l -> key.equals(PolicyContext.lineKey(l.vendor(), l.amount(), l.expenseDate())));
            if (!earlierInClaim && !ctx.priorLineKeys().contains(key)) return Optional.empty();
            return Optional.of(violation(rule, "Duplicate of an earlier line: same vendor, amount and date ("
                    + line.vendor().trim() + ", " + PolicyFormat.money(line.amount()) + " INR, " + DAY.format(line.expenseDate()) + ")"));
        }
    }

    /** BR-7: same receipt file (SHA-256 of the bytes, so a renamed copy matches) as an earlier line. */
    public static final class DuplicateReceiptRule implements PolicyRuleEvaluator {
        public String code() { return "DUPLICATE_RECEIPT"; }

        public Optional<Violation> evaluate(LineInput line, List<LineInput> lines, RuleConfig rule, PolicyContext ctx) {
            if (!line.hasReceipt()) return Optional.empty();
            boolean earlierInClaim = lines.stream().filter(l -> l.lineNo() < line.lineNo())
                    .anyMatch(l -> line.receiptSha256().equals(l.receiptSha256()));
            if (!earlierInClaim && !ctx.priorReceiptHashes().contains(line.receiptSha256())) return Optional.empty();
            return Optional.of(violation(rule, "Receipt on line " + line.lineNo() + " was already used on an earlier line"));
        }
    }

    /** BR-8: Saturday or Sunday spend is flagged for manager attention. */
    public static final class WeekendSpendRule implements PolicyRuleEvaluator {
        public String code() { return "WEEKEND_SPEND"; }

        public Optional<Violation> evaluate(LineInput line, List<LineInput> lines, RuleConfig rule, PolicyContext ctx) {
            DayOfWeek d = line.expenseDate().getDayOfWeek();
            if (d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY) return Optional.empty();
            return Optional.of(violation(rule, "Weekend spend on " + DOW_DAY.format(line.expenseDate()) + " (line " + line.lineNo() + ")"));
        }
    }
}
