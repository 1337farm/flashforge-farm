package com.flashforge.farm.boot;

import java.io.File;

import com.flashforge.farm.FarmApp;

public class ClearModelCacheTask extends BootTask {
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public ClearModelCacheTask() {
        super(()->{
            File cache = FarmApp.getModelCacheDir();
            if (cache.exists()) {
                for (File f : cache.listFiles()) {
                    f.delete();
                }
            }
        });
        nonCritical = true;
        onWorker();
    }
}
