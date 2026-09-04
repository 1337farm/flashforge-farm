package com.flashforge.farm.boot;

import java.util.Collections;
import java.util.List;

public class BootTask {
    public final List<Class<?>> dependencies;
    public final Runnable run;
    public boolean workerThread;
    public int priority;
    public boolean nonCritical;

    /* package */ int index;

    public BootTask(Runnable run) {
        this.dependencies = Collections.emptyList();
        this.run = run;
    }

    public BootTask(List<Class<?>> dependencies, Runnable run) {
        this.dependencies = dependencies;
        this.run = run;
    }

    public BootTask onWorker() {
        return onWorker(-20);
    }

    public BootTask onWorker(int priority) {
        this.workerThread = true;
        this.priority = priority;
        return this;
    }
}
