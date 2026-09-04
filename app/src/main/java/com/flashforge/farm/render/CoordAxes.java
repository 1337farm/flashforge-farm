package com.flashforge.farm.render;

import com.flashforge.farm.R;
import com.flashforge.farm.slic3r.GLModel;
import com.flashforge.farm.slic3r.GLShaderProgram;
import com.flashforge.farm.slic3r.GLShadersManager;
import com.flashforge.farm.slic3r.Slic3rUtils;
import com.flashforge.farm.theme.ThemesRepo;
import com.flashforge.farm.utils.DoubleMatrix;
import com.flashforge.farm.utils.Vec3d;

public class CoordAxes {
    public Vec3d origin = new Vec3d(0, 0, 0);

    private float stemRadius = 0.5f;
    private float stemLength = 25.0f;
    private float tipRadius = 2.5f * stemRadius;
    private float tipLength = 5.0f;

    private GLModel arrow = new GLModel();

    public void setStemLength(float stemLength) {
        this.stemLength = stemLength;
        arrow.reset();
    }

    private double[] matrix = new double[16];
    private double[] matrix2 = new double[16];
    private double[] matrix3 = new double[16];

    private double[] normals = new double[12];

    private void renderAxis(GLShaderProgram shader, double[] viewMatrix, double[] projectionMatrix) {
        DoubleMatrix.multiplyMM(matrix3, 0, viewMatrix, 0, matrix2, 0);
        shader.setUniformMatrix4fv("view_model_matrix", matrix3);
        shader.setUniformMatrix4fv("projection_matrix", projectionMatrix);
        Slic3rUtils.calcViewNormalMatrix(viewMatrix, matrix2, normals);
        shader.setUniformMatrix3fv("view_normal_matrix", normals);
        arrow.render();
    }

    public void render(GLShadersManager shadersManager, double[] viewMatrix, double[] projectionMatrix, float emissionFactor, float invZoom) {
        if (!arrow.isInitialized()) {
            arrow.stilizedArrow(tipRadius, tipLength, stemRadius, stemLength);
        }

        GLShaderProgram currentShader = shadersManager.getCurrentShader();
        GLShaderProgram shader = shadersManager.get(GLShadersManager.SHADER_GOURAUD_LIGHT);
        if (currentShader != null) {
            currentShader.stopUsing();
        }

        shader.startUsing();
        shader.setUniform("emission_factor", emissionFactor);

        DoubleMatrix.setIdentityM(matrix, 0);
        float scale = Math.min(1, invZoom * 2f);
        DoubleMatrix.scaleM(matrix, 0, scale, scale, scale);

        arrow.setColor(ThemesRepo.getColor(R.attr.xTrackColor));
        DoubleMatrix.setIdentityM(matrix2, 0);
        DoubleMatrix.translateM(matrix2, 0, origin.x, origin.y, origin.z);
        DoubleMatrix.rotateM(matrix2, 0, 90f, 0, 1, 0);
        DoubleMatrix.multiplyMM(matrix2, 0, matrix, 0, matrix2, 0);
        renderAxis(shader, viewMatrix, projectionMatrix);

        arrow.setColor(ThemesRepo.getColor(R.attr.yTrackColor));
        DoubleMatrix.setIdentityM(matrix2, 0);
        DoubleMatrix.translateM(matrix2, 0, origin.x, origin.y, origin.z);
        DoubleMatrix.rotateM(matrix2, 0, -90f, 1, 0, 0);
        DoubleMatrix.multiplyMM(matrix2, 0, matrix, 0, matrix2, 0);
        renderAxis(shader, viewMatrix, projectionMatrix);

        arrow.setColor(ThemesRepo.getColor(R.attr.zTrackColor));
        DoubleMatrix.setIdentityM(matrix2, 0);
        DoubleMatrix.translateM(matrix2, 0, origin.x, origin.y, origin.z);
        DoubleMatrix.multiplyMM(matrix2, 0, matrix, 0, matrix2, 0);
        renderAxis(shader, viewMatrix, projectionMatrix);

        shader.stopUsing();
        if (currentShader != null) {
            currentShader.startUsing();
        }
    }

    public void release() {
        arrow.release();
    }
}
