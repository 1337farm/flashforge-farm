package com.flashforge.farm.slic3r;

public interface SliceListener {
    void onProgress(int progress, String text);
}
