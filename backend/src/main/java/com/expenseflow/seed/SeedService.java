package com.expenseflow.seed;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.ClaimStatus;
import com.expenseflow.domain.ExpenseCategory;
import com.expenseflow.domain.PolicyOutcome;
import com.expenseflow.domain.SampleAudit;
import com.expenseflow.policy.PolicyEngine;
import com.expenseflow.policy.PolicyModel.LineInput;
import com.expenseflow.policy.PolicyModel.LineResult;
import com.expenseflow.policy.PolicyModel.PolicyContext;
import com.expenseflow.policy.PolicyModel.PolicyResult;
import com.expenseflow.policy.PolicyModel.RuleConfig;
import com.expenseflow.policy.PolicyService;

import net.datafaker.Faker;
import tools.jackson.databind.json.JsonMapper;

/**
 * Synthetic seed data (T-013, requirements.md section 6, team-standards section 2). Deterministic for a fixed
 * seed except that dates are relative to "now", so the dashboard's 1D / 7D / 1M filters always show data.
 * Every submitted claim goes through the real PolicyEngine, so outcome, reasons, line violations, status and
 * approval steps are consistent with the rules. Idempotent: does nothing if departments already exist.
 */
@Service
public class SeedService {

    private static final Logger log = LoggerFactory.getLogger(SeedService.class);

    public static final int USERS = 150;
    public static final int CLAIMS = 3000;
    public static final String DEMO_PASSWORD = "Demo@123";
    static final String[][] DEPARTMENTS = {
            {"ENG", "Engineering"}, {"SAL", "Sales"}, {"MKT", "Marketing"}, {"FIN", "Finance"},
            {"HR", "HR"}, {"OPS", "Operations"}, {"SUP", "Support"}, {"LEG", "Legal"}};
    static final Set<ClaimStatus> NOT_COUNTED = Set.of(ClaimStatus.DRAFT, ClaimStatus.REJECTED, ClaimStatus.AUTO_REJECTED);

    private static final Map<ExpenseCategory, String[]> VENDORS = Map.of(
            ExpenseCategory.MEAL, new String[] {"Swiggy", "Zomato", "Cafe Coffee Day", "Haldiram's", "Barbeque Nation", "Starbucks", "Chai Point", "Saravana Bhavan"},
            ExpenseCategory.CAB, new String[] {"Uber", "Ola", "Rapido", "Meru", "BluSmart"},
            ExpenseCategory.HOTEL, new String[] {"Taj", "ITC Hotels", "Lemon Tree", "Ginger", "Treebo", "Marriott", "Hyatt", "Radisson"},
            ExpenseCategory.TRAVEL, new String[] {"IndiGo", "Air India", "Vistara", "IRCTC", "Akasa Air"},
            ExpenseCategory.OTHER, new String[] {"Amazon", "Croma", "Reliance Digital", "Staples", "Flipkart"});
    private static final String[] TIER1 = {"Mumbai", "Delhi", "Bengaluru", "Chennai", "Kolkata", "Hyderabad", "Pune", "Ahmedabad"};
    private static final String[] OTHER_CITIES = {"Indore", "Jaipur", "Lucknow", "Nagpur", "Kochi", "Coimbatore", "Bhopal", "Raipur", "Chandigarh", "Surat"};

    /** Target state mix (requirements.md section 6). */
    static final Map<ClaimStatus, Double> MIX = new LinkedHashMap<>();
    static {
        MIX.put(ClaimStatus.PAID, 0.40);
        MIX.put(ClaimStatus.APPROVED, 0.12);
        MIX.put(ClaimStatus.IN_PAYOUT, 0.03);
        MIX.put(ClaimStatus.PENDING_MANAGER, 0.10);
        MIX.put(ClaimStatus.PENDING_AUDITOR, 0.05);
        MIX.put(ClaimStatus.PENDING_HEAD, 0.02);
        MIX.put(ClaimStatus.CLARIFICATION_REQUESTED, 0.03);
        MIX.put(ClaimStatus.REJECTED, 0.05);
        MIX.put(ClaimStatus.AUTO_REJECTED, 0.08);
        MIX.put(ClaimStatus.DRAFT, 0.12);
    }

    public record SeedStats(boolean seeded, int users, int claims, long millis) {
    }

    private volatile SeedStats lastSeed;

    /** Stats of the run that actually seeded (null if this instance never seeded). */
    public SeedStats lastSeed() {
        return lastSeed;
    }

    // ------------------------------------------------------------------ in-memory model
    static final class U {
        long id; String code; String name; String email; String dept; Long managerId; List<String> roles = new ArrayList<>();
        String bank; String ifsc; Instant createdAt;
    }

    static final class R {
        long id; long ownerId; String fileName; byte[] content; String sha;
    }

    static final class L {
        long id; int no; ExpenseCategory cat; LocalDate date; BigDecimal amount; String vendor; String city; Integer nights;
        R receipt; String violations;
    }

    static final class S {
        long id; int round = 1; ApprovalLevel level; long assigneeId; Long actedById; String decision; String comment;
        Instant createdAt; Instant decidedAt;
    }

    static final class C {
        long id; String code; U owner; String title; ClaimStatus status; List<L> lines = new ArrayList<>();
        PolicyOutcome outcome; String policyJson; SampleAudit sample = SampleAudit.NONE;
        Instant submittedAt; Instant decidedAt; Instant paidAt; Instant createdAt; List<S> steps = new ArrayList<>();
        List<String[]> comments = new ArrayList<>(); // [authorId, body]
        BigDecimal total() { return lines.stream().map(l -> l.amount).reduce(BigDecimal.ZERO, BigDecimal::add); }
    }

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final PolicyService policyService;
    private final JsonMapper json;
    private final PolicyEngine engine = PolicyEngine.withDefaultRules();

    // generation state
    private Random rnd;
    private Faker faker;
    private Instant now;
    private Map<String, RuleConfig> rules;
    private final List<U> users = new ArrayList<>();
    private final List<R> receipts = new ArrayList<>();
    private final List<C> claims = new ArrayList<>();
    private final Map<Long, Map<LocalDate, BigDecimal>> mealsByEmp = new HashMap<>();
    private final Set<String> lineKeys = new HashSet<>();
    private final List<L> countedLines = new ArrayList<>();
    private final List<R> countedReceipts = new ArrayList<>();
    private U head, admin, priya, rahul, anita, meera;
    private final List<U> auditors = new ArrayList<>();
    private int receiptSeq = 0;

