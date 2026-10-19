package com.q3lives.ds.database.fulltext.cluster.resilience.distributed;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.q3lives.ds.database.fulltext.cluster.resilience.CircuitBreakerGuard;

/**
 * 跨节点 CircuitBreaker 状态快照（不可变 POJO）。
 *
 * <p>P36 OQ1 分布式熔断器 + Adaptive 自适应 MVP（2026-10-16）。
 * 沿用 P32 {@link CircuitBreakerGuard.State} 三态机（CLOSED/OPEN/HALF_OPEN），
 * 通过 {@link ClusterStateBroadcaster} 在节点间广播，跨进程 fingerprint 稳定。
 *
 * <p>硬约束：R7（threshold ∈ [0, 1]） + R8（openDurationMs ≥ 1） + serverId ∈ [0, 1024]。
 *
 * <p>红线：NG-3 零依赖（无 netty/grpc/p2p.client 引用），ZeroLeak3=3（仅在 resilience/distributed/ 子包）。
 *
 * @author SP3.1 Implement Agent
 * @since P36 OQ1 Distributed Circuit Breaker MVP
 */
public final class DistributedCircuitBreakerState {

    /** serverId 上限（避免广播风暴，uint16 范围） */
    public static final int MAX_SERVER_ID = 1024;

    /** 节点 ID（≥ 0 且 ≤ 1024） */
    public final int serverId;
    /** CB 状态（P32 enum 重用） */
    public final CircuitBreakerGuard.State state;
    /** OPEN 状态起始时间（System.nanoTime，0 表示非 OPEN） */
    public final long openedAtNanos;
    /** 当前失败率 ∈ [0, 1] */
    public final double failureRate;
    /** 当前 Adaptive 阈值 ∈ [0, 1] */
    public final double currentThreshold;
    /** 当前 Adaptive OPEN 持续时间（ms，≥ 1） */
    public final long currentOpenDurationMs;
    /** 采样时间戳（ms，≥ 0） */
    public final long sampleTimestampMs;

    public DistributedCircuitBreakerState(int serverId,
                                          CircuitBreakerGuard.State state,
                                          long openedAtNanos,
                                          double failureRate,
                                          double currentThreshold,
                                          long currentOpenDurationMs,
                                          long sampleTimestampMs) {
        if (serverId < 0 || serverId > MAX_SERVER_ID) {
            throw new IllegalArgumentException(
                    "serverId must be in [0, " + MAX_SERVER_ID + "], got " + serverId);
        }
        if (state == null) {
            throw new IllegalArgumentException("state cannot be null");
        }
        if (failureRate < 0.0 || failureRate > 1.0) {
            throw new IllegalArgumentException(
                    "failureRate must be in [0, 1], got " + failureRate);
        }
        if (currentThreshold < 0.0 || currentThreshold > 1.0) {
            throw new IllegalArgumentException(
                    "currentThreshold must be in [0, 1], got " + currentThreshold);
        }
        if (currentOpenDurationMs < 1) {
            throw new IllegalArgumentException(
                    "currentOpenDurationMs must be >= 1, got " + currentOpenDurationMs);
        }
        if (sampleTimestampMs < 0) {
            throw new IllegalArgumentException(
                    "sampleTimestampMs must be >= 0, got " + sampleTimestampMs);
        }
        this.serverId = serverId;
        this.state = state;
        this.openedAtNanos = openedAtNanos;
        this.failureRate = failureRate;
        this.currentThreshold = currentThreshold;
        this.currentOpenDurationMs = currentOpenDurationMs;
        this.sampleTimestampMs = sampleTimestampMs;
    }

    /**
     * 默认 CLOSED 状态快照（Adaptive 阈值取 P32 ResilienceConfig 默认 0.5）。
     */
    public static DistributedCircuitBreakerState closedDefault(int serverId) {
        return new DistributedCircuitBreakerState(
                serverId, CircuitBreakerGuard.State.CLOSED, 0L, 0.0, 0.5, 5_000L,
                System.currentTimeMillis());
    }

    public int getServerId() { return serverId; }
    public CircuitBreakerGuard.State getState() { return state; }
    public long getOpenedAtNanos() { return openedAtNanos; }
    public double getFailureRate() { return failureRate; }
    public double getCurrentThreshold() { return currentThreshold; }
    public long getCurrentOpenDurationMs() { return currentOpenDurationMs; }
    public long getSampleTimestampMs() { return sampleTimestampMs; }

    /**
     * 跨进程 fingerprint：SHA-256(serverId|state|failureRate|threshold|openDuration) 64-hex。
     */
    public static String fingerprint(DistributedCircuitBreakerState s) {
        if (s == null) {
            throw new IllegalArgumentException("state cannot be null");
        }
        String payload = s.serverId + "|" + s.state.name() + "|"
                + s.failureRate + "|" + s.currentThreshold + "|"
                + s.currentOpenDurationMs;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DistributedCircuitBreakerState)) return false;
        DistributedCircuitBreakerState that = (DistributedCircuitBreakerState) o;
        return serverId == that.serverId
                && openedAtNanos == that.openedAtNanos
                && Double.compare(that.failureRate, failureRate) == 0
                && Double.compare(that.currentThreshold, currentThreshold) == 0
                && currentOpenDurationMs == that.currentOpenDurationMs
                && sampleTimestampMs == that.sampleTimestampMs
                && state == that.state;
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverId, state, openedAtNanos, failureRate,
                currentThreshold, currentOpenDurationMs, sampleTimestampMs);
    }

    @Override
    public String toString() {
        return "DistributedCircuitBreakerState{serverId=" + serverId
                + ", state=" + state
                + ", failureRate=" + failureRate
                + ", currentThreshold=" + currentThreshold
                + ", currentOpenDurationMs=" + currentOpenDurationMs
                + ", sampleTimestampMs=" + sampleTimestampMs
                + ", fingerprint=" + fingerprint(this).substring(0, 16) + "}";
    }
}