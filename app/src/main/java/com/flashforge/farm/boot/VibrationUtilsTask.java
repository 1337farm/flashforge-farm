package com.flashforge.farm.boot;

import com.flashforge.farm.FarmApp;
import com.flashforge.farm.utils.VibrationUtils;

public class VibrationUtilsTask extends BootTask {

    public VibrationUtilsTask() {
        super(() -> VibrationUtils.init(FarmApp.INSTANCE));
        onWorker();
    }
}
