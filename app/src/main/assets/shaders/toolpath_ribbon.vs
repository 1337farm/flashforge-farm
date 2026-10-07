uniform mat4 view_model_matrix;
uniform mat4 projection_matrix;

attribute vec3 v_position;
attribute vec2 v_tex_coord;

varying float v_across;

void main()
{
	v_across = v_tex_coord.x;
    gl_Position = projection_matrix * view_model_matrix * vec4(v_position, 1.0);
}