    public SeedService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, PolicyService policyService, JsonMapper json) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.policyService = policyService;
        this.json = json;
    }

    @Transactional
    public synchronized SeedStats seedIfEmpty() {
        Integer depts = jdbc.queryForObject("select count(*) from departments", Integer.class);
        if (depts != null && depts > 0) {
            log.info("Seed skipped: data already present ({} departments)", depts);
            return new SeedStats(false, 0, 0, 0);
        }
        long t0 = System.currentTimeMillis();
        reset();
        rules = policyService.currentRules();
        buildUsers();
        buildClaims();
        write();
        long ms = System.currentTimeMillis() - t0;
        log.info("Seed done: {} users, {} claims, {} receipts in {} ms", users.size(), claims.size(), receipts.size(), ms);
        lastSeed = new SeedStats(true, users.size(), claims.size(), ms);
        return lastSeed;
    }

    private void reset() {
        rnd = new Random(42);
        faker = new Faker(Locale.forLanguageTag("en-IND"), new Random(42));
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        users.clear(); receipts.clear(); claims.clear(); mealsByEmp.clear(); lineKeys.clear();
        countedLines.clear(); countedReceipts.clear(); auditors.clear(); receiptSeq = 0;
    }

    // ------------------------------------------------------------------ users
    private U user(String name, String email, String dept, U manager, String... roles) {
        U u = new U();
        u.code = String.format("EMP%04d", users.size() + 1);
        u.name = name;
        u.email = email;
        u.dept = dept;
        u.managerId = manager == null ? null : manager.id;
        u.roles.add("EMPLOYEE");
        for (String r : roles) if (!u.roles.contains(r)) u.roles.add(r);
        u.bank = "50100" + String.format("%07d", rnd.nextInt(10_000_000));
        u.ifsc = new String[] {"HDFC0", "ICIC0", "SBIN0", "UTIB0", "KKBK0"}[rnd.nextInt(5)] + String.format("%06d", rnd.nextInt(1_000_000));
        u.createdAt = now.minus(Duration.ofDays(90 + rnd.nextInt(600)));
        users.add(u);
        return u;
    }

    private String uniqueEmail(String name) {
        String base = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", ".").replaceAll("^\\.|\\.$", "");
        String email = base + "@expenseflow.test";
        int i = 2;
        while (true) {
            final String e = email;
            if (users.stream().noneMatch(u -> u.email.equals(e))) return email;
            email = base + i++ + "@expenseflow.test";
        }
    }

    private String fakeName() {
        return faker.name().firstName() + " " + faker.name().lastName();
    }

    private void buildUsers() {
        List<Long> ids = jdbc.queryForList("select nextval('users_id_seq') from generate_series(1, ?)", Long.class, USERS);
        int[] idx = {0};
        // demo logins first (rbac.md section 4); ids assigned in creation order
        head = assign(user("Farhan Head", "head@expenseflow.test", "FIN", null, "FINANCE_HEAD", "SUPER_ADMIN"), ids, idx);
        admin = assign(user("Sam Admin", "admin@expenseflow.test", "OPS", head), ids, idx);
        admin.roles.clear();
        admin.roles.add("SUPER_ADMIN");
        anita = assign(user("Anita Auditor", "auditor@expenseflow.test", "FIN", head, "FINANCE_AUDITOR"), ids, idx);
        auditors.add(anita);
        rahul = assign(user("Rahul Manager", "manager@expenseflow.test", "ENG", head, "MANAGER"), ids, idx);
        meera = assign(user("Meera Backup", "manager2@expenseflow.test", "ENG", rahul, "MANAGER"), ids, idx);
        priya = assign(user("Priya Employee", "employee@expenseflow.test", "ENG", rahul), ids, idx);

        for (int i = 0; i < 2; i++) {
            String n = fakeName();
            auditors.add(assign(user(n, uniqueEmail(n), "FIN", head, "FINANCE_AUDITOR"), ids, idx));
        }
        Map<String, U> deptManager = new LinkedHashMap<>();
        Map<String, List<U>> leads = new LinkedHashMap<>();
        for (String[] d : DEPARTMENTS) {
            U mgr;
            if (d[0].equals("ENG")) {
                mgr = rahul;
            } else {
                String n = fakeName();
                mgr = assign(user(n, uniqueEmail(n), d[0], head, "MANAGER"), ids, idx);
            }
            deptManager.put(d[0], mgr);
            List<U> l = new ArrayList<>();
            if (d[0].equals("ENG")) l.add(meera);
            int nLeads = d[0].equals("ENG") ? 1 : (rnd.nextBoolean() ? 1 : 2);
            for (int i = 0; i < nLeads && users.size() < 30; i++) {
                String n = fakeName();
                l.add(assign(user(n, uniqueEmail(n), d[0], mgr, "MANAGER"), ids, idx));
            }
            leads.put(d[0], l);
        }
        // everyone else: employees spread over departments, reporting to a team lead or the department manager
        int d = 0;
        while (users.size() < USERS) {
            String dept = DEPARTMENTS[d++ % DEPARTMENTS.length][0];
            List<U> l = leads.get(dept);
            U mgr = !l.isEmpty() && rnd.nextInt(4) != 0 ? l.get(rnd.nextInt(l.size())) : deptManager.get(dept);
            String n = fakeName();
            assign(user(n, uniqueEmail(n), dept, mgr), ids, idx);
        }
    }

    private static U assign(U u, List<Long> ids, int[] idx) {
        u.id = ids.get(idx[0]++);
        return u;
    }

    private U managerOf(U u) {
        if (u.managerId == null) return null;
        return users.stream().filter(x -> x.id == u.managerId).findFirst().orElse(null);
    }

    // ------------------------------------------------------------------ helpers
    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal between(double lo, double hi) {
        double v = lo + rnd.nextDouble() * (hi - lo);
        return rnd.nextInt(3) == 0 ? money(Math.round(v)) : money(v);
    }

    private String vendor(ExpenseCategory c) {
        String[] v = VENDORS.get(c);
        return v[rnd.nextInt(v.length)];
    }

    private Instant daysAgo(double lo, double hi) {
        Instant t;
        int guard = 0;
        do {
            double days = lo + rnd.nextDouble() * (hi - lo);
            t = now.minusSeconds((long) (days * 86_400));
            DayOfWeek dow = t.atZone(ZoneOffset.UTC).getDayOfWeek();
            boolean weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
            if (!weekend || rnd.nextInt(10) < 4 || ++guard > 20) break;   // fewer claims on weekends
        } while (true);
        return t;
    }

    /** Older claims are sparser: density grows toward now (upward trend). */
    private Instant trendDaysAgo(double lo, double hi) {
        double u = rnd.nextDouble();
        double x = Math.sqrt(u);                       // more mass near 1
        double days = hi - x * (hi - lo);
        return daysAgo(days, days);
    }

    private LocalDate weekdayBefore(Instant submitted, int maxDaysBack) {
        LocalDate d = submitted.atZone(ZoneOffset.UTC).toLocalDate().minusDays(rnd.nextInt(maxDaysBack + 1));
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.minusDays(1);
        return d;
    }

    private LocalDate weekendBefore(Instant submitted) {
        LocalDate d = submitted.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        while (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) d = d.minusDays(1);
        return d;
    }

    private R newReceipt(long ownerId, byte[] content, String name) {
        R r = new R();
        r.ownerId = ownerId;
        r.content = content;
        r.fileName = name;
        r.sha = sha256(content);
        receipts.add(r);
        return r;
    }

    private R freshReceipt(long ownerId) {
        int n = ++receiptSeq;
        byte[] content = ("%PDF-1.4\n% ExpenseFlow synthetic receipt #" + n + " " + rnd.nextLong() + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII);
        return newReceipt(ownerId, content, "receipt-" + n + ".pdf");
    }

    private static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private L line(ExpenseCategory cat, LocalDate date, BigDecimal amount, String vendor, long ownerId, boolean withReceipt) {
        L l = new L();
        l.cat = cat;
        l.date = date;
        l.amount = amount;
        l.vendor = vendor;
        if (cat == ExpenseCategory.HOTEL) {
            l.nights = 1;
            l.city = rnd.nextBoolean() ? TIER1[rnd.nextInt(TIER1.length)] : OTHER_CITIES[rnd.nextInt(OTHER_CITIES.length)];
        }
        if (withReceipt) l.receipt = freshReceipt(ownerId);
        return l;
    }

    private L cleanLine(ExpenseCategory cat, LocalDate date, long ownerId, double maxAmount) {
        BigDecimal amount = switch (cat) {
            case MEAL -> between(120, Math.min(450, maxAmount));
            case CAB -> between(90, Math.min(1500, maxAmount));
            case HOTEL -> null;
            case TRAVEL -> between(400, Math.min(4500, maxAmount));
            case OTHER -> between(80, Math.min(1800, maxAmount));
        };
        if (cat == ExpenseCategory.HOTEL) {
            L l = line(cat, date, BigDecimal.ONE, vendor(cat), ownerId, true);
            boolean tier1 = List.of(TIER1).contains(l.city);
            l.nights = 1 + rnd.nextInt(2);
            double perNight = tier1 ? 2500 + rnd.nextDouble() * 3300 : 1500 + rnd.nextDouble() * 2300;
            l.amount = money(perNight * l.nights);
            return l;
        }
        return line(cat, date, amount, vendor(cat), ownerId, amount.compareTo(new BigDecimal("500")) > 0 || rnd.nextInt(3) == 0);
    }

    // ------------------------------------------------------------------ claims
    private void buildClaims() {
        List<U> claimants = users.stream().filter(u -> u != head && u != admin && u != priya).toList();
        List<ClaimStatus> targets = new ArrayList<>();
        int bulk = CLAIMS - 10 - BOUNDARY_COUNT;
        for (Map.Entry<ClaimStatus, Double> e : MIX.entrySet()) {
            int n = (int) Math.round(e.getValue() * bulk);
            for (int i = 0; i < n; i++) targets.add(e.getKey());
        }
        while (targets.size() < bulk) targets.add(ClaimStatus.PAID);
        while (targets.size() > bulk) targets.remove(targets.size() - 1);
        Collections.shuffle(targets, rnd);

        // timestamps by status; about 4% of all claims in the last 24 h (some in the last 2 h)
        int recentBudget = (int) Math.round(CLAIMS * 0.04);
        int pendingOld = 0;
        List<C> bulkClaims = new ArrayList<>();
        for (ClaimStatus target : targets) {
            C c = new C();
            c.owner = claimants.get(rnd.nextInt(claimants.size()));
            c.status = target;
            boolean recentEligible = target == ClaimStatus.PENDING_MANAGER || target == ClaimStatus.AUTO_REJECTED || target == ClaimStatus.DRAFT;
            Instant t;
            if (recentEligible && recentBudget > 0 && rnd.nextInt(3) == 0) {
                t = recentBudget <= 25 ? daysAgo(0.01, 0.08) : daysAgo(0.05, 0.95);      // last 2 h / last 24 h
                recentBudget--;
            } else {
                t = switch (target) {
                    case PAID -> trendDaysAgo(8, 60);
                    case IN_PAYOUT -> daysAgo(2, 7);
                    case APPROVED -> daysAgo(1.2, 12);
                    case PENDING_MANAGER, PENDING_AUDITOR, PENDING_HEAD -> {
                        if (pendingOld < 18 && rnd.nextInt(4) == 0) { pendingOld++; yield daysAgo(2.2, 6); }   // > 48 h (alert demo)
                        yield daysAgo(1.05, 2.0);
                    }
                    case CLARIFICATION_REQUESTED -> daysAgo(1.2, 6);
                    case REJECTED, AUTO_REJECTED -> trendDaysAgo(1.05, 60);
                    case DRAFT -> daysAgo(1.05, 20);
                };
            }
            if (target == ClaimStatus.DRAFT) c.createdAt = t;
            else c.submittedAt = t;
            bulkClaims.add(c);
        }
        // chronological order so "earlier" duplicates and meal sums make sense
        bulkClaims.sort((a, b) -> time(a).compareTo(time(b)));
        for (C c : bulkClaims) {
            if (c.status == ClaimStatus.DRAFT) buildDraft(c);
            else buildSubmitted(c, c.status);
            claims.add(c);
        }
        buildBoundaryClaims(claimants);
        buildDemoClaims();
        // codes in submit order
        claims.sort((a, b) -> time(a).compareTo(time(b)));
        int n = 0;
        for (C c : claims) {
            if (c.code == null) c.code = String.format("EXP-%d-%05d", time(c).atZone(ZoneOffset.UTC).getYear(), ++n);
        }
        buildPayouts();
    }

    private static Instant time(C c) {
        return c.submittedAt != null ? c.submittedAt : c.createdAt;
    }

    private void buildDraft(C c) {
        c.title = title();
        int n = 1 + rnd.nextInt(4);
        for (int i = 0; i < n; i++) {
            ExpenseCategory cat = ExpenseCategory.values()[rnd.nextInt(5)];
            c.lines.add(cleanLine(cat, weekdayBefore(c.createdAt, 3), c.owner.id, 3000));
        }
        number(c);
    }

    private String title() {
        String[] t = {"Client visit", "Team offsite", "Customer workshop", "Conference travel", "Site inspection", "Vendor meeting",
                "Sales trip", "Training program", "Quarterly review", "Product demo", "Recruitment drive", "Audit visit"};
        return t[rnd.nextInt(t.length)] + " - " + faker.address().city();
    }

    private static void number(C c) {
        for (int i = 0; i < c.lines.size(); i++) c.lines.get(i).no = i + 1;
    }

    /** Builds lines for the target and runs the engine; retries until the outcome fits the target. */
    private void buildSubmitted(C c, ClaimStatus target) {
        c.title = title();
        for (int attempt = 0; attempt < 8; attempt++) {
            c.lines = linesFor(c, target);
            number(c);
            PolicyResult r = evaluate(c);
            if (fits(target, r)) {
                finish(c, target, r);
                return;
            }
        }
        // could not hit the target (e.g. meal sums of earlier claims): keep the last lines, derive a consistent status
        PolicyResult r = evaluate(c);
        ClaimStatus fallback = switch (r.outcome()) {
            case AUTO_REJECTED -> ClaimStatus.AUTO_REJECTED;
            case AUTO_APPROVED -> ClaimStatus.APPROVED;
            case ROUTED -> ClaimStatus.PENDING_MANAGER;
        };
        finish(c, fallback, r);
    }

    private enum Path { AUTO, ROUTED_ANY, ROUTED_AUDITOR, ROUTED_HEAD, REJECT }

    private Path pathFor(ClaimStatus target) {
        return switch (target) {
            case AUTO_REJECTED -> Path.REJECT;
            case PENDING_AUDITOR -> Path.ROUTED_AUDITOR;
            case PENDING_HEAD -> Path.ROUTED_HEAD;
            case PENDING_MANAGER, CLARIFICATION_REQUESTED, REJECTED -> Path.ROUTED_ANY;
            case APPROVED, IN_PAYOUT, PAID -> {
                int x = rnd.nextInt(100);
                yield x < 55 ? Path.AUTO : x < 88 ? Path.ROUTED_ANY : x < 97 ? Path.ROUTED_AUDITOR : Path.ROUTED_HEAD;
            }
            default -> Path.AUTO;
        };
    }

    private final Map<C, Path> paths = new HashMap<>();

    private List<L> linesFor(C c, ClaimStatus target) {
        Path p = paths.computeIfAbsent(c, k -> pathFor(target));
        long o = c.owner.id;
        Instant s = c.submittedAt;
        List<L> out = new ArrayList<>();
        switch (p) {
            case AUTO -> {
                int n = 1 + rnd.nextInt(3);
                double budget = 4800;
                for (int i = 0; i < n; i++) {
                    ExpenseCategory cat = new ExpenseCategory[] {ExpenseCategory.MEAL, ExpenseCategory.CAB, ExpenseCategory.OTHER, ExpenseCategory.TRAVEL}[rnd.nextInt(4)];
                    L l = cleanLine(cat, weekdayBefore(s, 4), o, Math.max(150, budget / (n - i)));
                    budget -= l.amount.doubleValue();
                    out.add(l);
                }
            }
            case ROUTED_ANY -> {
                if (rnd.nextInt(100) < 45) {
                    out.add(breachLine(s, o));
                    if (rnd.nextBoolean()) out.add(cleanLine(ExpenseCategory.CAB, weekdayBefore(s, 3), o, 1500));
                } else {
                    out.add(line(ExpenseCategory.TRAVEL, weekdayBefore(s, 5), between(5200, 18000), vendor(ExpenseCategory.TRAVEL), o, true));
                    out.add(cleanLine(ExpenseCategory.HOTEL, weekdayBefore(s, 5), o, 0));
                    if (rnd.nextBoolean()) out.add(cleanLine(ExpenseCategory.MEAL, weekdayBefore(s, 5), o, 450));
                }
            }
            case ROUTED_AUDITOR -> {
                out.add(line(ExpenseCategory.TRAVEL, weekdayBefore(s, 6), between(18000, 60000), vendor(ExpenseCategory.TRAVEL), o, true));
                out.add(line(ExpenseCategory.TRAVEL, weekdayBefore(s, 6), between(9000, 30000), vendor(ExpenseCategory.TRAVEL), o, true));
                out.add(cleanLine(ExpenseCategory.HOTEL, weekdayBefore(s, 6), o, 0));
            }
            case ROUTED_HEAD -> {
                out.add(line(ExpenseCategory.TRAVEL, weekdayBefore(s, 8), between(60000, 140000), vendor(ExpenseCategory.TRAVEL), o, true));
                out.add(line(ExpenseCategory.TRAVEL, weekdayBefore(s, 8), between(45000, 90000), vendor(ExpenseCategory.TRAVEL), o, true));
            }
            case REJECT -> {
                int kind = rnd.nextInt(100);
                if (kind < 45 || countedLines.isEmpty()) {
                    // BR-5: missing receipt above 500
                    out.add(line(ExpenseCategory.OTHER, weekdayBefore(s, 4), between(500.01, 3200), vendor(ExpenseCategory.OTHER), o, false));
                } else if (kind < 75 || countedReceipts.isEmpty()) {
                    // BR-6: same vendor + amount + date as an earlier line (any employee)
                    L src = countedLines.get(rnd.nextInt(countedLines.size()));
                    L dup = line(src.cat, src.date, src.amount, rnd.nextBoolean() ? src.vendor.toUpperCase(Locale.ROOT) : " " + src.vendor + " ", o, true);
                    if (src.cat == ExpenseCategory.HOTEL) { dup.city = src.city; dup.nights = src.nights; }
                    out.add(dup);
                } else {
                    // BR-7: same receipt bytes under a different file name
                    R src = countedReceipts.get(rnd.nextInt(countedReceipts.size()));
                    L dup = line(ExpenseCategory.MEAL, weekdayBefore(s, 4), between(200, 450), vendor(ExpenseCategory.MEAL), o, false);
                    dup.receipt = newReceipt(o, src.content.clone(), "scan-" + (++receiptSeq) + ".pdf");
                    out.add(dup);
                }
                if (rnd.nextBoolean()) out.add(cleanLine(ExpenseCategory.CAB, weekdayBefore(s, 3), o, 900));
            }
        }
        return out;
    }

    /** A line that violates one ROUTE rule (limit breach or weekend). */
    private L breachLine(Instant s, long o) {
        return switch (rnd.nextInt(4)) {
            case 0 -> line(ExpenseCategory.MEAL, weekdayBefore(s, 4), between(1520, 3200), vendor(ExpenseCategory.MEAL), o, true);
            case 1 -> {
                L l = line(ExpenseCategory.HOTEL, weekdayBefore(s, 4), BigDecimal.ONE, vendor(ExpenseCategory.HOTEL), o, true);
                boolean tier1 = List.of(TIER1).contains(l.city);
                l.amount = tier1 ? between(6050, 9500) : between(4050, 6500);
                yield l;
            }
            case 2 -> line(ExpenseCategory.CAB, weekdayBefore(s, 4), between(2050, 4200), vendor(ExpenseCategory.CAB), o, true);
            default -> line(ExpenseCategory.MEAL, weekendBefore(s), between(250, 900), vendor(ExpenseCategory.MEAL), o, true);
        };
    }

    private boolean fits(ClaimStatus target, PolicyResult r) {
        Path p = paths.get(lastClaim);
        return switch (target) {
            case AUTO_REJECTED -> r.outcome() == PolicyOutcome.AUTO_REJECTED;
            case PENDING_AUDITOR -> r.outcome() == PolicyOutcome.ROUTED && r.requiredLevels().contains(ApprovalLevel.AUDITOR)
                    && !r.requiredLevels().contains(ApprovalLevel.HEAD);
            case PENDING_HEAD -> r.outcome() == PolicyOutcome.ROUTED && r.requiredLevels().contains(ApprovalLevel.HEAD);
            case PENDING_MANAGER, CLARIFICATION_REQUESTED, REJECTED -> r.outcome() == PolicyOutcome.ROUTED;
            case APPROVED, IN_PAYOUT, PAID -> p == Path.AUTO ? r.outcome() == PolicyOutcome.AUTO_APPROVED : r.outcome() == PolicyOutcome.ROUTED;
            default -> true;
        };
    }

    private C lastClaim;

    private PolicyResult evaluate(C c) {
        lastClaim = c;
        List<LineInput> in = new ArrayList<>();
        for (L l : c.lines) {
            in.add(new LineInput(l.no, null, l.cat, l.date, l.amount, l.vendor, l.city, l.nights, l.receipt == null ? null : l.receipt.sha));
        }
        Map<LocalDate, BigDecimal> meals = mealsByEmp.getOrDefault(c.owner.id, Map.of());
        Set<String> hashes = new HashSet<>();
        for (R r : countedReceipts) hashes.add(r.sha);
        return engine.evaluate(in, rules, new PolicyContext(meals, lineKeys, hashes));
    }

    private void finish(C c, ClaimStatus status, PolicyResult r) {
        c.status = status;
        c.outcome = r.outcome();
        c.policyJson = json.writeValueAsString(r);
        Map<Long, LineResult> byNo = new HashMap<>();
        for (LineResult lr : r.lineResults()) byNo.put(lr.lineNo(), lr);
        for (L l : c.lines) {
            LineResult lr = byNo.get((long) l.no);
            l.violations = lr == null || lr.violations().isEmpty() ? null : json.writeValueAsString(lr.violations());
        }
        approvalSteps(c, r);
        if (r.outcome() == PolicyOutcome.AUTO_APPROVED && rnd.nextInt(10) == 0) {
            c.sample = c.status == ClaimStatus.APPROVED ? SampleAudit.PENDING : SampleAudit.OK;   // BR-13, BR-18
        }
        if (c.status == ClaimStatus.AUTO_REJECTED || c.status == ClaimStatus.APPROVED && c.outcome == PolicyOutcome.AUTO_APPROVED
                || c.status == ClaimStatus.IN_PAYOUT || c.status == ClaimStatus.PAID) {
            if (c.decidedAt == null) c.decidedAt = c.submittedAt.plusSeconds(1);
        }
        c.createdAt = c.submittedAt.minus(Duration.ofMinutes(10 + rnd.nextInt(600)));
        if (!NOT_COUNTED.contains(c.status)) {
            for (L l : c.lines) {
                if (l.cat == ExpenseCategory.MEAL) {
                    mealsByEmp.computeIfAbsent(c.owner.id, k -> new HashMap<>()).merge(l.date, l.amount, BigDecimal::add);
                }
                String key = PolicyContext.lineKey(l.vendor, l.amount, l.date);
                if (key != null) lineKeys.add(key);
                countedLines.add(l);
                if (l.receipt != null) countedReceipts.add(l.receipt);
            }
        }
    }

    private U assigneeFor(ApprovalLevel level, U claimant) {
        return switch (level) {
            case MANAGER -> {
                U m = managerOf(claimant);
                yield m == null || m == claimant ? auditorNot(claimant) : m;       // BR-15 escalation
            }
            case AUDITOR -> auditorNot(claimant);
            case HEAD -> head;
        };
    }

    private U auditorNot(U claimant) {
        if (claimant == priya) return anita;              // demo: Priya's claims land in Anita's queue
        List<U> a = auditors.stream().filter(x -> x != claimant).toList();
        return a.get(rnd.nextInt(a.size()));
    }

    /** Approval steps consistent with the final status (ADR-001). */
    private void approvalSteps(C c, PolicyResult r) {
        if (r.outcome() != PolicyOutcome.ROUTED) return;
        List<ApprovalLevel> levels = r.requiredLevels();
        int decidedCount = switch (c.status) {
            case PENDING_MANAGER -> 0;
            case PENDING_AUDITOR -> 1;
            case PENDING_HEAD -> 2;
            case CLARIFICATION_REQUESTED, REJECTED -> rnd.nextInt(Math.max(1, levels.size()));
            default -> levels.size();       // APPROVED / IN_PAYOUT / PAID: all approved
        };
        Instant t = c.submittedAt;
        Instant latest = now.minus(Duration.ofMinutes(5));
        for (int i = 0; i < levels.size() && i <= decidedCount; i++) {
            S s = new S();
            s.level = levels.get(i);
            s.assigneeId = assigneeFor(s.level, c.owner).id;
            s.createdAt = t;
            boolean last = i == decidedCount;
            String decision = null;
            if (i < decidedCount) decision = "APPROVED";
            else if (last && c.status == ClaimStatus.CLARIFICATION_REQUESTED) decision = "SENT_BACK";
            else if (last && c.status == ClaimStatus.REJECTED) decision = "REJECTED";
            if (decision != null) {
                Instant d = t.plus(Duration.ofMinutes(60 + rnd.nextInt(30 * 60)));
                if (d.isAfter(latest)) d = latest;
                if (d.isBefore(t)) d = t;
                s.decision = decision;
                s.actedById = s.assigneeId;
                s.decidedAt = d;
                t = d;
                if (decision.equals("SENT_BACK")) {
                    s.comment = new String[] {"Please attach the hotel invoice with GST number.", "Which client was this for? Add the project code.",
                            "Split the meal bill per day, please."}[rnd.nextInt(3)];
                    c.comments.add(new String[] {Long.toString(s.assigneeId), s.comment});
                } else if (decision.equals("REJECTED")) {
                    s.comment = new String[] {"Not a business expense.", "Personal travel - not reimbursable.", "Duplicate of an earlier claim."}[rnd.nextInt(3)];
                    c.comments.add(new String[] {Long.toString(s.assigneeId), s.comment});
                }
                c.decidedAt = d;
            }
            c.steps.add(s);
            if (decision == null || !decision.equals("APPROVED")) break;
        }
        if (c.status == ClaimStatus.CLARIFICATION_REQUESTED || c.status == ClaimStatus.REJECTED) {
            if (c.steps.stream().noneMatch(s -> s.decision != null && !s.decision.equals("APPROVED"))) {
                // ensure the last created step carries the send-back / rejection
                S s = c.steps.get(c.steps.size() - 1);
                s.decision = c.status == ClaimStatus.REJECTED ? "REJECTED" : "SENT_BACK";
                s.actedById = s.assigneeId;
                s.decidedAt = s.createdAt.plus(Duration.ofHours(2)).isAfter(now) ? now.minusSeconds(60) : s.createdAt.plus(Duration.ofHours(2));
                s.comment = c.status == ClaimStatus.REJECTED ? "Not a business expense." : "Please add the missing details.";
                c.comments.add(new String[] {Long.toString(s.assigneeId), s.comment});
                c.decidedAt = s.decidedAt;
            }
        }
        if (c.status == ClaimStatus.PENDING_MANAGER || c.status == ClaimStatus.PENDING_AUDITOR || c.status == ClaimStatus.PENDING_HEAD
                || c.status == ClaimStatus.CLARIFICATION_REQUESTED) {
            if (c.status != ClaimStatus.CLARIFICATION_REQUESTED) c.decidedAt = null;
        }
    }

    // ------------------------------------------------------------------ boundary records (requirements section 6)
    static final int BOUNDARY_COUNT = 14;

    private void buildBoundaryClaims(List<U> claimants) {
        List<U> pool = claimants.stream().filter(u -> u.roles.size() == 1).toList();
        Object[][] specs = {
                {ExpenseCategory.MEAL, "1500.00", null}, {ExpenseCategory.MEAL, "1500.01", null},
                {ExpenseCategory.HOTEL, "6000.00", "Mumbai"}, {ExpenseCategory.HOTEL, "6000.01", "Mumbai"},
                {ExpenseCategory.HOTEL, "4000.00", "Indore"}, {ExpenseCategory.HOTEL, "4000.01", "Indore"},
                {ExpenseCategory.CAB, "2000.00", null}, {ExpenseCategory.CAB, "2000.01", null},
                {ExpenseCategory.OTHER, "500.00", "NO_RECEIPT"}, {ExpenseCategory.OTHER, "500.01", "NO_RECEIPT"},
                {ExpenseCategory.TRAVEL, "25000.00", null}, {ExpenseCategory.TRAVEL, "25000.01", null},
                {ExpenseCategory.TRAVEL, "100000.00", null}, {ExpenseCategory.TRAVEL, "100000.01", null}};
        for (int i = 0; i < specs.length; i++) {
            C c = new C();
            c.owner = pool.get((i * 7 + 3) % pool.size());
            c.submittedAt = daysAgo(1.1 + i * 0.3, 1.1 + i * 0.3);
            ExpenseCategory cat = (ExpenseCategory) specs[i][0];
            BigDecimal amount = new BigDecimal((String) specs[i][1]);
            boolean noReceipt = "NO_RECEIPT".equals(specs[i][2]);
            LocalDate date = freeMealDate(c.owner, c.submittedAt);
            L l = line(cat, date, amount, "Boundary " + vendor(cat), c.owner.id, !noReceipt);
            if (cat == ExpenseCategory.HOTEL) { l.city = (String) specs[i][2]; l.nights = 1; }
            c.lines.add(l);
            c.title = "Boundary check: " + cat.name().toLowerCase(Locale.ROOT) + " " + specs[i][1];
            number(c);
            PolicyResult r = evaluate(c);
            ClaimStatus status = switch (r.outcome()) {
                case AUTO_APPROVED -> ClaimStatus.APPROVED;
                case AUTO_REJECTED -> ClaimStatus.AUTO_REJECTED;
                case ROUTED -> ClaimStatus.PENDING_MANAGER;
            };
            finish(c, status, r);
            claims.add(c);
        }
    }

    /** A weekday with no meals yet for this employee, so the meal boundary records test exactly one rule. */
    private LocalDate freeMealDate(U u, Instant s) {
        Map<LocalDate, BigDecimal> m = mealsByEmp.getOrDefault(u.id, Map.of());
        LocalDate d = weekdayBefore(s, 0);
        while (m.containsKey(d) || d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.minusDays(1);
        return d;
    }

    // ------------------------------------------------------------------ demo claims DEMO-1001..1010 (Priya)
    private void buildDemoClaims() {
        Instant base = now.minus(Duration.ofHours(30));
        LocalDate wd = weekdayBefore(base, 1);

        C d1 = demo(1001, "Team lunch (draft)", null);
        d1.createdAt = now.minus(Duration.ofHours(3));
        d1.lines.add(line(ExpenseCategory.MEAL, wd, new BigDecimal("640.00"), "Barbeque Nation HSR Layout", priya.id, true));
        d1.lines.add(line(ExpenseCategory.CAB, wd, new BigDecimal("280.00"), "Uber", priya.id, false));
        number(d1);

        C d2 = demo(1002, "Client lunch and cab", base.minus(Duration.ofDays(4)));
        R reused = freshReceipt(priya.id);
        L d2a = line(ExpenseCategory.MEAL, weekdayBefore(d2.submittedAt, 1), new BigDecimal("980.00"), "Saravana Bhavan Koramangala", priya.id, false);
        d2a.receipt = reused;
        d2.lines.add(d2a);
        d2.lines.add(line(ExpenseCategory.CAB, weekdayBefore(d2.submittedAt, 1), new BigDecimal("420.00"), "Ola Airport Drop", priya.id, false));
        submitDemo(d2, ClaimStatus.APPROVED, PolicyOutcome.AUTO_APPROVED);

        C d3 = demo(1003, "Office supplies (missing receipt)", base.minus(Duration.ofDays(3)));
        d3.lines.add(line(ExpenseCategory.OTHER, weekdayBefore(d3.submittedAt, 1), new BigDecimal("1250.00"), "Croma Indiranagar", priya.id, false));
        submitDemo(d3, ClaimStatus.AUTO_REJECTED, PolicyOutcome.AUTO_REJECTED);

        C d4 = demo(1004, "Weekend customer support visit", base);
        d4.lines.add(line(ExpenseCategory.MEAL, weekendBefore(d4.submittedAt), new BigDecimal("720.00"), "Chai Point Whitefield", priya.id, true));
        d4.lines.add(line(ExpenseCategory.CAB, weekendBefore(d4.submittedAt), new BigDecimal("560.00"), "Rapido Bike Taxi", priya.id, true));
        submitDemo(d4, ClaimStatus.PENDING_MANAGER, PolicyOutcome.ROUTED);

        C d5 = demo(1005, "Bengaluru partner summit", base.minus(Duration.ofDays(2)));
        d5.lines.add(line(ExpenseCategory.TRAVEL, weekdayBefore(d5.submittedAt, 3), new BigDecimal("24500.00"), "IndiGo 6E-512", priya.id, true));
        L h5 = line(ExpenseCategory.HOTEL, weekdayBefore(d5.submittedAt, 3), new BigDecimal("5500.00"), "ITC Gardenia", priya.id, true);
        h5.city = "Bengaluru"; h5.nights = 1;
        d5.lines.add(h5);
        submitDemo(d5, ClaimStatus.PENDING_AUDITOR, PolicyOutcome.ROUTED);     // total 30,000

        C d6 = demo(1006, "Singapore conference (international)", base.minus(Duration.ofDays(5)));
        d6.lines.add(line(ExpenseCategory.TRAVEL, weekdayBefore(d6.submittedAt, 4), new BigDecimal("86000.00"), "Air India AI-380", priya.id, true));
        d6.lines.add(line(ExpenseCategory.TRAVEL, weekdayBefore(d6.submittedAt, 4), new BigDecimal("34000.00"), "Marina Bay Hotel Deposit", priya.id, true));
        submitDemo(d6, ClaimStatus.PENDING_HEAD, PolicyOutcome.ROUTED);        // total 1,20,000

        C d7 = demo(1007, "Pune workshop hotel", base.minus(Duration.ofDays(3)));
        L h7 = line(ExpenseCategory.HOTEL, weekdayBefore(d7.submittedAt, 2), new BigDecimal("13000.00"), "Marriott Hinjewadi", priya.id, true);
        h7.city = "Pune"; h7.nights = 2;
        d7.lines.add(h7);
        submitDemo(d7, ClaimStatus.CLARIFICATION_REQUESTED, PolicyOutcome.ROUTED);

        C d8 = demo(1008, "Hyderabad training travel", base.minus(Duration.ofDays(6)));
        d8.lines.add(line(ExpenseCategory.TRAVEL, weekdayBefore(d8.submittedAt, 2), new BigDecimal("8400.00"), "Akasa Air QP-1120", priya.id, true));
        submitDemo(d8, ClaimStatus.APPROVED, PolicyOutcome.ROUTED);

        C d9 = demo(1009, "Mumbai client meeting", base.minus(Duration.ofDays(14)));
        d9.lines.add(line(ExpenseCategory.TRAVEL, weekdayBefore(d9.submittedAt, 2), new BigDecimal("3200.00"), "IRCTC Rajdhani", priya.id, true));
        d9.lines.add(line(ExpenseCategory.CAB, weekdayBefore(d9.submittedAt, 2), new BigDecimal("650.00"), "Meru Andheri", priya.id, true));
        submitDemo(d9, ClaimStatus.PAID, PolicyOutcome.AUTO_APPROVED);

        C d10 = demo(1010, "Dinner (receipt reused)", base.minus(Duration.ofDays(1)));
        L d10a = line(ExpenseCategory.MEAL, weekdayBefore(d10.submittedAt, 1), new BigDecimal("880.00"), "Zomato Gold Dinner", priya.id, false);
        d10a.receipt = newReceipt(priya.id, reused.content.clone(), "dinner-scan.pdf");   // same bytes as DEMO-1002's receipt
        d10.lines.add(d10a);
        submitDemo(d10, ClaimStatus.AUTO_REJECTED, PolicyOutcome.AUTO_REJECTED);

        claims.add(d1);
        number(d1);
    }

    private C demo(int n, String title, Instant submitted) {
        C c = new C();
        c.code = "DEMO-" + n;
        c.owner = priya;
        c.title = title;
        c.submittedAt = submitted;
        c.status = ClaimStatus.DRAFT;
        return c;
    }

    private void submitDemo(C c, ClaimStatus status, PolicyOutcome expected) {
        number(c);
        PolicyResult r = evaluate(c);
        if (r.outcome() != expected) {
            throw new IllegalStateException(c.code + " expected " + expected + " but policy engine said " + r.outcome() + " " + r.reasons());
        }
        finish(c, status, r);
        c.sample = SampleAudit.NONE;          // demo claims stay predictable (1008/1009 payable)
        if (status == ClaimStatus.CLARIFICATION_REQUESTED) {
            c.steps.get(c.steps.size() - 1).comment = "Hotel looks high for Pune - please attach the invoice and the workshop agenda.";
            c.comments.clear();
            c.comments.add(new String[] {Long.toString(rahul.id), c.steps.get(c.steps.size() - 1).comment});
        }
        claims.add(c);
    }

    // ------------------------------------------------------------------ payouts
    private void buildPayouts() {
        // PAID claims: weekly batches; IN_PAYOUT claims: one submitted batch awaiting the bank
        TreeMap<LocalDate, List<C>> byWeek = new TreeMap<>();
        for (C c : claims) {
            if (c.status == ClaimStatus.PAID) {
                Instant paid = c.decidedAt.plus(Duration.ofHours(24 + rnd.nextInt(96)));
                if (paid.isAfter(now.minus(Duration.ofHours(1)))) paid = now.minus(Duration.ofHours(1));
                c.paidAt = paid;
                LocalDate wk = paid.atZone(ZoneOffset.UTC).toLocalDate().with(DayOfWeek.FRIDAY);
                byWeek.computeIfAbsent(wk, k -> new ArrayList<>()).add(c);
            }
        }
        batches.clear();
        for (Map.Entry<LocalDate, List<C>> e : byWeek.entrySet()) {
            Instant latestPaid = e.getValue().stream().map(c -> c.paidAt).max(Instant::compareTo).orElse(now);
            batches.add(new Object[] {"PAID", e.getKey().isAfter(LocalDate.now(ZoneOffset.UTC)) ? LocalDate.now(ZoneOffset.UTC) : e.getKey(),
                    latestPaid.minus(Duration.ofHours(6)), latestPaid, "MOCKBANK-" + (100000 + rnd.nextInt(899999)), e.getValue()});
        }
        List<C> inPayout = claims.stream().filter(c -> c.status == ClaimStatus.IN_PAYOUT).toList();
        if (!inPayout.isEmpty()) {
            batches.add(new Object[] {"SUBMITTED", LocalDate.now(ZoneOffset.UTC), now.minus(Duration.ofHours(4)), null, null, inPayout});
        }
    }

    /** [status, valueDate, submittedAt, paidAt(unused), bankRef, claims]. */
    private final List<Object[]> batches = new ArrayList<>();

    // ------------------------------------------------------------------ write
    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private List<Long> ids(String seq, int n) {
        return n == 0 ? List.of() : jdbc.queryForList("select nextval('" + seq + "') from generate_series(1, ?)", Long.class, n);
    }

    private void write() {
        // departments
        Map<String, Long> deptIds = new HashMap<>();
        for (String[] d : DEPARTMENTS) {
            Long id = jdbc.queryForObject("insert into departments (code, name) values (?, ?) returning id", Long.class, d[0], d[1]);
            deptIds.put(d[0], id);
        }
        // users (one BCrypt hash for the shared synthetic demo password keeps seeding fast)
        String hash = passwordEncoder.encode(DEMO_PASSWORD);
        jdbc.batchUpdate("insert into users (id, employee_code, name, email, password_hash, department_id, manager_id, bank_account_no, ifsc, enabled, created_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, true, ?)", users, 200, (ps, u) -> {
                    ps.setLong(1, u.id); ps.setString(2, u.code); ps.setString(3, u.name); ps.setString(4, u.email); ps.setString(5, hash);
                    ps.setLong(6, deptIds.get(u.dept)); ps.setObject(7, u.managerId); ps.setString(8, u.bank); ps.setString(9, u.ifsc);
                    ps.setTimestamp(10, ts(u.createdAt));
                });
        List<Object[]> roleRows = new ArrayList<>();
        for (U u : users) for (String r : u.roles) roleRows.add(new Object[] {u.id, r});
        jdbc.batchUpdate("insert into user_roles (user_id, role_code) values (?, ?)", roleRows);

        // receipts
        List<Long> rIds = ids("receipts_id_seq", receipts.size());
        for (int i = 0; i < receipts.size(); i++) receipts.get(i).id = rIds.get(i);
        jdbc.batchUpdate("insert into receipts (id, owner_id, file_name, content_type, size, sha256, content, uploaded_at) values (?, ?, ?, 'application/pdf', ?, ?, ?, ?)",
                receipts, 500, (ps, r) -> {
                    ps.setLong(1, r.id); ps.setLong(2, r.ownerId); ps.setString(3, r.fileName); ps.setLong(4, r.content.length);
                    ps.setString(5, r.sha); ps.setBytes(6, r.content); ps.setTimestamp(7, ts(now.minus(Duration.ofDays(1))));
                });

        // claims
        List<Long> cIds = ids("claims_id_seq", claims.size());
        for (int i = 0; i < claims.size(); i++) claims.get(i).id = cIds.get(i);
        jdbc.batchUpdate("insert into claims (id, code, employee_id, title, status, total_amount, policy_outcome, policy_result, sample_audit, "
                + "submitted_at, decided_at, paid_at, created_at, updated_at, version) values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, 0)",
                claims, 500, (ps, c) -> {
                    ps.setLong(1, c.id); ps.setString(2, c.code); ps.setLong(3, c.owner.id); ps.setString(4, c.title); ps.setString(5, c.status.name());
                    ps.setBigDecimal(6, c.total()); ps.setString(7, c.outcome == null ? null : c.outcome.name()); ps.setString(8, c.policyJson);
                    ps.setString(9, c.sample.name()); ps.setTimestamp(10, ts(c.submittedAt)); ps.setTimestamp(11, ts(c.decidedAt));
                    ps.setTimestamp(12, ts(c.paidAt)); ps.setTimestamp(13, ts(c.createdAt));
                    Instant upd = c.paidAt != null ? c.paidAt : c.decidedAt != null ? c.decidedAt : time(c);
                    ps.setTimestamp(14, ts(upd));
                });

        // lines
        List<L> allLines = new ArrayList<>();
        Map<L, C> owner = new HashMap<>();
        for (C c : claims) for (L l : c.lines) { allLines.add(l); owner.put(l, c); }
        jdbc.batchUpdate("insert into claim_lines (claim_id, line_no, category, expense_date, amount, vendor, description, city, nights, receipt_id, violations, created_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)", allLines, 1000, (ps, l) -> {
                    C c = owner.get(l);
                    ps.setLong(1, c.id); ps.setInt(2, l.no); ps.setString(3, l.cat.name()); ps.setObject(4, l.date); ps.setBigDecimal(5, l.amount);
                    ps.setString(6, l.vendor); ps.setString(7, null); ps.setString(8, l.city); ps.setObject(9, l.nights);
                    ps.setObject(10, l.receipt == null ? null : l.receipt.id); ps.setString(11, l.violations); ps.setTimestamp(12, ts(c.createdAt));
                });

        // approval steps + comments
        List<Object[]> steps = new ArrayList<>();
        List<Object[]> comments = new ArrayList<>();
        for (C c : claims) {
            for (S s : c.steps) {
                steps.add(new Object[] {c.id, s.round, s.level.name(), s.assigneeId, s.actedById, s.decision, s.comment, ts(s.createdAt), ts(s.decidedAt)});
            }
            for (String[] cm : c.comments) {
                Instant at = c.decidedAt != null ? c.decidedAt : time(c);
                comments.add(new Object[] {c.id, Long.parseLong(cm[0]), cm[1], ts(at)});
            }
        }
        jdbc.batchUpdate("insert into approval_steps (claim_id, round, level, assignee_id, acted_by_id, decision, comment, created_at, decided_at) "
                + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)", steps);
        jdbc.batchUpdate("insert into claim_comments (claim_id, author_id, body, created_at) values (?, ?, ?, ?)", comments);

        // payout batches + items
        for (Object[] b : batches) {
            @SuppressWarnings("unchecked") List<C> items = (List<C>) b[5];
            BigDecimal total = items.stream().map(C::total).reduce(BigDecimal.ZERO, BigDecimal::add);
            Long batchId = jdbc.queryForObject("insert into payout_batches (status, created_by_id, item_count, total, value_date, bank_reference, created_at, submitted_at) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?) returning id", Long.class,
                    b[0], head.id, items.size(), total, b[1], b[4], ts(((Instant) b[2]).minus(Duration.ofHours(2))), ts((Instant) b[2]));
            jdbc.batchUpdate("insert into payout_items (batch_id, claim_id, amount, created_at) values (?, ?, ?, ?)",
                    items.stream().map(c -> new Object[] {batchId, c.id, c.total(), ts((Instant) b[2])}).toList());
        }
    }

    /** Summary counts for logs and docs/testing/seed-summary.md (rendered by SeedSummary). */
    public Map<ClaimStatus, Integer> statusCounts() {
        Map<ClaimStatus, Integer> m = new EnumMap<>(ClaimStatus.class);
        for (C c : claims) m.merge(c.status, 1, Integer::sum);
        return m;
    }
}
