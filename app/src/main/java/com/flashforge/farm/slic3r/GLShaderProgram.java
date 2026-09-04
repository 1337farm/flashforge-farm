package com.flashforge.farm.slic3r;

import static com.flashforge.farm.utils.DebugUtils.assertTrue;

import android.content.res.AssetManager;
import android.graphics.Color;
import android.opengl.GLES30;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import com.flashforge.farm.FarmApp;
import com.flashforge.farm.utils.IOUtils;

public class GLShaderProgram {
    long pointer;
    private static ThreadLocal<FloatBuffer> matrixBuffer = new ThreadLocal<FloatBuffer>() {
        @Nullable
        @Override
        protected FloatBuffer initialValue() {
            return ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        }
    };
    private static ThreadLocal<float[]> float16Buffer = new ThreadLocal<float[]>() {
        @Override
        protected float[] initialValue() {
            return new float[16];
        }
    };
    private static ThreadLocal<float[]> float12Buffer = new ThreadLocal<float[]>() {
        @Override
        protected float[] initialValue() {
            return new float[12];
        }
    };

    public GLShaderProgram(String name) {
        AssetManager assets = FarmApp.INSTANCE.getAssets();
        try {
            pointer = Native.shader_init_from_texts(name, IOUtils.readString(assets.open("shaders/" + name + ".fs")), IOUtils.readString(assets.open("shaders/" + name + ".vs")));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void startUsing() {
        Native.shader_start_using(pointer);
    }

    public void stopUsing() {
        Native.shader_stop_using(pointer);
    }

    public int getUniformLocation(String name) {
        // This function uses native uniform cache. Java one does not
        return Native.shader_get_uniform_location(pointer, name);
    }

    public int getAttribLocation(String name) {
        // Same as getUniformLocation
        return Native.shader_get_attrib_location(pointer, name);
    }

    public void setUniform(String name, boolean value) {
        GLES30.glUniform1i(getUniformLocation(name), value ? 1 : 0);
    }

    public void setUniform(String name, float value) {
        GLES30.glUniform1f(getUniformLocation(name), value);
    }

    public void setUniformMatrix3fv(String name, double[] value) {
        assertTrue(value.length == 12);

        float[] floats = float12Buffer.get();
        for (int i = 0; i < value.length; i++) {
            floats[i] = (float) value[i];
        }
        setUniformMatrix3fv(name, floats);
    }

    public void setUniformMatrix3fv(String name, float[] value) {
        assertTrue(value.length == 12);

        FloatBuffer buf = matrixBuffer.get();
        buf.position(0).limit(12);
        buf.put(value);
        buf.flip();
        GLES30.glUniformMatrix3fv(getUniformLocation(name), 1, false, buf);
    }

    public void setUniformMatrix4fv(String name, double[] value) {
        assertTrue(value.length == 16);

        float[] floats = float16Buffer.get();
        for (int i = 0; i < value.length; i++) {
            floats[i] = (float) value[i];
        }
        setUniformMatrix4fv(name, floats);
    }

    public void setUniformMatrix4fv(String name, float[] value) {
        assertTrue(value.length == 16);

        FloatBuffer buf = matrixBuffer.get();
        buf.position(0).limit(16);
        buf.put(value);
        buf.flip();
        GLES30.glUniformMatrix4fv(getUniformLocation(name), 1, false, buf);
    }

    public void setUniformColor(String name, int color) {
        setUniform4f(name, (float) Color.red(color) / 0xFF, (float) Color.green(color) / 0xFF, (float) Color.blue(color) / 0xFF, (float) Color.alpha(color) / 0xFF);
    }

    public void setUniform4f(String name, float... value) {
        assertTrue(value.length == 4);

        GLES30.glUniform4f(getUniformLocation(name), value[0], value[1], value[2], value[3]);
    }

    public void setUniform3f(String name, float... value) {
        assertTrue(value.length == 3);

        GLES30.glUniform3f(getUniformLocation(name), value[0], value[1], value[2]);
    }

    public void setUniform2f(String name, float... value) {
        assertTrue(value.length == 2);

        GLES30.glUniform2f(getUniformLocation(name), value[0], value[1]);
    }

    public int getId() {
        return Native.shader_get_id(pointer);
    }

    public void release() {
        if (pointer != 0) {
            Native.shader_release(pointer);
            pointer = 0;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        release();
    }
}
