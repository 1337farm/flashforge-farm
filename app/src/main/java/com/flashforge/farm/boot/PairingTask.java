package com.flashforge.farm.boot;

import com.flashforge.farm.FarmApp;
import com.flashforge.farm.utils.PairingRuntime;

public class PairingTask extends BootTask {
    public PairingTask() {
        super(()-> PairingRuntime.ensureStarted(FarmApp.INSTANCE));
        nonCritical = true;
        onWorker();
    }
}
