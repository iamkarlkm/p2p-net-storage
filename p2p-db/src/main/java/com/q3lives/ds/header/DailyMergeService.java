package com.q3lives.ds.header;

import java.io.Closeable;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.q3lives.ds.database.config.DsDbConfig;

public class DailyMergeService implements Closeable {

    private static volatile DailyMergeService INSTANCE;
    private static final Object LOCK = new Object();

    public static DailyMergeService getInstance() {
        if (INSTANCE == null) {
            synchronized (LOCK) {
                if (INSTANCE == null) {
                    INSTANCE = new DailyMergeService(DsDbConfig.getInstance().getMergeHourOfDay());
                }
            }
        }
        return INSTANCE;
    }

    public static void resetForTest(int hour) {
        synchronized (LOCK) {
            if (INSTANCE != null) {
                try { INSTANCE.close(); } catch (IOException ignore) {}
                INSTANCE = null;
            }
            INSTANCE = new DailyMergeService(hour);
        }
    }

    public static void forceResetForTest() {
        synchronized (LOCK) {
            if (INSTANCE != null) {
                try { INSTANCE.close(); } catch (IOException ignore) {}
                INSTANCE = null;
            }
        }
    }

    private final int mergeHourOfDay;
    private final Set<HeaderTieredStore> registered = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> scheduledTask;
    private final AtomicInteger mergeCycle = new AtomicInteger(0);

    private DailyMergeService(int mergeHourOfDay) {
        if (mergeHourOfDay < 0) mergeHourOfDay = 0;
        if (mergeHourOfDay > 23) mergeHourOfDay = 23;
        this.mergeHourOfDay = mergeHourOfDay;
        ThreadFactory tf = new ThreadFactory() {
            final AtomicInteger id = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "header-tier-merge-" + id.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        this.scheduler = Executors.newSingleThreadScheduledExecutor(tf);
        scheduleNext();
    }

    private void scheduleNext() {
        long delayMillis = computeDelayMillisToNextRun();
        scheduledTask = scheduler.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    runMergeCycle();
                } finally {
                    scheduleNext();
                }
            }
        }, delayMillis, TimeUnit.MILLISECONDS);
    }

    private long computeDelayMillisToNextRun() {
        LocalDateTime now = LocalDateTime.now();
        LocalTime target = LocalTime.of(mergeHourOfDay, 0, 0);
        LocalDateTime nextRun = LocalDateTime.of(now.toLocalDate(), target);
        if (!nextRun.isAfter(now)) {
            nextRun = LocalDateTime.of(now.toLocalDate().plusDays(1), target);
        }
        long nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long runMillis = nextRun.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long delay = runMillis - nowMillis;
        if (delay <= 0) delay = 60_000L;
        return delay;
    }

    public void register(HeaderTieredStore store) {
        if (store == null) return;
        registered.add(store);
    }

    public void unregister(HeaderTieredStore store) {
        if (store == null) return;
        registered.remove(store);
    }

    public int registeredCount() {
        return registered.size();
    }

    public int currentCycle() {
        return mergeCycle.get();
    }

    public int runMergeCycleNow() {
        return runMergeCycle();
    }

    public int forceRolloverAllNow() {
        String dayKey = "manual-roll-" + System.currentTimeMillis();
        int ok = 0;
        for (HeaderTieredStore s : registered) {
            try { s.forceRolloverNow(); ok++; } catch (Exception ignore) {}
        }
        return ok;
    }

    public int forceMergeAllNow() {
        int prepared = 0;
        int applied = 0;
        for (HeaderTieredStore s : registered) {
            try {
                if (s.prepareMerge()) prepared++;
            } catch (Exception ignore) {}
        }
        for (HeaderTieredStore s : registered) {
            try {
                s.mergeRunnable();
            } catch (Exception ignore) {}
        }
        for (HeaderTieredStore s : registered) {
            try {
                if (s.applyMergedToBase()) applied++;
            } catch (Exception ignore) {}
        }
        return applied;
    }

    private int runMergeCycle() {
        int cycle = mergeCycle.incrementAndGet();
        String dayKey = LocalDate.now().toString();
        int prepared = 0;
        int applied = 0;
        int failed = 0;
        for (HeaderTieredStore store : registered) {
            if (store == null) continue;
            try {
                store.rollover(dayKey + "-c" + cycle);
            } catch (Exception e) {
                failed++;
            }
        }
        for (HeaderTieredStore store : registered) {
            if (store == null) continue;
            try {
                if (store.prepareMerge()) prepared++;
            } catch (Exception e) {
                failed++;
            }
        }
        for (HeaderTieredStore store : registered) {
            if (store == null) continue;
            try {
                store.mergeRunnable();
            } catch (Exception e) {
                failed++;
            }
        }
        for (HeaderTieredStore store : registered) {
            if (store == null) continue;
            try {
                if (store.applyMergedToBase()) applied++;
            } catch (Exception e) {
                failed++;
            }
        }
        return applied;
    }

    @Override
    public void close() throws IOException {
        try {
            ScheduledFuture<?> s = scheduledTask;
            if (s != null) s.cancel(false);
            scheduler.shutdown();
            boolean done = false;
            try {
                done = scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!done) {
                scheduler.shutdownNow();
            }
        } finally {
            registered.clear();
        }
    }
}
