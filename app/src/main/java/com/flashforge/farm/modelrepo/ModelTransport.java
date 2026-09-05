package com.flashforge.farm.modelrepo;

import java.io.File;
import java.util.List;

public interface ModelTransport {
    interface Listener {
        void onMetadata(String ticket, ModelMetadata meta, List<Entry> entries);
        void onProgress(String ticket, long downloadedBytes, long totalBytes);
        void onComplete(String ticket, File dir);
        void onError(String ticket, String msg);
    }

    class Entry {
        public final String name;
        public final long size;

        public Entry(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    class UnavailableException extends Exception {
        public UnavailableException(String msg) {
            super(msg);
        }
    }

    void fetch(String ticket, File dir, Listener listener) throws UnavailableException;

    void stop(String ticket);

    static ModelTransport unavailable(final String reason) {
        return new ModelTransport() {
            @Override
            public void fetch(String ticket, File dir, Listener listener) throws UnavailableException {
                throw new UnavailableException(reason);
            }

            @Override
            public void stop(String ticket) {
            }
        };
    }
}
