package com.flashforge.farm.events;

import com.flashforge.farm.utils.Vec3d;

public class EmbossSurfaceClickedEvent {
    public final Vec3d position;
    public final Vec3d normal;

    public EmbossSurfaceClickedEvent(Vec3d position, Vec3d normal) {
        this.position = position;
        this.normal = normal;
    }
}
