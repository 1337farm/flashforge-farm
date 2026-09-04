package com.flashforge.farm.events;

import com.flashforge.farm.utils.Vec3d;

public class MeasurePointsChangedEvent {
    public final Vec3d pointA;
    public final Vec3d pointB;

    public MeasurePointsChangedEvent(Vec3d pointA, Vec3d pointB) {
        this.pointA = pointA;
        this.pointB = pointB;
    }
}
