package com.flashforge.farm.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.Arrays;

import com.flashforge.farm.R;
import com.flashforge.farm.slic3r.GCodeViewer;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.ViewUtils;

public class SegmentsView extends View {
    private Path path = new Path();
    private Paint paint = new Paint();
    private float showProgress;
    private boolean isVisible;
    private float[] currentValues;
    private SpringAnimation currentChangeAnimation;

    public SegmentsView(Context context) {
        super(context);
    }

    public void startAnimation() {
        isVisible = true;
        new SpringAnimation(new FloatValueHolder(0))
                .setMinimumVisibleChange(1 / 256f)
                .setSpring(new SpringForce(1f)
                        .setStiffness(1000f)
                        .setDampingRatio(1f))
                .addUpdateListener((animation, value, velocity) -> {
                    showProgress = value;
                    invalidate();
                })
                .start();
    }

    public void setNotVisible() {
        isVisible = false;
    }

    public void setValues(float[] values) {
        if (!isVisible || currentValues == null || currentValues.length != values.length) {
            currentValues = values;
            invalidate();
        } else {
            float[] prevValues = Arrays.copyOf(currentValues, currentValues.length);
            currentChangeAnimation = new SpringAnimation(new FloatValueHolder(0))
                    .setMinimumVisibleChange(1 / 256f)
                    .setSpring(new SpringForce(1f)
                            .setStiffness(1000f)
                            .setDampingRatio(1f))
                    .addUpdateListener((animation, value, velocity) -> {
                        for (int i = 0; i < currentValues.length; i++) {
                            currentValues[i] = ViewUtils.lerp(prevValues[i], values[i], value);
                        }
                        invalidate();
                    })
                    .addEndListener((animation, canceled, value, velocity) -> {
                        if (animation == currentChangeAnimation) {
                            currentChangeAnimation = null;
                        }
                    });
            currentChangeAnimation.start();
        }
    }

    public static int mapColor(@GCodeViewer.ExtrusionRole int index) {
        switch (index) {
            default:
            case GCodeViewer.EXTRUSION_ROLE_NONE:
                return ThemesRepo.getColor(R.attr.gcodeViewerNone);
            case GCodeViewer.EXTRUSION_ROLE_PERIMETER:
                return ThemesRepo.getColor(R.attr.gcodeViewerPerimeter);
            case GCodeViewer.EXTRUSION_ROLE_EXTERNAL_PERIMETER:
                return ThemesRepo.getColor(R.attr.gcodeViewerExternalPerimeter);
            case GCodeViewer.EXTRUSION_ROLE_OVERHANG_PERIMETER:
                return ThemesRepo.getColor(R.attr.gcodeViewerOverhangPerimeter);
            case GCodeViewer.EXTRUSION_ROLE_INTERNAL_INFILL:
                return ThemesRepo.getColor(R.attr.gcodeViewerInternalInfill);
            case GCodeViewer.EXTRUSION_ROLE_SOLID_INFILL:
                return ThemesRepo.getColor(R.attr.gcodeViewerSolidInfill);
            case GCodeViewer.EXTRUSION_ROLE_TOP_SOLID_INFILL:
                return ThemesRepo.getColor(R.attr.gcodeViewerTopSolidInfill);
            case GCodeViewer.EXTRUSION_ROLE_IRONING:
                return ThemesRepo.getColor(R.attr.gcodeViewerIroning);
            case GCodeViewer.EXTRUSION_ROLE_BRIDGE_INFILL:
                return ThemesRepo.getColor(R.attr.gcodeViewerBridgeInfill);
            case GCodeViewer.EXTRUSION_ROLE_GAP_FILL:
                return ThemesRepo.getColor(R.attr.gcodeViewerGapFill);
            case GCodeViewer.EXTRUSION_ROLE_SKIRT:
                return ThemesRepo.getColor(R.attr.gcodeViewerSkirt);
            case GCodeViewer.EXTRUSION_ROLE_SUPPORT_MATERIAL:
                return ThemesRepo.getColor(R.attr.gcodeViewerSupportMaterial);
            case GCodeViewer.EXTRUSION_ROLE_SUPPORT_MATERIAL_INTERFACE:
                return ThemesRepo.getColor(R.attr.gcodeViewerSupportMaterialInterface);
            case GCodeViewer.EXTRUSION_ROLE_WIPE_TOWER:
                return ThemesRepo.getColor(R.attr.gcodeViewerWipeTower);
            case GCodeViewer.EXTRUSION_ROLE_CUSTOM:
                return ThemesRepo.getColor(R.attr.gcodeViewerCustom);
        }
    }

    protected int onGetColor(int i) {
        return mapColor(i);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);

        canvas.save();
        path.rewind();
        float cX = getWidth() / 2f;
        float l = 0, r = ViewUtils.lerp(0, getWidth(), showProgress);
        path.addRoundRect(l, 0, r, getHeight(), ViewUtils.dp(12), ViewUtils.dp(12), Path.Direction.CW);
        canvas.clipPath(path);
        if (currentValues != null) {
            float dw = r - l;
            for (int i = 1; i < currentValues.length; i++) {
                float prev = currentValues[i - 1];
                float to = currentValues[i];
                paint.setColor(onGetColor(i - 1));
                canvas.drawRect(l + prev * dw, 0, l + to * dw, getHeight(), paint);
            }
        }
        canvas.restore();
    }
}
