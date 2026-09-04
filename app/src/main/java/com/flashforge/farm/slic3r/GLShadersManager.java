package com.flashforge.farm.slic3r;

import android.opengl.GLES30;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.annotation.StringDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GLShadersManager {
    public final static String
            SHADER_BACKGROUND = "background",
            SHADER_DASHED_LINES = "dashed_lines",
            SHADER_FLAT = "flat",
            SHADER_FLAT_CLIP = "flat_clip",
            SHADER_FLAT_TEXTURE = "flat_texture",
            SHADER_GOURAUD = "gouraud",
            SHADER_GOURAUD_LIGHT = "gouraud_light",
            SHADER_GOURAUD_LIGHT_INSTANCED = "gouraud_light_instanced",
            SHADER_IMGUI = "imgui",
            SHADER_MM_CONTOUR = "mm_contour",
            SHADER_MM_GOURAUD = "mm_gouraud",
            SHADER_PRINTBED = "printbed",
            SHADER_TOOLPATHS_COG = "toolpaths_cog",
            SHADER_VARIABLE_LAYER_HEIGHT = "variable_layer_height",
            SHADER_WIREFRAME = "wireframe",
            SHADER_FARM_INTRO = "farm_intro";

    @StringDef(value = {
            SHADER_BACKGROUND,
            SHADER_DASHED_LINES,
            SHADER_FLAT,
            SHADER_FLAT_CLIP,
            SHADER_FLAT_TEXTURE,
            SHADER_GOURAUD,
            SHADER_GOURAUD_LIGHT,
            SHADER_GOURAUD_LIGHT_INSTANCED,
            SHADER_IMGUI,
            SHADER_MM_CONTOUR,
            SHADER_MM_GOURAUD,
            SHADER_GOURAUD,
            SHADER_PRINTBED,
            SHADER_TOOLPATHS_COG,
            SHADER_VARIABLE_LAYER_HEIGHT,
            SHADER_WIREFRAME,
            SHADER_FARM_INTRO
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ShaderType {}

    private final static List<GLShadersManager> managers = new ArrayList<>();

    private final Map<String, GLShaderProgram> shaders = new HashMap<String, GLShaderProgram>() {
        @Override
        public GLShaderProgram get(@Nullable Object key) {
            GLShaderProgram shader = super.get(key);
            if (shader == null) put((String) key, shader = new GLShaderProgram((String) key));
            return shader;
        }
    };

    public GLShadersManager() {
        managers.add(this);
    }

    public void clearShaders() {
        for (GLShaderProgram program : shaders.values()) {
            program.release();
        }
        shaders.clear();
        managers.remove(this);
    }

    public GLShaderProgram get(@ShaderType String key) {
        return shaders.get(key);
    }

    @Keep
    private static long getCurrentShaderPointer() {
        GLShaderProgram prog = null;
        for (GLShadersManager manager : managers) {
            prog = manager.getCurrentShader();
            if (prog != null) {
                break;
            }
        }
        return prog != null ? prog.pointer : 0;
    }

    public GLShaderProgram getCurrentShader() {
        int[] idRef = {0};
        GLES30.glGetIntegerv(GLES30.GL_CURRENT_PROGRAM, idRef, 0);
        int id = idRef[0];
        if (id != 0) {
            for (GLShaderProgram program : shaders.values()) {
                if (program.getId() == id) {
                    return program;
                }
            }
        }
        return null;
    }
}
