package tn.supcom.tupletrace.db;

import jakarta.json.Json;
import tn.supcom.tupletrace.security.PasswordAccounts;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

final class LoginTimingChecks {
    // A single origin has a budget of 20 failures per 15 minutes under migration V5.
    private static final int WARMUP_PAIRS = 2;
    private static final int MEASURED_PAIRS = 8;
    private static final int ORDER_SEED = 6238;

    private final Connection operator;
    private final HttpClient client;

    LoginTimingChecks(Connection operator, HttpClient client) {
        this.operator = operator;
        this.client = client;
    }

    private record Attempt(String email, HttpRequest request) {}
    private record Pair(Attempt active, Attempt unknown) {}
    private record Observation(int status, double millis) {}

    void run() throws Exception {
        var org = UUID.randomUUID();
        try (var insert = operator.prepareStatement("INSERT INTO tt.organization(id,name,status) VALUES (?,'Timing IT','ACTIVE')")) {
            insert.setObject(1, org);
            insert.executeUpdate();
        }
        var pairs = new ArrayList<Pair>();
        for (int i = 0; i < WARMUP_PAIRS + MEASURED_PAIRS; i++) pairs.add(prepare(org));

        var order = new ArrayList<Boolean>();
        for (int i = 0; i < MEASURED_PAIRS; i++) order.add(i % 2 == 0);
        Collections.shuffle(order, new Random(ORDER_SEED));

        var activeTimes = new ArrayList<Double>();
        var unknownTimes = new ArrayList<Double>();
        var differences = new ArrayList<Double>();
        System.out.println("Login timing: pair,phase,first,active_result,active_ms,unknown_result,unknown_ms");
        for (int i = 0; i < pairs.size(); i++) {
            var pair = pairs.get(i);
            boolean warmup = i < WARMUP_PAIRS;
            boolean activeFirst = warmup ? i % 2 == 0 : order.get(i - WARMUP_PAIRS);
            var first = send(activeFirst ? pair.active : pair.unknown);
            var second = send(activeFirst ? pair.unknown : pair.active);
            var active = activeFirst ? first : second;
            var unknown = activeFirst ? second : first;
            var activeResult = classify(pair.active, active);
            var unknownResult = classify(pair.unknown, unknown);
            System.out.printf(Locale.ROOT, "%d,%s,%s,%s,%.3f,%s,%.3f%n", i + 1,
                    warmup ? "warmup" : "sample", activeFirst ? "active" : "unknown",
                    activeResult, active.millis, unknownResult, unknown.millis);
            if (!warmup && activeResult == Outcome.CHECK && unknownResult == Outcome.CHECK) {
                activeTimes.add(active.millis);
                unknownTimes.add(unknown.millis);
                differences.add(active.millis - unknown.millis);
            }
        }
        System.out.printf("Measured pairs: %d retained, %d excluded%n", differences.size(), MEASURED_PAIRS - differences.size());
        assertTrue(differences.size() >= 4, "Insufficient admitted pairs; rerun on a fresh disposable instance");
        Collections.sort(activeTimes);
        Collections.sort(unknownTimes);
        Collections.sort(differences);
        System.out.printf(Locale.ROOT,
                "Latency ms: active median=%.3f p90=%.3f; unknown median=%.3f p90=%.3f; paired median(active-unknown)=%.3f; two-sided sign p=%.4f%n",
                median(activeTimes), p90(activeTimes), median(unknownTimes), p90(unknownTimes),
                median(differences), signTest(differences));
    }

    private Pair prepare(UUID org) throws Exception {
        UUID user = UUID.randomUUID();
        String active = user + "@example.invalid";
        String unknown = UUID.randomUUID() + "@example.invalid";
        try (var insert = operator.prepareStatement("INSERT INTO tt.app_user(id,email,organization_id,status) VALUES (?,?,?,'ACTIVE')")) {
            insert.setObject(1, user);
            insert.setString(2, active);
            insert.setObject(3, org);
            insert.executeUpdate();
        }
        char[] password = UUID.randomUUID().toString().toCharArray();
        try {
            PasswordAccounts.enroll(operator, user, password);
        } finally {
            Arrays.fill(password, '\0');
        }
        String incorrect = "invalid-" + UUID.randomUUID();
        return new Pair(attempt(active, incorrect), attempt(unknown, incorrect));
    }

    private static Attempt attempt(String email, String password) {
        String json = Json.createObjectBuilder().add("email", email).add("password", password).build().toString();
        var request = HttpRequest.newBuilder(URI.create("https://localhost:8443/api/auth/login"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return new Attempt(email, request);
    }

    private Observation send(Attempt attempt) throws Exception {
        long start = System.nanoTime();
        var response = client.send(attempt.request, HttpResponse.BodyHandlers.ofByteArray());
        long elapsed = System.nanoTime() - start;
        assertTrue(response.statusCode() == 401 || response.statusCode() == 429, "Unexpected HTTP " + response.statusCode());
        assertEquals(0, response.body().length);
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        return new Observation(response.statusCode(), elapsed / 1_000_000.0);
    }

    private enum Outcome { CHECK, LIMITED_429, UNCONFIRMED_401 }

    private Outcome classify(Attempt attempt, Observation result) throws SQLException {
        if (result.status == 429) return Outcome.LIMITED_429;
        // V5 records account_key only on CHECK. Each identifier is submitted exactly once.
        try (var query = operator.prepareStatement("""
                SELECT count(*) FROM tt.login_failure WHERE account_key=sha256(convert_to(?,'UTF8'))
                """)) {
            query.setString(1, attempt.email);
            try (var row = query.executeQuery()) {
                row.next();
                return row.getInt(1) == 1 ? Outcome.CHECK : Outcome.UNCONFIRMED_401;
            }
        }
    }

    private static double median(List<Double> sorted) {
        int n = sorted.size();
        return (sorted.get((n - 1) / 2) + sorted.get(n / 2)) / 2;
    }

    private static double p90(List<Double> sorted) {
        return sorted.get((int) Math.ceil(0.9 * sorted.size()) - 1);
    }

    private static double signTest(List<Double> differences) {
        int positive = (int) differences.stream().filter(d -> d > 0).count();
        int negative = (int) differences.stream().filter(d -> d < 0).count();
        int n = positive + negative;
        double combinations = 1;
        double tail = 1;
        for (int k = 1; k <= Math.min(positive, negative); k++) {
            combinations *= (double) (n - k + 1) / k;
            tail += combinations;
        }
        return Math.min(1, Math.scalb(2 * tail, -n));
    }
}
