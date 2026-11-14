package com.erpschool.saas.access;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Counts API requests per school over the last few minutes.
 *
 * <p>This is the only per-school runtime signal a shared application can
 * measure honestly. It is used to show each school's share of current load and
 * to apportion the application's real memory use for the logical RAM budget.
 * It does not, and cannot, reserve memory for a school.</p>
 */
@Component
public class TenantLoadTracker {

    private static final int BUCKETS = 5;
    private static final long BUCKET_MS = 60_000;

    private final ConcurrentHashMap<UUID, Window> windows = new ConcurrentHashMap<>();
    private final Window total = new Window();

    public void record(UUID tenantId) {
        long now = System.currentTimeMillis();
        total.hit(now);
        if (tenantId != null) {
            windows.computeIfAbsent(tenantId, id -> new Window()).hit(now);
        }
    }

    /** Requests in the last five minutes for one school. */
    public long recent(UUID tenantId) {
        Window w = windows.get(tenantId);
        return w == null ? 0 : w.sum(System.currentTimeMillis());
    }

    public long recentTotal() {
        return total.sum(System.currentTimeMillis());
    }

    /** Share (0..1) of all recent tenant requests that belong to this school. */
    public double share(UUID tenantId) {
        long now = System.currentTimeMillis();
        long all = 0;
        for (Window w : windows.values()) {
            all += w.sum(now);
        }
        return all == 0 ? 0 : (double) recent(tenantId) / all;
    }

    public Map<UUID, Long> snapshot() {
        long now = System.currentTimeMillis();
        Map<UUID, Long> out = new LinkedHashMap<>();
        windows.forEach((id, w) -> {
            long n = w.sum(now);
            if (n > 0) {
                out.put(id, n);
            }
        });
        return out;
    }

    private static final class Window {
        private final AtomicLongArray counts = new AtomicLongArray(BUCKETS);
        private final AtomicLongArray stamps = new AtomicLongArray(BUCKETS);

        void hit(long now) {
            long minute = now / BUCKET_MS;
            int i = (int) (minute % BUCKETS);
            if (stamps.get(i) != minute) {
                stamps.set(i, minute);
                counts.set(i, 0);
            }
            counts.incrementAndGet(i);
        }

        long sum(long now) {
            long minute = now / BUCKET_MS;
            long sum = 0;
            for (int i = 0; i < BUCKETS; i++) {
                if (minute - stamps.get(i) < BUCKETS) {
                    sum += counts.get(i);
                }
            }
            return sum;
        }
    }
}
