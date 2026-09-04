package com.flashforge.farm.components.bed_menu;

import android.content.Context;
import android.widget.Toast;

import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import java.util.Arrays;
import java.util.List;

import com.flashforge.farm.R;
import com.flashforge.farm.components.FarmAlertDialogBuilder;
import com.flashforge.farm.recycler.SimpleRecyclerItem;
import com.flashforge.farm.recycler.SpaceItem;
import com.flashforge.farm.render.Camera;
import com.flashforge.farm.slic3r.Bed3D;
import com.flashforge.farm.utils.Prefs;
import com.flashforge.farm.utils.Vec3d;
import com.flashforge.farm.utils.ViewUtils;
import com.flashforge.farm.view.GLView;

public class CameraMenu extends ListBedMenu {
    private boolean checkInvalidBed() {
        if (!fragment.getGlView().getRenderer().getBed().isValid()) {
            Toast.makeText(fragment.getContext(), R.string.BedConfigurationError, Toast.LENGTH_SHORT).show();
            return true;
        }
        return false;
    }

    @Override
    protected List<SimpleRecyclerItem> onCreateItems(boolean portrait) {
        return Arrays.asList(
                new BedMenuItem(R.string.MenuCameraIsometric, R.drawable.camera_mode_0_28).onClick(v -> {
                    if (checkInvalidBed()) return;
                    GLView glView = fragment.getGlView();
                    Bed3D bed = glView.getRenderer().getBed();

                    Vec3d min = bed.getVolumeMin(), max = bed.getVolumeMax();
                    Vec3d center = min.center(max);
                    Vec3d toOrigin = new Vec3d(center).multiply(1, 1, 0);
                    Vec3d toPosition = new Vec3d(center.x - center.z * 2, center.y - center.z * 2, min.z + Math.sqrt(center.z * center.z * 8));
                    animateTo(toOrigin, toPosition);
                }),
                new SpaceItem(portrait ? ViewUtils.dp(8) : 0, portrait ? 0 : ViewUtils.dp(8)),
                new BedMenuItem(R.string.MenuCameraTop, R.drawable.camera_mode_1_28).onClick(v -> {
                    if (checkInvalidBed()) return;
                    GLView glView = fragment.getGlView();
                    Bed3D bed = glView.getRenderer().getBed();

                    Vec3d min = bed.getVolumeMin(), max = bed.getVolumeMax();
                    Vec3d center = min.center(max);
                    Vec3d toOrigin = new Vec3d(center).multiply(1, 1, 0);
                    Vec3d toPosition = new Vec3d(center);
                    toPosition.z = max.z + (max.z - min.z);
                    toPosition.y -= 1f;
                    animateTo(toOrigin, toPosition);
                }),
                new BedMenuItem(R.string.MenuCameraBottom, R.drawable.camera_mode_2_28).onClick(v -> {
                    if (checkInvalidBed()) return;
                    GLView glView = fragment.getGlView();
                    Bed3D bed = glView.getRenderer().getBed();

                    Vec3d min = bed.getVolumeMin(), max = bed.getVolumeMax();
                    Vec3d center = min.center(max);
                    Vec3d toOrigin = new Vec3d(center).multiply(1, 1, 0);
                    Vec3d toPosition = new Vec3d(center);
                    toPosition.z = min.z - (max.z - min.z);
                    toPosition.y -= 1f;
                    animateTo(toOrigin, toPosition);
                }),
                new BedMenuItem(R.string.MenuCameraFront, R.drawable.camera_mode_3_28).onClick(v -> {
                    if (checkInvalidBed()) return;
                    GLView glView = fragment.getGlView();
                    Bed3D bed = glView.getRenderer().getBed();

                    Vec3d min = bed.getVolumeMin(), max = bed.getVolumeMax();
                    Vec3d center = min.center(max);
                    Vec3d toOrigin = new Vec3d(center).multiply(1, 1, 0);
                    Vec3d toPosition = new Vec3d(center);
                    toPosition.y = min.y - (max.y - min.y);
                    toPosition.z = 0;
                    animateTo(toOrigin, toPosition);
                }),
                new BedMenuItem(R.string.MenuCameraBack, R.drawable.camera_mode_4_28).onClick(v -> {
                    if (checkInvalidBed()) return;
                    GLView glView = fragment.getGlView();
                    Bed3D bed = glView.getRenderer().getBed();

                    Vec3d min = bed.getVolumeMin(), max = bed.getVolumeMax();
                    Vec3d center = min.center(max);
                    Vec3d toOrigin = new Vec3d(center).multiply(1, 1, 0);
                    Vec3d toPosition = new Vec3d(center);
                    toPosition.y = max.y + (max.y - min.y);
                    toPosition.z = 0;
                    animateTo(toOrigin, toPosition);
                }),
                new BedMenuItem(R.string.MenuCameraLeft, R.drawable.camera_mode_5_28).onClick(v -> {
                    if (checkInvalidBed()) return;
                    GLView glView = fragment.getGlView();
                    Bed3D bed = glView.getRenderer().getBed();

                    Vec3d min = bed.getVolumeMin(), max = bed.getVolumeMax();
                    Vec3d center = min.center(max);
                    Vec3d toOrigin = new Vec3d(center).multiply(1, 1, 0);
                    Vec3d toPosition = new Vec3d(center);
                    toPosition.x = min.x - (max.x - min.x);
                    toPosition.z = 0;
                    animateTo(toOrigin, toPosition);
                }),
                new BedMenuItem(R.string.MenuCameraRight, R.drawable.camera_mode_6_28).onClick(v -> {
                    if (checkInvalidBed()) return;
                    GLView glView = fragment.getGlView();
                    Bed3D bed = glView.getRenderer().getBed();

                    Vec3d min = bed.getVolumeMin(), max = bed.getVolumeMax();
                    Vec3d center = min.center(max);
                    Vec3d toOrigin = new Vec3d(center).multiply(1, 1, 0);
                    Vec3d toPosition = new Vec3d(center);
                    toPosition.x = max.x + (max.x - min.x);
                    toPosition.z = 0;
                    animateTo(toOrigin, toPosition);
                }),
                new SpaceItem(portrait ? ViewUtils.dp(8) : 0, portrait ? 0 : ViewUtils.dp(8)),
                new BedMenuItem(R.string.MenuCameraControlMode, R.drawable.rectangle_hand_point_up_28).onClick(v -> {
                    Context ctx = v.getContext();
                    new FarmAlertDialogBuilder(v.getContext())
                            .setTitle(R.string.MenuCameraControlModeFull)
                            .setSingleChoiceItems(new CharSequence[] {
                                    ctx.getString(R.string.MenuCameraControlModeOne),
                                    ctx.getString(R.string.MenuCameraControlModeTwo),
                                    ctx.getString(R.string.MenuCameraControlModeThree)
                            }, Prefs.getCameraControlMode(), (dialog, which) -> {
                                Prefs.setCameraControlMode(which);
                                dialog.dismiss();
                            })
                            .show();
                }),
                new BedMenuItem(R.string.MenuCameraOrtho, R.drawable.image_format_32).setCheckable((buttonView, isChecked) -> {
                    Prefs.setOrthoProjectionEnabled(isChecked);
                    fragment.getGlView().getRenderer().updateProjection();
                    fragment.getGlView().requestRender();
                }, Prefs.isOrthoProjectionEnabled()));
    }

