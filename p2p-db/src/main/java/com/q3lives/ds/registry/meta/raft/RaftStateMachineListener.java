package com.q3lives.ds.registry.meta.raft;

public interface RaftStateMachineListener {

    void onApplied(RaftServer.LogEntry entry, long appliedIndex);

    default byte[] readApplied(String registryPath) { return null; }
    default boolean existsApplied(String registryPath) { return readApplied(registryPath) != null; }
}
