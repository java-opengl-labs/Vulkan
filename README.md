# Vulkan Kotlin examples and demos

A comprehensive collection of open source Kotlin examples for [Vulkan®](https://www.khronos.org/vulkan/), the new graphics and compute API from Khronos, based on the excellent examples of [Sascha Willems](https://github.com/SaschaWillems/Vulkan)

The expressiveness of Kotlin meets the power of Vulkan, thanks to [VK²](https://github.com/kotlin-graphics/vkk).

## Examples

### Basics

#### [01a - Triangle Verbose](src/main/kotlin/vulkan/basics/01a%20Triangle%20Verbose.kt)
Basic and verbose example for getting a colored triangle rendered to the screen using Vulkan. This is meant as a starting point for learning Vulkan from the ground up. 
A huge part of the code is boilerplate that is abstracted away in later examples. Also, there is no type safety through inline
classes.

#### [01b - Triangle](src/main/kotlin/vulkan/basics/01b%20Triangle.kt)
Boilerplate code gone, 1/3 less code, strictly type safety for free thanks to inline classes.

#### [02 - Pipelines](src/main/kotlin/vulkan/basics/02%20Pipelines.kt)

Using pipeline state objects (pso) that bake state information (rasterization states, culling modes, etc.) along with the shaders into a single object, making it easy for an implementation to optimize usage (compared to OpenGL's dynamic state machine). Also demonstrates the use of pipeline derivatives.

#### [03 - Descriptor sets](src/main/kotlin/vulkan/basics/03%20Descriptor%20Sets.kt)

Descriptors are used to pass data to shader binding points. Sets up descriptor sets, layouts, pools, creates a single pipeline based on the set layout and renders multiple objects with different descriptor sets.

#### [04 - Dynamic uniform buffers](src/main/kotlin/vulkan/basics/04%20Dynamic%20Uniform%20Buffers.kt)

Dynamic uniform buffers are used for rendering multiple objects with multiple matrices stored in a single uniform buffer object. Individual matrices are dynamically addressed upon descriptor binding time, minimizing the number of required descriptor sets.

#### [05 - Push constants](src/main/kotlin/vulkan/basics/05%20Push%20Constants.kt)

Uses push constants, small blocks of uniform data stored within a command buffer, to pass data to a shader without the need for uniform buffers.

#### [06 - Specialization constants](src/main/kotlin/vulkan/basics/06%20Specialization%20Constants.kt)

Uses SPIR-V specialization constants to create multiple pipelines with different lighting paths from a single "uber" shader.

#### [07 - Texture mapping](src/main/kotlin/vulkan/basics/07%20Texture.kt)

Loads a 2D texture from disk (including all mip levels), uses staging to upload it into video memory and samples from it using combined image samplers.

#### [08 - Cubemap textures](src/main/kotlin/vulkan/basics/08%20Texture%20Cubemap.kt) Only sphere model

Loads a cube map texture from disk containing six different faces. All faces and mip levels are uploaded into video memory and the cubemap is sampled once as a skybox (for the background) and as a source for reflections (for a 3D model).

#### [09 - Texture arrays](src/main/kotlin/vulkan/basics/09%20Texture%20Array.kt)

Loads a 2D texture array containing multiple 2D texture slices (each with it's own mip chain) and renders multiple meshes each sampling from a different layer of the texture. 2D texture arrays don't do any interpolation between the slices.

#### [10 - 3D textures](src/main/kotlin/vulkan/basics/10%20Texture%203d.kt)

Generates a 3D texture on the cpu (using perlin noise), uploads it to the device and samples it to render an animation. 3D textures store volumetric data and interpolate in all three dimensions.

#### [11 - Model rendering](src/main/kotlin/vulkan/basics/11%20Model%20Rendering.kt)

Loads a 3D model and texture maps from a common file format (using [assimp](https://github.com/assimp/assimp)), uploads the vertex and index buffer data to video memory, sets up a matching vertex layout and renders the 3D model.

#### [12 - Sub passes](examples/subpasses/) TOFINISH

Uses sub passes and input attachments to write and read back data from framebuffer attachments (same location only) in single render pass. This is used to implement deferred render composition with added forward transparency in a single pass. 

#### [13 - Offscreen rendering](src/main/kotlin/vulkan/basics/13%20Offscreen%20Rendering.kt) TODO

Basic offscreen rendering in two passes. First pass renders the mirrored scene to a separate framebuffer with color and depth attachments, second pass samples from that color attachment for rendering a mirror surface.

#### [14 - CPU particle system](examples/particlefire/) TOFINISH

Implements a simple CPU based particle system. Particle data is stored in host memory, updated on the CPU per-frame and synchronized with the device before it's rendered using pre-multiplied alpha.

#### [15 - Stencil buffer](examples/stencilbuffer/) TODO

Uses the stencil buffer and it's compare functionality for rendering a 3D model with dynamic outlines.


### Advanced TODO

### Performance 

#### [01 - Multi threaded command buffer generation](src/main/kotlin/vulkan/performance/01a%20Multithreading.kt)

Multi threaded parallel command buffer generation. Instead of prebuilding and reusing the same command buffers this sample uses multiple hardware threads to demonstrate parallel per-frame recreation of secondary command buffers that are executed and submitted in a primary buffer once all threads have finished.

### Deferred TODO

### Compute shader

#### [01 - Image processing](src/main/kotlin/vulkan/computeShader/01%20Image%20Processing.kt)

Uses a compute shader along with a separate compute queue to apply different convolution kernels (and effects) on an input image in realtime.

#### [02 - GPU particle system](examples/computeparticles/) TOFIX

Attraction based 2D GPU particle system using compute shaders. Particle data is stored in a shader storage buffer and only modified on the GPU using memory barriers for synchronizing compute particle updates with graphics pipeline vertex access.

#### [03 - N-body simulation](examples/computenbody/) TOFIX

N-body simulation based particle system with multiple attractors and particle-to-particle interaction using two passes separating particle movement calculation and final integration. Shared compute shader memory is used to speed up compute calculations.

#### [04 - Ray tracing](examples/raytracing/) TODO

Simple GPU ray tracer with shadows and reflections using a compute shader. No scene geometry is rendered in the graphics pass.

#### [05 - Cloth simulation](examples/computecloth/) TODO

Mass-spring based cloth system on the GPU using a compute shader to calculate and integrate spring forces, also implementing basic collision with a fixed scene object.

#### [06 - Cull and LOD](examples/computecullandlod/) TODO

Purely GPU based frustum visibility culling and level-of-detail system. A compute shader is used to modify draw commands stored in an indirect draw commands buffer to toggle model visibility and select it's level-of-detail based on camera distance, no calculations have to be done on and synced with the CPU.



#### Credits:

- [Sascha](https://github.com/SaschaWillems), awesome repo (except for avoiding using glfw)
- [Spasi](https://github.com/Spasi), for assisting (and tolerating) my continuous boring questions :), also the tip about the buffer came from him 
- [Kai](https://github.com/httpdigest), for his first Hello Triangle in java
- [Ulrik](https://github.com/skalarproduktraum), for some tips and the glsl -> spir-v converter
