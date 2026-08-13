package com.q3lives.ds.header;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

public interface StoreIdRegistry extends Closeable {

    long intern(String relativePath) throws IOException;

    Long lookupIfRegistered(String relativePath);

    String resolvePath(long storeId) throws IOException;

    long nextAssignedStoreId();

    String registryDebugName();

    File getBaseDir();

    boolean isReady();
}
