package com.flashforge.farm.recycler;

import android.content.Context;
import android.view.View;

public abstract class SimpleRecyclerItem<V extends View> {
    public abstract V onCreateView(Context ctx);
    public void onBindView(V view) {}
}
