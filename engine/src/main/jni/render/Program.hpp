#ifndef ORCA_SLICER3_RENDER_PROGRAM_HPP
#define ORCA_SLICER3_RENDER_PROGRAM_HPP

namespace Slic3r {

class GLShaderProgram;

// Renders with the currently bound shader program. Implemented by the consuming
// application layer so the engine's rendering utility stays free of any
// application-level shader state.
GLShaderProgram* get_current_shader();

} // namespace Slic3r

#endif // ORCA_SLICER3_RENDER_PROGRAM_HPP
