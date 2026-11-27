package com.expenseflow.observability;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * POST /api/telemetry/page-load {route, ms} -> histogram {@code expenseflow_page_load_seconds{route}}.
 * Routes are normalised to keep tag cardinality low: ids become {@code :id}, query strings are dropped, unknown
 * shapes and anything beyond {@link #MAX_ROUTES} distinct routes are tagged {@code other}.
 */
@RestController
public class PageLoadTelemetry {

    static final int MAX_ROUTES = 50;
    static final String OTHER = "other";
    private static final Pattern SAFE = Pattern.compile("/[a-z0-9/_:-]{0,99}");
    private static final Pattern ID_SEGMENT = Pattern.compile(
            "/(\\d+|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[a-z]+-\\d+)(?=/|$)");

    public record PageLoad(@NotBlank @Size(max = 100) String route, @NotNull @Min(0) @Max(120_000) Long ms) {
    }

    private final MeterRegistry registry;
    private final Set<String> knownRoutes = ConcurrentHashMap.newKeySet();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public PageLoadTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    @PostMapping("/api/telemetry/page-load")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@Valid @RequestBody PageLoad body) {
        String route = tagFor(body.route());
        Duration took = Duration.ofMillis(body.ms());
        // NFR-OBS name + the name the Grafana dashboard queries (sre-handbook s2)
        timer("expenseflow.page.load", route).record(took);
        timer("page.load", route).record(took);
    }

    private Timer timer(String name, String route) {
        return timers.computeIfAbsent(name + "|" + route, k -> Timer.builder(name)
                .description("Page load time reported by the UI").tag("route", route)
                .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(50))
                .maximumExpectedValue(Duration.ofSeconds(30)).register(registry));
    }

    String tagFor(String rawRoute) {
        String route = normalise(rawRoute);
        if (route.equals(OTHER) || knownRoutes.contains(route)) {
            return route;
        }
        synchronized (knownRoutes) {
            if (knownRoutes.size() >= MAX_ROUTES) {
                return OTHER;
            }
            knownRoutes.add(route);
            return route;
        }
    }

    static String normalise(String raw) {
        String r = raw.trim().toLowerCase();
        int q = r.indexOf('?');
        if (q >= 0) r = r.substring(0, q);
        int h = r.indexOf('#');
        if (h >= 0) r = r.substring(0, h);
        if (r.length() > 1 && r.endsWith("/")) r = r.substring(0, r.length() - 1);
        r = ID_SEGMENT.matcher(r).replaceAll("/:id");
        return SAFE.matcher(r).matches() ? r : OTHER;
    }
}
