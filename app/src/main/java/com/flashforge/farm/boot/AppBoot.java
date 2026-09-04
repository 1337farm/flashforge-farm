package com.flashforge.farm.boot;

import android.util.Log;
import android.util.SparseBooleanArray;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.flashforge.farm.BuildConfig;

public class AppBoot {
    private final static String TAG = "boot";

    static ExecutorService executor = Executors.newCachedThreadPool();
    static List<BootTask> tasks;
    static List<Runnable> pendingMain = new ArrayList<>();
    static List<BootTask> pendingTasks = new ArrayList<>();
    static SparseBooleanArray completed = new SparseBooleanArray();
    static CountDownLatch latch;

    public static void run(List<BootTask> tasks) {
        long start = System.currentTimeMillis();
        AppBoot.tasks = tasks;
        int size = tasks.size();
        for (int i = 0, s = tasks.size(); i < s; i++) {
            BootTask task = tasks.get(i);
            if (task.nonCritical) {
                if (!task.workerThread) {
                    throw new IllegalArgumentException("Can't schedule non-critical task on main thread");
                }
                size--;
            }
        }
        AppBoot.latch = new CountDownLatch(size);

        for (int i = 0, s = tasks.size(); i < s; i++) {
            BootTask task = tasks.get(i);
            task.index = i;
            tryRunTask(task, true, false);
        }
        try {
            while (!latch.await(10, TimeUnit.MILLISECONDS)) {
                if (!pendingMain.isEmpty()) {
                    List<Runnable> clone = new ArrayList<>(pendingMain);
                    for (Runnable r : clone) {
                        r.run();
                    }
                    pendingMain.removeAll(clone);
                }
            }
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Boot in " + (System.currentTimeMillis() - start) + "ms");
            }
            tryShutdown();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void tryShutdown() {
        if (completed.size() == tasks.size()) {
            executor.shutdown();
            executor = null;
            tasks = null;
            pendingMain = null;
            pendingTasks = null;
            completed = null;
            latch = null;
        }
    }

    private static void tryRunTask(BootTask task, boolean fromMain, boolean isContinue) {
        if (checkDependencies(task.dependencies)) {
            Runnable r = () -> {
                try {
                    task.run.run();
                } catch (Throwable t) {
                    Log.e(TAG, "Error while executing boot task", t);
                }
                completed.put(task.index, true);
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Finish " + task);
                }
                if (!task.nonCritical) {
                    latch.countDown();
                } else {
                    tryShutdown();
                }

                if (!isContinue) {
                    continueTasks(fromMain);
                }
            };
            if (task.workerThread) {
                executor.submit(r);
            } else {
                if (fromMain) {
                    r.run();
                } else {
                    pendingMain.add(r);
                }
            }
        } else {
            pendingTasks.add(task);
        }
    }

    private static void continueTasks(boolean fromMain) {
        for (Iterator<BootTask> it = pendingTasks.iterator(); it.hasNext();) {
            BootTask task = it.next();
            if (checkDependencies(task.dependencies)) {
                tryRunTask(task, fromMain, true);
                it.remove();
            }
        }
    }

    private static boolean checkDependencies(List<Class<?>> clzs) {
        if (clzs.isEmpty()) {
            return true;
        }

        for (int i = 0, s = tasks.size(); i < s; i++) {
            if (clzs.contains(tasks.get(i).getClass())) {
                if (!completed.get(i)) {
                    return false;
                }
            }
        }
        return true;
    }
}
