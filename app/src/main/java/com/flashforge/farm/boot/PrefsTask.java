package com.flashforge.farm.boot;

import com.flashforge.farm.FarmApp;
import com.flashforge.farm.utils.Prefs;

public class PrefsTask extends BootTask {
    public PrefsTask() {
        super(()->Prefs.init(FarmApp.INSTANCE));
    }
}
