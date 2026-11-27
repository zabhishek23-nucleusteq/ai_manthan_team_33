package com.expenseflow.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.User;
import com.expenseflow.repository.RoleRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtTokenService;

/** FR-15 super admin APIs over HTTP with PostgreSQL. Each test creates its own users (unique emails). */
class AdminApiTest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtTokenService tokens;

    private User admin;
    private String adminToken;
    private String employeeToken;

    @BeforeEach
    void setUp() {
        admin = newUser("admin", Role.SUPER_ADMIN);
        adminToken = tokenFor(admin);
        employeeToken = tokenFor(newUser("emp", Role.EMPLOYEE));
    }

    private User newUser(String prefix, String role) {
        User u = new User(prefix + " " + uid(), prefix + "-" + uid() + "@expenseflow.test", "$2a$10$notarealhash");
        u.getRoles().add(roles.findById(role).orElseThrow());
        return users.save(u);
    }

    private String tokenFor(User u) {
        return tokens.issue(u.getId(), u.getEmail(), u.roleCodes()).value();
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private HttpResponse<String> send(String method, String path, String token, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json));
        if (json != null) b.header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String range() {
        Instant now = Instant.now();
        return "from=" + enc(now.minus(Duration.ofHours(1))) + "&to=" + enc(now.plus(Duration.ofHours(1)));
    }

    private static String enc(Object o) {
        return URLEncoder.encode(String.valueOf(o), StandardCharsets.UTF_8);
    }

    private static long id(HttpResponse<String> res) {
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(res.body());
        assertThat(m.find()).isTrue();
        return Long.parseLong(m.group(1));
    }

    private HttpResponse<String> createStaff(String email, String rolesJson) throws Exception {
        return send("POST", "/api/admin/users", adminToken, "{\"name\":\"Staff Member\",\"email\":\"" + email
                + "\",\"password\":\"Demo@1234\",\"roles\":" + rolesJson + "}");
    }

    // ---- users ----

    @Test
    void createStaffUser_201_withRoles_andAudited() throws Exception {
        String email = "staff-" + uid() + "@expenseflow.test";
        HttpResponse<String> res = createStaff(email, "[\"MANAGER\",\"FINANCE_AUDITOR\"]");
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.body()).contains("\"roles\":[\"FINANCE_AUDITOR\",\"MANAGER\"]").doesNotContain("password");
        long id = id(res);

        HttpResponse<String> audit = send("GET", "/api/admin/audit?" + range() + "&action=USER_CREATED&entityType=USER"
                + "&actorId=" + admin.getId(), adminToken, null);
        assertThat(audit.statusCode()).isEqualTo(200);
        assertThat(audit.body()).contains("\"entityId\":\"" + id + "\"").contains(email)
                .contains("\"actor\":{\"id\":" + admin.getId()).doesNotContain("Demo@1234");
    }

    @Test
    void createStaffUser_duplicate409_unknownRole400_missingRoles400() throws Exception {
        String email = "staff-" + uid() + "@expenseflow.test";
        assertThat(createStaff(email, "[\"EMPLOYEE\"]").statusCode()).isEqualTo(201);
        HttpResponse<String> dup = createStaff(email.toUpperCase(), "[\"EMPLOYEE\"]");
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(dup.body()).contains("EMAIL_TAKEN");
        assertThat(createStaff("x-" + uid() + "@expenseflow.test", "[\"CEO\"]").statusCode()).isEqualTo(400);
        assertThat(createStaff("y-" + uid() + "@expenseflow.test", "[]").statusCode()).isEqualTo(400);
    }

    @Test
    void removeOwnAdmin_409_byRoleChange_andByDisablingSelf() throws Exception {
        HttpResponse<String> roleChange = send("PUT", "/api/admin/users/" + admin.getId() + "/roles", adminToken,
                "{\"roles\":[\"EMPLOYEE\"]}");
        assertThat(roleChange.statusCode()).isEqualTo(409);
        assertThat(roleChange.body()).contains("\"code\":\"CANNOT_REMOVE_OWN_ADMIN\"");

        HttpResponse<String> disable = send("PATCH", "/api/admin/users/" + admin.getId(), adminToken,
                "{\"enabled\":false}");
        assertThat(disable.statusCode()).isEqualTo(409);
        assertThat(disable.body()).contains("CANNOT_REMOVE_OWN_ADMIN");
        assertThat(users.findById(admin.getId()).orElseThrow().roleCodes()).contains(Role.SUPER_ADMIN);

        // keeping SUPER_ADMIN while adding a role is fine
        assertThat(send("PUT", "/api/admin/users/" + admin.getId() + "/roles", adminToken,
                "{\"roles\":[\"SUPER_ADMIN\",\"FINANCE_HEAD\"]}").statusCode()).isEqualTo(200);
    }

    @Test
    void replaceRoles_andDisable_otherUser_areAudited() throws Exception {
        User target = newUser("target", Role.EMPLOYEE);
        HttpResponse<String> res = send("PUT", "/api/admin/users/" + target.getId() + "/roles", adminToken,
                "{\"roles\":[\"MANAGER\"]}");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"roles\":[\"MANAGER\"]");

        HttpResponse<String> patched = send("PATCH", "/api/admin/users/" + target.getId(), adminToken,
                "{\"enabled\":false,\"managerId\":" + admin.getId() + "}");
        assertThat(patched.statusCode()).isEqualTo(200);
        assertThat(patched.body()).contains("\"enabled\":false").contains("\"manager\":{\"id\":" + admin.getId());

        HttpResponse<String> audit = send("GET", "/api/admin/audit?" + range() + "&entityType=USER&actorId="
                + admin.getId(), adminToken, null);
        assertThat(audit.body()).contains("USER_ROLES_CHANGED").contains("USER_DISABLED")
                .contains("\"before\":{").contains("\"roles\":[\"EMPLOYEE\"]").contains("\"roles\":[\"MANAGER\"]");
    }

    @Test
    void patch_unknownUser404_selfManager400_unknownDepartment400() throws Exception {
        User target = newUser("t", Role.EMPLOYEE);
        assertThat(send("PATCH", "/api/admin/users/99999999", adminToken, "{\"enabled\":true}").statusCode())
                .isEqualTo(404);
        assertThat(send("PATCH", "/api/admin/users/" + target.getId(), adminToken,
                "{\"managerId\":" + target.getId() + "}").statusCode()).isEqualTo(400);
        assertThat(send("PATCH", "/api/admin/users/" + target.getId(), adminToken, "{\"departmentId\":99999999}")
                .statusCode()).isEqualTo(400);
    }

    @Test
    void listUsers_searchAndRoleFilter_paged() throws Exception {
        String tag = uid();
        User a = new User("Zed " + tag, "zed-" + tag + "@expenseflow.test", "$2a$10$x");
        a.getRoles().add(roles.findById(Role.FINANCE_HEAD).orElseThrow());
        users.save(a);

        HttpResponse<String> byName = send("GET", "/api/admin/users?q=" + tag, adminToken, null);
        assertThat(byName.statusCode()).isEqualTo(200);
        assertThat(byName.body()).contains("\"total\":1").contains("zed-" + tag);
        assertThat(send("GET", "/api/admin/users?q=" + tag + "&role=FINANCE_HEAD", adminToken, null).body())
                .contains("\"total\":1");
        assertThat(send("GET", "/api/admin/users?q=" + tag + "&role=MANAGER", adminToken, null).body())
                .contains("\"total\":0");
        assertThat(send("GET", "/api/admin/users?size=1&page=0", adminToken, null).body())
                .contains("\"size\":1").contains("\"page\":0");
        assertThat(send("GET", "/api/admin/users?role=NOPE", adminToken, null).statusCode()).isEqualTo(400);
    }

    // ---- access ----

    @Test
    void admin_403_forNonAdmin_and401_withoutToken() throws Exception {
        List<String[]> calls = List.of(
                new String[] {"GET", "/api/admin/users", null},
                new String[] {"POST", "/api/admin/users", "{\"name\":\"Xx\",\"email\":\"x" + uid()
                        + "@e.test\",\"password\":\"Demo@1234\",\"roles\":[\"SUPER_ADMIN\"]}"},
                new String[] {"PUT", "/api/admin/users/" + admin.getId() + "/roles", "{\"roles\":[\"EMPLOYEE\"]}"},
                new String[] {"PATCH", "/api/admin/users/" + admin.getId(), "{\"enabled\":false}"},
                new String[] {"GET", "/api/admin/audit?" + range(), null},
                new String[] {"GET", "/api/admin/overview?" + range(), null},
                new String[] {"GET", "/api/admin/health", null});
        for (String[] c : calls) {
            HttpResponse<String> res = send(c[0], c[1], employeeToken, c[2]);
            assertThat(res.statusCode()).as("%s %s", c[0], c[1]).isEqualTo(403);
            assertThat(res.body()).contains("\"code\":\"FORBIDDEN\"");
            assertThat(send(c[0], c[1], null, c[2]).statusCode()).as("no token %s %s", c[0], c[1]).isEqualTo(401);
        }
        assertThat(users.findById(admin.getId()).orElseThrow().isEnabled()).isTrue();
    }

    // ---- audit / overview / health ----

    @Test
    void audit_filter_byActionAndRange_andInvalidRange400() throws Exception {
        createStaff("a-" + uid() + "@expenseflow.test", "[\"EMPLOYEE\"]");
        HttpResponse<String> onlyRoleChanges = send("GET", "/api/admin/audit?" + range()
                + "&action=USER_ROLES_CHANGED&actorId=" + admin.getId(), adminToken, null);
        assertThat(onlyRoleChanges.statusCode()).isEqualTo(200);
        assertThat(onlyRoleChanges.body()).contains("\"total\":0").doesNotContain("USER_CREATED");

        Instant past = Instant.now().minus(Duration.ofDays(30));
        HttpResponse<String> oldWindow = send("GET", "/api/admin/audit?from=" + enc(past) + "&to="
                + enc(past.plus(Duration.ofDays(1))) + "&actorId=" + admin.getId(), adminToken, null);
        assertThat(oldWindow.body()).contains("\"total\":0");

        Instant now = Instant.now();
        for (String q : List.of("from=" + enc(now) + "&to=" + enc(now),
                "from=" + enc(now.minus(Duration.ofDays(367))) + "&to=" + enc(now),
                "to=" + enc(now), "from=yesterday&to=" + enc(now))) {
            HttpResponse<String> bad = send("GET", "/api/admin/audit?" + q, adminToken, null);
            assertThat(bad.statusCode()).as(q).isEqualTo(400);
            assertThat(bad.body()).contains("INVALID_RANGE");
        }
    }

    @Test
    void overview_countsUsersByRole() throws Exception {
        HttpResponse<String> res = send("GET", "/api/admin/overview?" + range(), adminToken, null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"scope\":\"ALL\"").contains("\"EMPLOYEE\":").contains("\"MANAGER\":")
                .contains("\"FINANCE_AUDITOR\":").contains("\"FINANCE_HEAD\":").contains("\"SUPER_ADMIN\":")
                .contains("\"activeUsers\":").contains("\"range\":{\"from\":");
        assertThat(res.body()).doesNotContain("\"newUsersInRange\":0");
    }

    @Test
    void health_reportsServices_errorRate_andPendingAge() throws Exception {
        HttpResponse<String> res = send("GET", "/api/admin/health", adminToken, null);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("{\"name\":\"backend\",\"status\":\"UP\"")
                .contains("{\"name\":\"database\",\"status\":\"UP\"")
                .containsPattern("\\{\"name\":\"mock-bank\",\"status\":\"(UP|DOWN|DEGRADED)\"")
                .contains("\"errorRate5m\":").contains("\"oldestPendingApprovalHours\"");
    }
}
