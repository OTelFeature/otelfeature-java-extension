package io.otelfeature;

import java.time.Duration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls flagd's OFREP REST endpoint for the {@code telemetryLevel} flag and
 * caches the result.
 *
 * <p>When the flag value is {@code "IO"}, {@link #shouldSuppressInternal()}
 * returns {@code true}, indicating that {@code INTERNAL} spans should be
 * suppressed (filtered out before export). When the value is {@code "FULL"}
 * (or when flagd is unreachable), it returns {@code false}.
 *
 * <p>Uses only the JDK's built-in {@link java.net.http.HttpClient} — no
 * external dependencies — so the extension JAR stays lightweight and doesn't
 * conflict with the agent's classloader.
 *
 * <p>Configuration via environment variables:
 * <ul>
 *   <li>{@code FLAGD_HOST} — flagd host (default: {@code flagd})</li>
 *   <li>{@code FLAGD_PORT} — flagd OFREP REST port (default: {@code 8016})</li>
 *   <li>{@code FLAGD_POLL_INTERVAL_SECONDS} — poll interval (default: {@code 5})</li>
 *   <li>{@code OTELFEATURE_FLAG_NAME} — flag key to evaluate (default: {@code telemetryLevel})</li>
 * </ul>
 */
public class FlagdClient {

    private static final Logger log = LoggerFactory.getLogger(FlagdClient.class);

    private static final String FLAGD_HOST = System.getenv().getOrDefault("FLAGD_HOST", "flagd");
    private static final int FLAGD_PORT = Integer.parseInt(System.getenv().getOrDefault("FLAGD_PORT", "8016"));
    private static final int POLL_INTERVAL = Integer.parseInt(
            System.getenv().getOrDefault("FLAGD_POLL_INTERVAL_SECONDS", "5"));
    private static final String FLAG_NAME = System.getenv().getOrDefault("OTELFEATURE_FLAG_NAME", "telemetryLevel");

    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Boolean> suppressInternal = new AtomicReference<>(false);

    public FlagdClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "otelfeature-flagd-poller");
            t.setDaemon(true);
            return t;
        });

        // Poll immediately, then at fixed interval
        poll();
        scheduler.scheduleAtFixedRate(this::poll, POLL_INTERVAL, POLL_INTERVAL, TimeUnit.SECONDS);

        log.info("otelfeature-java-extension: polling flagd at {}:{} every {}s for flag '{}'",
                FLAGD_HOST, FLAGD_PORT, POLL_INTERVAL, FLAG_NAME);
    }

    private void poll() {
        try {
            URI uri = URI.create(String.format("http://%s:%d/ofrep/v1/evaluate/flags/%s",
                    FLAGD_HOST, FLAGD_PORT, FLAG_NAME));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"context\":{}}"))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String value = extractValue(response.body());
                boolean shouldSuppress = "IO".equalsIgnoreCase(value);
                boolean previous = suppressInternal.getAndSet(shouldSuppress);

                if (previous != shouldSuppress) {
                    log.info("otelfeature-java-extension: telemetryLevel changed to '{}' — INTERNAL spans {}",
                            value, shouldSuppress ? "suppressed" : "visible");
                }
            } else {
                log.debug("otelfeature-java-extension: flagd returned status {} — defaulting to no suppression",
                        response.statusCode());
                suppressInternal.set(false);
            }
        } catch (Exception e) {
            log.debug("otelfeature-java-extension: failed to poll flagd ({}:{}) — {}",
                    FLAGD_HOST, FLAGD_PORT, e.getMessage());
            // Keep the last known value — don't flip on transient errors
        }
    }

    /**
     * Extracts the {@code "value"} field from the OFREP JSON response.
     * Uses a simple regex instead of a JSON library to keep the extension
     * dependency-free.
     */
    private String extractValue(String json) {
        // Matches "value":"SOME_VALUE" in the response
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "\"value\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Returns {@code true} if {@code INTERNAL} spans should be suppressed
     * (i.e., the {@code telemetryLevel} flag is set to {@code "IO"}).
     *
     * <p>This is safe to call from any thread — the value is atomically
     * updated by the background poller and atomically read here.
     *
     * @return {@code true} if INTERNAL spans should be filtered out
     */
    public boolean shouldSuppressInternal() {
        return suppressInternal.get();
    }
}
