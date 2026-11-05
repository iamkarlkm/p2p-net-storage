package com.q3lives.ds.registry.meta.raft;

import java.util.concurrent.ConcurrentHashMap;

public class MemoryRaftStateMachine implements RaftStateMachineListener {

    public final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<String, Long> appliedLogIndex = new ConcurrentHashMap<>();
    public volatile long lastApplied = 0L;

    @Override
    public void onApplied(RaftServer.LogEntry entry, long appliedIndex) {
        this.lastApplied = appliedIndex;
        if (entry == null) return;
        switch (entry.cmdType) {
            case RaftServer.CMD_NOOP:
                return;
            case RaftServer.CMD_PUT_REGISTRY:
            case RaftServer.CMD_META_SAVE:
                if (entry.registryPath != null && entry.payload != null) {
                    byte[] copy = new byte[entry.payload.length];
                    System.arraycopy(entry.payload, 0, copy, 0, copy.length);
                    store.put(entry.registryPath, copy);
                    appliedLogIndex.put(entry.registryPath, appliedIndex);
                }
                return;
            case RaftServer.CMD_REMOVE_REGISTRY:
                if (entry.registryPath != null) {
                    store.remove(entry.registryPath);
                    appliedLogIndex.put(entry.registryPath, appliedIndex);
                }
                return;
            default:
                return;
        }
    }

    @Override
    public byte[] readApplied(String registryPath) {
        return store.get(registryPath);
    }

    @Override
    public boolean existsApplied(String registryPath) {
        return store.containsKey(registryPath);
    }
}
