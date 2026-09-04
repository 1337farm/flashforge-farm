package com.flashforge.farm.recycler;

import android.content.Context;
import android.widget.Space;

public class SpaceItem extends SimpleRecyclerItem<Space> {
    private int x, y;

    public SpaceItem(int x, int y) {
        this.x = x;
        this.y = y;
    }

    @Override
    public Space onCreateView(Context ctx) {
        return new Space(ctx);
    }

    @Override
    public void onBindView(Space view) {
        view.setMinimumWidth(x);
        view.setMinimumHeight(y);
    }
}
