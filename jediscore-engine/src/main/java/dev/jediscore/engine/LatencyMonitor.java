package dev.jediscore.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Basic latency monitoring behind the {@code LATENCY} command, mirroring Redis's
 * latency framework: per-event time series of spikes that crossed
 * {@code latency-monitor-threshold} milliseconds, plus the running max.
 *
 * <p>Command-thread-confined. Disabled by default (threshold {@code 0}), as in
 * Redis; raising the threshold (via {@code CONFIG SET}, Phase 7C) starts sampling.
 */
public final class LatencyMonitor {

    /** Redis keeps the last 160 samples per event. */
    private static final int MAX_SAMPLES = 160;

    /** One latency spike: when it happened and how long it was. */
    public record Sample(long timeSeconds, long latencyMillis) { }

    private final Map<String, Deque<Sample>> events = new LinkedHashMap<>();
    private final Map<String, Long> maxLatency = new LinkedHashMap<>();
    private long thresholdMillis;

    /**
     * Records the {@code command} event latency if monitoring is enabled and the
     * latency crosses the threshold.
     *
     * @param durationMicros the command duration in microseconds
     */
    public void maybeRecordCommand(long durationMicros) {
        if (thresholdMillis <= 0) {
            return;
        }
        long millis = durationMicros / 1000;
        if (millis >= thresholdMillis) {
            record("command", millis);
        }
    }

    /**
     * Records a latency sample for an event.
     *
     * @param event       the event name
     * @param latencyMillis the latency in milliseconds
     */
    public void record(String event, long latencyMillis) {
        Deque<Sample> series = events.computeIfAbsent(event, e -> new ArrayDeque<>());
        series.addLast(new Sample(System.currentTimeMillis() / 1000, latencyMillis));
        while (series.size() > MAX_SAMPLES) {
            series.removeFirst();
        }
        maxLatency.merge(event, latencyMillis, Math::max);
    }

    /** @return the latest sample per event, with its running max */
    public List<LatestEntry> latest() {
        List<LatestEntry> out = new ArrayList<>();
        for (Map.Entry<String, Deque<Sample>> e : events.entrySet()) {
            Sample last = e.getValue().peekLast();
            if (last != null) {
                out.add(new LatestEntry(e.getKey(), last.timeSeconds(), last.latencyMillis(),
                        maxLatency.getOrDefault(e.getKey(), last.latencyMillis())));
            }
        }
        return out;
    }

    /** The newest sample for one event plus its all-time max. */
    public record LatestEntry(String event, long timeSeconds, long latestMillis, long maxMillis) { }

    /**
     * @param event the event name
     * @return the full sample history for an event (oldest first)
     */
    public List<Sample> history(String event) {
        Deque<Sample> series = events.get(event);
        return series == null ? List.of() : new ArrayList<>(series);
    }

    /**
     * Resets monitoring data.
     *
     * @param eventsToReset specific events, or empty to reset all
     * @return the number of event time series cleared
     */
    public int reset(List<String> eventsToReset) {
        if (eventsToReset.isEmpty()) {
            int n = events.size();
            events.clear();
            maxLatency.clear();
            return n;
        }
        int n = 0;
        for (String event : eventsToReset) {
            if (events.remove(event) != null) {
                maxLatency.remove(event);
                n++;
            }
        }
        return n;
    }

    /** @return the monitored event names */
    public List<String> trackedEvents() {
        return new ArrayList<>(events.keySet());
    }

    /** @return the latency-monitor threshold in milliseconds ({@code 0} = disabled) */
    public long thresholdMillis() {
        return thresholdMillis;
    }

    /**
     * Sets the latency-monitor threshold ({@code latency-monitor-threshold}).
     *
     * @param millis milliseconds; {@code 0} disables monitoring
     */
    public void setThresholdMillis(long millis) {
        this.thresholdMillis = Math.max(0, millis);
    }
}
