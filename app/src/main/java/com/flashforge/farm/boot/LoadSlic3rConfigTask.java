package com.flashforge.farm.boot;

import java.io.File;
import java.io.IOException;

import com.flashforge.farm.FarmApp;
import com.flashforge.farm.slic3r.Slic3rConfigWrapper;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class LoadSlic3rConfigTask extends BootTask {
    public LoadSlic3rConfigTask() {
        super(() -> {
            File cfgFile = FarmApp.getConfigFile();
            FarmApp.getCurrentConfigFile().delete();
            if (cfgFile.exists()) {
                try {
                    FarmApp.CONFIG = new Slic3rConfigWrapper(cfgFile);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        onWorker();
    }
}
