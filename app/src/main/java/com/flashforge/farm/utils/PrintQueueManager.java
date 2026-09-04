package com.flashforge.farm.utils;

import android.content.SharedPreferences;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.ArrayList;
import java.util.List;

public class PrintQueueManager {
    private static final String PREF_KEY = "print_queue";
    private static final Gson gson = new Gson();

    public static class QueueItem {
        public String id;
        public String gcodePath;
        public String requiredNozzleSize;
        public String requiredFilamentType;
        public String requiredFilamentColor;
        public String status; // Pending, Printing, Done
        public int priority; // 0 = Low, 1 = Normal, 2 = High

        public QueueItem(String id, String gcodePath, String requiredNozzleSize, String requiredFilamentType, String requiredFilamentColor) {
            this.id = id;
            this.gcodePath = gcodePath;
            this.requiredNozzleSize = requiredNozzleSize;
            this.requiredFilamentType = requiredFilamentType;
            this.requiredFilamentColor = requiredFilamentColor;
            this.status = "Pending";
            this.priority = 1; // Default to Normal
        }
    }

    public static List<QueueItem> getQueue() {
        SharedPreferences prefs = Prefs.getPrefs();
        String json = prefs.getString(PREF_KEY, "[]");
        List<QueueItem> list = gson.fromJson(json, new TypeToken<List<QueueItem>>(){}.getType());
        if (list != null) {
            java.util.Collections.sort(list, (a, b) -> Integer.compare(b.priority, a.priority));
        }
        return list == null ? new java.util.ArrayList<>() : list;
    }

    public static void saveQueue(List<QueueItem> queue) {
        SharedPreferences.Editor editor = Prefs.getPrefs().edit();
        editor.putString(PREF_KEY, gson.toJson(queue));
        editor.apply();
    }

    public static void enqueueJob(QueueItem item) {
        List<QueueItem> queue = getQueue();
        queue.add(item);
        saveQueue(queue);
    }

    public static void updateJobStatus(String id, String status) {
        List<QueueItem> queue = getQueue();
        for (QueueItem item : queue) {
            if (item.id.equals(id)) {
                item.status = status;
                break;
            }
        }
        saveQueue(queue);
    }
}
