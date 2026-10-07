precision highp float;

uniform vec4 uniform_color;

varying float v_across;

void main()
{
    // Rounded-filament profile across the ribbon: bright crown fading to
    // soft edges. Kills the harsh 1px-line shimmer and reads as a lit
    // cylinder even though the ribbon is flat.
    float d = abs(v_across * 2.0 - 1.0);
    float profile = sqrt(max(0.0, 1.0 - d * d));
    float shade = 0.62 + 0.38 * profile;
    gl_FragColor = vec4(uniform_color.rgb * shade, uniform_color.a);
}
