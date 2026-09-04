package com.flashforge.farm.boot;

import java.io.IOException;

import com.flashforge.farm.FarmApp;

public class CheckUpdateJsonTask extends BootTask {
    public CheckUpdateJsonTask() {
        super(() -> {
            try {
                FarmApp.INSTANCE.getAssets().open("update.json").close();
                FarmApp.hasUpdateInfo = true;
            } catch (IOException e) {
                FarmApp.hasUpdateInfo = false;
            }
        });
        onWorker();
    }
}