    private void animateTo(Vec3d toOrigin, Vec3d toPosition) {
        animateTo(toOrigin, null, toPosition);
    }

    private void animateTo(Vec3d toOrigin, Vec3d middlePoint, Vec3d toPosition) {
        GLView glView = fragment.getGlView();
        Camera camera = glView.getRenderer().getCamera();

        Vec3d fromOrigin = new Vec3d(camera.origin);
        Vec3d fromPosition = new Vec3d(camera.position);
        if (middlePoint == null) {
            middlePoint = fromPosition.center(toPosition);
        }

        float zoom = camera.getZoom();
        Vec3d finalMiddlePoint = middlePoint;
        new SpringAnimation(new FloatValueHolder(0))
                .setMinimumVisibleChange(1 / 1000f)
                .setSpring(new SpringForce(1f)
                        .setStiffness(1000f)
                        .setDampingRatio(1f))
                .addUpdateListener((animation, value, velocity) -> {
                    camera.setZoom(ViewUtils.lerp(zoom, 1f, value));
                    camera.position.set(
                            ViewUtils.lerpd(fromPosition.x, Math.abs(toPosition.x - toOrigin.x) <= 5 ? finalMiddlePoint.x : fromPosition.x + (toPosition.x - fromPosition.x) / 2, toPosition.x, value),
                            ViewUtils.lerpd(fromPosition.y, Math.abs(toPosition.y - toOrigin.y) <= 5 ? finalMiddlePoint.y : fromPosition.y + (toPosition.y - fromPosition.y) / 2, toPosition.y, value),
                            ViewUtils.lerpd(fromPosition.z, Math.abs(toPosition.z - toOrigin.z) <= 5 ? finalMiddlePoint.z : fromPosition.z + (toPosition.z - fromPosition.z) / 2, toPosition.z, value)
                    );
                    camera.origin.set(
                            ViewUtils.lerpd(fromOrigin.x, toOrigin.x, value),
                            ViewUtils.lerpd(fromOrigin.y, toOrigin.y, value),
                            ViewUtils.lerpd(fromOrigin.z, toOrigin.z, value)
                    );
                    glView.getRenderer().updateProjection();
                    glView.requestRender();
                })
                .start();
    }
}
