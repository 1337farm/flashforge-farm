package com.flashforge.farm;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.flashforge.farm.events.EmbossSurfaceClickedEvent;
import com.flashforge.farm.events.FlattenModeResetEvent;
import com.flashforge.farm.events.LongClickTranslationEvent;
import com.flashforge.farm.events.MeasurePointsChangedEvent;
import com.flashforge.farm.events.NeedDismissCalibrationsMenu;
import com.flashforge.farm.events.NeedDismissSnackbarEvent;
import com.flashforge.farm.events.NeedSnackbarEvent;
import com.flashforge.farm.events.ObjectsListChangedEvent;
import com.flashforge.farm.events.SelectedObjectChangedEvent;
import com.flashforge.farm.events.SlicingProgressEvent;

import java.util.ArrayList;
import java.util.List;

public final class Bus {
    public interface Listener<T> {
        void onChanged(T value);
    }

    public static final class Emitter<T> {
        private final List<Listener<T>> listeners = new ArrayList<>();
        private final Handler mainHandler = new Handler(Looper.getMainLooper());

        public void postValue(T value) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                dispatch(value);
            } else {
                mainHandler.post(() -> dispatch(value));
            }
        }

        public void observeForever(Listener<T> listener) {
            listeners.add(listener);
        }

        public void removeObserver(Listener<T> listener) {
            listeners.remove(listener);
        }

        private void dispatch(T value) {
            for (Listener<T> listener : new ArrayList<>(listeners)) {
                if (listener == null) continue;
                try {
                    listener.onChanged(value);
                } catch (Exception e) {
                    Log.e("Bus", "Exception in listener", e);
                }
            }
        }
    }

    public static final Emitter<NeedSnackbarEvent> NEED_SNACKBAR = new Emitter<>();
    public static final Emitter<NeedDismissSnackbarEvent> DISMISS_SNACKBAR = new Emitter<>();
    public static final Emitter<ObjectsListChangedEvent> OBJECTS_LIST_CHANGED = new Emitter<>();
    public static final Emitter<SelectedObjectChangedEvent> SELECTED_OBJECT_CHANGED = new Emitter<>();
    public static final Emitter<SlicingProgressEvent> SLICING_PROGRESS = new Emitter<>();
    public static final Emitter<FlattenModeResetEvent> FLATTEN_MODE_RESET = new Emitter<>();
    public static final Emitter<LongClickTranslationEvent> LONG_CLICK_TRANSLATION = new Emitter<>();
    public static final Emitter<EmbossSurfaceClickedEvent> EMBOSS_SURFACE_CLICKED = new Emitter<>();
    public static final Emitter<MeasurePointsChangedEvent> MEASURE_POINTS_CHANGED = new Emitter<>();
    public static final Emitter<NeedDismissCalibrationsMenu> DISMISS_CALIBRATIONS_MENU = new Emitter<>();

    private Bus() {
    }
}