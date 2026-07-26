package com.q3lives.ds.fs.mft;

import java.util.List;

/**
 * DsMftFileSystem 全局配置文件模型。
 *
 * <p>对应 YAML 格式示例：</p>
 * <pre>
 * fsName: "myfs"
 * namespaceDir: "./mft_data"
 * atimeEnabled: true
 * auditLogEnabled: true
 * namespaceDirs:
 *   - "/data"
 *   - "/tmp"
 *   - "/logs"
 * tagsInitialRingCap: 64
 * </pre>
 */
public final class DsMftFileSystemConfig {
    private String fsName;
    private String namespaceDir;
    private boolean atimeEnabled = true;
    private boolean auditLogEnabled = true;
    private List<String> namespaceDirs;
    private int tagsInitialRingCap = 64;

    public String getFsName() {
        return fsName;
    }

    public void setFsName(String fsName) {
        this.fsName = fsName;
    }

    public String getNamespaceDir() {
        return namespaceDir;
    }

    public void setNamespaceDir(String namespaceDir) {
        this.namespaceDir = namespaceDir;
    }

    public boolean isAtimeEnabled() {
        return atimeEnabled;
    }

    public void setAtimeEnabled(boolean atimeEnabled) {
        this.atimeEnabled = atimeEnabled;
    }

    public boolean isAuditLogEnabled() {
        return auditLogEnabled;
    }

    public void setAuditLogEnabled(boolean auditLogEnabled) {
        this.auditLogEnabled = auditLogEnabled;
    }

    public List<String> getNamespaceDirs() {
        return namespaceDirs;
    }

    public void setNamespaceDirs(List<String> namespaceDirs) {
        this.namespaceDirs = namespaceDirs;
    }

    public int getTagsInitialRingCap() {
        return tagsInitialRingCap;
    }

    public void setTagsInitialRingCap(int tagsInitialRingCap) {
        this.tagsInitialRingCap = tagsInitialRingCap;
    }
}
