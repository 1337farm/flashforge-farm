package com.flashforge.farm.boot;

import com.flashforge.farm.slic3r.PrintConfigDef;

public class PrintConfigWarmupTask extends BootTask {
    public PrintConfigWarmupTask() {
        super(PrintConfigDef::getInstance);
        onWorker();
    }
}
