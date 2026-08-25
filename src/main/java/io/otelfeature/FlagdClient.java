package io.otelfeature;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Polls flagd's OFREP REST endpoint for the {@code telemetryLevel} flag and
 * caches the result.
 *
 * <p>When the flag value is {@code "IO"}, {@link #shouldSuppressInternal()}
 * returns {@code true}, indicating that {@code INTERNAL} spans should be
 * suppressed (filtered out before export). When the value is {@code "FULL"}
 * (or when flagd is unreachable), it returns {@code false}.
 *
 * <p>Uses {@link HttpURLConnection} instead of {@link java.net.http.HttpClient}
 * to avoid classloader conflicts with the OTel agent's HTTP client
 * instrumentation. The extension runs in the agent's extension classloader,
 * where the agent's bytecode-instrumented {@code HttpClient} can cause
 * connection failures.
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

    private static final Logger log = Logger.getLogger(FlagdClient.class.getName());

    private static final String FLAGD_HOST = System.getenv().getOrDefault("FLAGD_HOST", "flagd");
    private static final int FLAGD_PORT = Integer.parseInt(System.getenv().getOrDefault("FLAGD_PORT", "8016"));
    private static final int POLL_INTERVAL = Integer.parseInt(
            System.getenv().getOrDefault("FLAGD_POLL_INTERVAL_SECONDS", "5"));
    private static final String FLAG_NAME = System.getenv().getOrDefault("OTELFEATURE_FLAG_NAME", "telemetryLevel");

    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Boolean> suppressInternal = new AtomicReference<>(false);

    /**
     * Creates a FlagdClient and starts polling flagd in the background.
     */
    public FlagdClient() {
        this(false);
    }

    /**
     * Internal constructor for testing. When {@code testMode} is {@code true},
     * no HTTP polling is started and {@link #shouldSuppressInternal()}
     * returns {@code false} until overridden by a test subclass.
     *
     * @param testMode {@code true} to skip background polling
     */
    FlagdClient(boolean testMode) {
        if (testMode) {
            this.scheduler = null;
            return;
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "otelfeature-flagd-poller");
            t.setDaemon(true);
            return t;
        });

        // Wrap in a try-catch so no exception can kill the scheduled task.
        Runnable safePoll = () -> {
            try {
                poll();
            } catch (Throwable t) {
                log.log(Level.WARNING, "otelfeature-java-extension: unexpected error polling flagd", t);
            }
        };

        // Schedule first poll immediately on the background thread (non-blocking).
        // Until the first poll completes, shouldSuppressInternal() returns false (no suppression).
        scheduler.scheduleAtFixedRate(safePoll, 0, POLL_INTERVAL, TimeUnit.SECONDS);

        log.info("otelfeature-java-extension: polling flagd at " + FLAGD_HOST + ":" + FLAGD_PORT
                + " every " + POLL_INTERVAL + "s for flag '" + FLAG_NAME + "'");
    }

    private void poll() {
        HttpURLConnection conn = null;
        try {
            URI uri = URI.create(String.format("http://%s:%d/ofrep/v1/evaluate/flags/%s",
                    FLAGD_HOST, FLAGD_PORT, FLAG_NAME));

            conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write("{\"context\":{}}".getBytes(StandardCharsets.UTF_8));
            }

            int status = conn.getResponseCode();

            if (status == 200) {
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        body.append(line);
                    }
                }

                String value = extractValue(body.toString());
                boolean shouldSuppress = "IO".equalsIgnoreCase(value);
                boolean previous = suppressInternal.getAndSet(shouldSuppress);

                if (previous != shouldSuppress) {
                    log.info("otelfeature-java-extension: telemetryLevel changed to '" + value
                            + "' — INTERNAL spans " + (shouldSuppress ? "suppressed" : "visible"));
                }
            } else {
                log.fine("otelfeature-java-extension: flagd returned status " + status
                        + " — defaulting to no suppression");
                suppressInternal.set(false);
            }
        } catch (Exception e) {
            log.fine("otelfeature-java-extension: failed to poll flagd (" + FLAGD_HOST + ":" + FLAGD_PORT
                    + ") — " + e.getMessage());
            // Keep the last known value — don't flip on transient errors
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Extracts the {@code "value"} field from the OFREP JSON response.
     * Uses a simple regex instead of a JSON library to keep the extension
     * dependency-free.
     */
    private String extractValue(String json) {
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

    /**
     * Shuts down the background polling thread.
     * No-op in test mode.
     */
    public void shutdown() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}
