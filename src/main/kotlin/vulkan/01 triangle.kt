package vulkan

import glm_.*
import glm_.func.rad
import glm_.mat4x4.Mat4
import glm_.vec3.Vec3
import org.lwjgl.glfw.GLFW.glfwInit
import org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported
import org.lwjgl.system.MemoryUtil
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.system.MemoryUtil.memCopy
import org.lwjgl.system.Struct
import org.lwjgl.system.StructBuffer
import org.lwjgl.vulkan.*
import org.lwjgl.vulkan.VK10.*
import uno.buffer.floatBufferOf
import uno.buffer.intBufferOf
import uno.buffer.toBuffer
import uno.buffer.use
import uno.kotlin.buffers.capacity
import vkn.*
import vkn.ArrayList_Long.resize
import vkn.VkMemoryStack.Companion.withStack
import vulkan.TriangleDemo_.loadShader
import vulkan.base.VulkanExampleBase
import vulkan.base.initializers.commandBufferBeginInfo
import vulkan.base.tools.DEFAULT_FENCE_TIMEOUT
import java.io.File
import java.nio.Buffer


fun main(args: Array<String>) {

    if (!glfwInit()) throw RuntimeException("Failed to initialize GLFW")
    if (!glfwVulkanSupported()) throw AssertionError("GLFW failed to find the Vulkan loader")

    VulkanExample().apply {
        initVulkan()
        setupWindow()
        prepare()
        renderLoop()
        destroy()
    }
}

/** Set to "true" to enable Vulkan's validation layers (see vulkandebug.cpp for details)    */
val ENABLE_VALIDATION = true
/** Set to "true" to use staging buffers for uploading vertex and index data to device local memory
 *  See "prepareVertices" for details on what's staging and on why to use it    */
var USE_STAGING = true

class VulkanExample : VulkanExampleBase(ENABLE_VALIDATION) {

    init {
        zoom = -2.5f
        title = "Vulkan Example - Basic indexed triangle"
        // Values not set here are initialized in the base class constructor
    }

    /** Vertex layout used in this example  */
    object Vertex {
        //        float position [3];
//        float color [3];
        val size = Vec3.size * 2
        val offsetPosition = 0
        val offsetColor = Vec3.size
    }

    /** Vertex buffer and attributes    */
    private val vertices = object {
        /** Handle to the device memory for this buffer */
        var memory: VkDeviceMemory = NULL
        /** Handle to the Vulkan buffer object that the memory is bound to  */
        var buffer: VkBuffer = NULL
    }

    /** Index buffer    */
    private val indices = object {
        var memory: VkDeviceMemory = NULL
        var buffer: VkBuffer = NULL
        var count = 0
    }

    /** Uniform buffer block object */
    private val uniformBufferVS = object {
        var memory = NULL
        var buffer = NULL
        var descriptor = VkDescriptorBufferInfo.malloc(1)
    }

    /*
        For simplicity we use the same uniform block layout as in the shader:

        layout(set = 0, binding = 0) uniform UBO {
            mat4 projectionMatrix;
            mat4 modelMatrix;
            mat4 viewMatrix;
        } ubo;

        This way we can just memcopy the ubo data to the ubo
        Note: You should use data types that align with the GPU in order to avoid manual padding (vec4, mat4)   */
    private val uboVS = object {
        var projectionMatrix = Mat4()
        var modelMatrix = Mat4()
        var viewMatrix = Mat4()
        val size = Mat4.size * 3L
    }

    /** The pipeline layout is used by a pipline to access the descriptor sets
     *  It defines interface (without binding any actual data) between the shader stages used by the pipeline and the
     *  shader resources
     *  A pipeline layout can be shared among multiple pipelines as long as their interfaces match  */
    var pipelineLayout: VkPipelineLayout = NULL

    /** Pipelines (often called "pipeline state objects") are used to bake all states that affect a pipeline
     *  While in OpenGL every state can be changed at (almost) any time, Vulkan requires to layout the graphics
     *  (and compute) pipeline states upfront
     *  So for each combination of non-dynamic pipeline states you need a new pipeline (there are a few exceptions to
     *  this not discussed here)
     *  Even though this adds a new dimension of planing ahead, it's a great opportunity for performance optimizations
     *  by the driver   */
    var pipeline: VkPipeline = NULL

    /** The descriptor set layout describes the shader binding layout (without actually referencing descriptor)
     *  Like the pipeline layout it's pretty much a blueprint and can be used with different descriptor sets as long as
     *  their layout matches    */
    var descriptorSetLayout: VkDescriptorSetLayout = NULL

    /** The descriptor set stores the resources bound to the binding points in a shader
     *  It connects the binding points of the different shaders with the buffers and images used for those bindings */
    var descriptorSet: VkDescriptorSet = NULL


    /*  Synchronization primitives
        Synchronization is an important concept of Vulkan that OpenGL mostly hid away.
        Getting this right is crucial to using Vulkan.     */

    /** Semaphores
     *  Used to coordinate operations within the graphics queue and ensure correct command ordering */
    var presentCompleteSemaphore: VkSemaphore = NULL
    var renderCompleteSemaphore: VkSemaphore = NULL

    /** Fences
     *  Used to check the completion of queue operations (e.g. command buffer execution)    */
    val waitFences = ArrayList<VkFence>()

    override fun destroy() {
        super.destroy()

        /*  Clean up used Vulkan resources
            Note: Inherited destructor cleans up resources stored in base class         */
        vkDestroyPipeline(device, pipeline)

        vkDestroyPipelineLayout(device, pipelineLayout)
        vkDestroyDescriptorSetLayout(device, descriptorSetLayout)

        vkDestroyBuffer(device, vertices.buffer)
        vkFreeMemory(device, vertices.memory)

        vkDestroyBuffer(device, indices.buffer)
        vkFreeMemory(device, indices.memory)

        vkDestroyBuffer(device, uniformBufferVS.buffer)
        vkFreeMemory(device, uniformBufferVS.memory)

        vkDestroySemaphore(device, presentCompleteSemaphore)
        vkDestroySemaphore(device, renderCompleteSemaphore)

        vkDestroyFence(device, waitFences)
    }

    /** This function is used to request a device memory type that supports all the property flags we request
     *  (e.g. device local, host visibile)
     *  Upon success it will return the index of the memory type that fits our requestes memory properties
     *  This is necessary as implementations can offer an arbitrary number of memory types with different
     *  memory properties.
     *  You can check http://vulkan.gpuinfo.org/ for details on different memory configurations */
    fun getMemoryTypeIndex(typeBits: Int, properties: VkMemoryPropertyFlags): Int {
        var typeBits = typeBits
        // Iterate over all memory types available for the device used in this example
        for (i in 0 until deviceMemoryProperties.memoryTypeCount) {
            if ((typeBits and 1) == 1 && (deviceMemoryProperties.memoryTypes[i].propertyFlags and properties) == properties)
                return i
            typeBits = typeBits ushr 1
        }
        throw Error("Could not find a suitable memory type!")
    }

    /** Create the Vulkan synchronization primitives used in this example   */
    fun prepareSynchronizationPrimitives() = withStack {
        // Semaphores (Used for correct command ordering)
        val semaphoreCreateInfo = cVkSemaphoreCreateInfo {
            type = VkStructureType_SEMAPHORE_CREATE_INFO
            next = NULL
        }

        // Semaphore used to ensures that image presentation is complete before starting to submit again
        vkCreateSemaphore(device, semaphoreCreateInfo, null, ::presentCompleteSemaphore).check()

        // Semaphore used to ensures that all commands submitted have been finished before submitting the image to the queue
        vkCreateSemaphore(device, semaphoreCreateInfo, null, ::renderCompleteSemaphore).check()

        // Fences (Used to check draw command buffer completion)
        val fenceCreateInfo = cVkFenceCreateInfo {
            type = VkStructureType_FENCE_CREATE_INFO
            // Create in signaled state so we don't wait on first render of each command buffer
            flags = VkFenceCreateFlag_SIGNALED_BIT
        }
        waitFences resize drawCmdBuffers.size
        vkCreateFences(device, fenceCreateInfo, null, waitFences)  // check inside singularly
    }

    /** Get a new command buffer from the command pool
     *  If begin is true, the command buffer is also started so we can start adding commands    */
    fun getCommandBuffer(begin: Boolean): VkCommandBuffer = withStack {

        val cmdBufAllocateInfo = cVkCommandBufferAllocateInfo {
            type = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO
            commandPool = cmdPool
            level = VK_COMMAND_BUFFER_LEVEL_PRIMARY
            commandBufferCount = 1
        }

        val pCmdBuffer = mallocPointer()
        vkAllocateCommandBuffers(device, cmdBufAllocateInfo, pCmdBuffer).check()

        val cmdBuffer = VkCommandBuffer(pCmdBuffer[0], device)

        // If requested, also start the new command buffer
        if (begin)
            vkBeginCommandBuffer(cmdBuffer, commandBufferBeginInfo()).check()

        return cmdBuffer
    }

    /** End the command buffer and submit it to the queue
     *  Uses a fence to ensure command buffer has finished executing before deleting it */
    fun flushCommandBuffer(commandBuffer: VkCommandBuffer) = withStack {

        assert(commandBuffer.address != NULL)

        vkEndCommandBuffer(commandBuffer).check()

        val submitInfo = cVkSubmitInfo {
            type = VkStructureType_SUBMIT_INFO
            submitInfo.commandBuffers = commandBuffer.toPointerBuffer()
        }

        // Create fence to ensure that the command buffer has finished executing
        val fenceCreateInfo = cVkFenceCreateInfo {
            type = VkStructureType_FENCE_CREATE_INFO
            flags = 0
        }
        val pFence = mallocLong()
        vkCreateFence(device, fenceCreateInfo, null, pFence).check()
        val fence: VkFence = pFence[0]

        // Submit to the queue
        vkQueueSubmit(queue, submitInfo, fence).check()
        // Wait for the fence to signal that command buffer has finished executing
        vkWaitForFences(device, fence, true, DEFAULT_FENCE_TIMEOUT).check()

        vkDestroyFence(device, fence, null)
        vkFreeCommandBuffers(device, cmdPool, commandBuffer)
    }

    /** Build separate command buffers for every framebuffer image
     *  Unlike in OpenGL all rendering commands are recorded once into command buffers that are then resubmitted to the queue
     *  This allows to generate work upfront and from multiple threads, one of the biggest advantages of Vulkan */
    fun buildCommandBuffers() = withStack {
        val cmdBufInfo = cVkCommandBufferBeginInfo {
            type = VkStructureType_COMMAND_BUFFER_BEGIN_INFO
            next = NULL
        }

        /*  Set clear values for all framebuffer attachments with loadOp set to clear
            We use two attachments (color and depth) that are cleared at the start of the subpass and
            as such we need to set clear values for both         */
        val clearValues = mVkClearValue(2)
        clearValues[0].color(0f, 0f, 0.2f, 1f)
        clearValues[1].depthStencil(1f, 0)

        val renderPassBeginInfo = cVkRenderPassBeginInfo {
            type = VkStructureType_RENDER_PASS_BEGIN_INFO
            next = NULL
            renderPass = this@VulkanExample.renderPass
            with(renderArea.offset) {
                x = 0
                y = 0
            }
            renderArea.extent.size(size)
            this.clearValues = clearValues
        }

        for (i in 0 until drawCmdBuffers.size) {
            // Set target frame buffer
            renderPassBeginInfo.framebuffer = frameBuffers[i]

            vkBeginCommandBuffer(drawCmdBuffers[i], cmdBufInfo).check()

            // Start the first sub pass specified in our default render pass setup by the base class
            // This will clear the color and depth attachment
            vkCmdBeginRenderPass(drawCmdBuffers[i], renderPassBeginInfo, VkSubpassContents_INLINE)

            // Update dynamic viewport state
            val viewport = mVkViewport(1) {
                //                size(this@VulkanExample.size) TODO bug
                size(size)
                //                depth.put(0f, 1f) same
                depth(0f, 1f)
            }
            vkCmdSetViewport(drawCmdBuffers[i], 0, viewport)

            // Update dynamic scissor state
            val scissor = mVkRect2D(1) {
                extent.size(size)
                offset.pos(0, 0)
            }
            vkCmdSetScissor(drawCmdBuffers[i], 0, scissor)

            // Bind descriptor sets describing shader binding points
            vkCmdBindDescriptorSets(drawCmdBuffers[i], VkPipelineBindPoint_GRAPHICS, pipelineLayout, 0, ::descriptorSet, null)

            // Bind the rendering pipeline
            // The pipeline (state object) contains all states of the rendering pipeline, binding it will set all the states specified at pipeline creation time
            vkCmdBindPipeline(drawCmdBuffers[i], VkPipelineBindPoint_GRAPHICS, pipeline)

            // Bind triangle vertex buffer (contains position and colors)
            val offsets = longs(0)
            vkCmdBindVertexBuffers(drawCmdBuffers[i], 0, vertices::buffer, offsets)

            // Bind triangle index buffer
            vkCmdBindIndexBuffer(drawCmdBuffers[i], indices.buffer, 0, VkIndexType_UINT32)

            // Draw indexed triangle
            vkCmdDrawIndexed(drawCmdBuffers[i], indices.count, 1, 0, 0, 1)

            vkCmdEndRenderPass(drawCmdBuffers[i])

            /*  Ending the render pass will add an implicit barrier transitioning the frame buffer color attachment to
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR for presenting it to the windowing system             */

            vkEndCommandBuffer(drawCmdBuffers[i]).check()
        }
    }

    fun draw() = withStack {
        // Get next image in the swap chain (back/front buffer)
        swapChain.acquireNextImage(presentCompleteSemaphore, ::currentBuffer).check()

        // Use a fence to wait until the command buffer has finished execution before using it again
        vkWaitForFences(device, waitFences[currentBuffer], true, Long.MAX_VALUE).check()
        vkResetFences(device, waitFences[currentBuffer]).check()

        // Pipeline stage at which the queue submission will wait (via pWaitSemaphores)
        val waitStageMask = ints(VkPipelineStage_COLOR_ATTACHMENT_OUTPUT_BIT)
        // The submit info structure specifices a command buffer queue submission batch
        val submitInfo = cVkSubmitInfo {
            type = VkStructureType_SUBMIT_INFO
            // Pointer to the list of pipeline stages that the semaphore waits will occur at
            waitDstStageMask = waitStageMask
            // Semaphore(s) to wait upon before the submitted command buffer starts executing
            waitSemaphores = presentCompleteSemaphore.toLongBuffer()
            // One wait semaphore
            waitSemaphoreCount = 1
            // Semaphore(s) to be signaled when command buffers have completed
            signalSemaphores = renderCompleteSemaphore.toLongBuffer()
            // One signal semaphore + Command buffers(s) to execute in this batch (submission)
            submitInfo.commandBuffers(drawCmdBuffers, currentBuffer)
        }

        // Submit to the graphics queue passing a wait fence
        vkQueueSubmit(queue, submitInfo, waitFences[currentBuffer]).check()

        /*  Present the current buffer to the swap chain
            Pass the semaphore signaled by the command buffer submission from the submit info as the wait semaphore
            for swap chain presentation
            This ensures that the image is not presented to the windowing system until all commands have been submitted */
        swapChain.queuePresent(queue, currentBuffer, renderCompleteSemaphore).check()
    }

    /** Prepare vertex and index buffers for an indexed triangle
     *  Also uploads them to device local memory using staging and initializes vertex input and attribute binding
     *  to match the vertex shader  */
    fun prepareVertices(useStagingBuffers: Boolean) = withStack {
        /*  A note on memory management in Vulkan in general:
            This is a very complex topic and while it's fine for an example application to to small individual memory
            allocations that is not what should be done a real-world application, where you should allocate large
            chunks of memory at once instead.   */

        // Setup vertices
        val vertexBuffer = floatBufferOf(
                // position    color
                +1f, +1f, +0f, 1f, 0f, 0f,
                -1f, +1f, +0f, 0f, 1f, 0f,
                +0f, -1f, +0f, 0f, 0f, 1f)

        val vertexBufferSize = vertexBuffer.size.L

        // Setup indices
        val indexBuffer = intBufferOf(0, 1, 2)
        indices.count = indexBuffer.capacity
        val indexBufferSize = indexBuffer.size.L

        val memAlloc = cVkMemoryAllocateInfo { type = VkStructureType_MEMORY_ALLOCATE_INFO }

        val memReqs = mVkMemoryRequirements()

        val data = mallocPointer()

        if (useStagingBuffers) {
            /*  Static data like vertex and index buffer should be stored on the device memory for optimal (and fastest)
                access by the GPU

                To achieve this we use so-called "staging buffers" :
                - Create a buffer that's visible to the host (and can be mapped)
                - Copy the data to this buffer
                - Create another buffer that's local on the device (VRAM) with the same size
                - Copy the data from the host to the device using a command buffer
                - Delete the host visible (staging) buffer
                - Use the device local buffers for rendering    */

            class StagingBuffer {
                var memory: VkDeviceMemory = NULL
                var buffer: VkBuffer = NULL
            }

            val stagingBuffers = object {
                val vertices = StagingBuffer()
                val indices = StagingBuffer()
            }

            // Vertex buffer
            val vertexBufferInfo = cVkBufferCreateInfo {
                type = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO
                size = vertexBufferSize
                // Buffer is used as the copy source
                usage = VkImageUsage_TRANSFER_SRC_BIT
            }
            // Create a host-visible buffer to copy the vertex data to (staging buffer)
            vkCreateBuffer(device, vertexBufferInfo, null, stagingBuffers.vertices::buffer).check()
            vkGetBufferMemoryRequirements(device, stagingBuffers.vertices.buffer, memReqs)
            memAlloc.allocationSize = memReqs.size
            // Request a host visible memory type that can be used to copy our data do
            // Also request it to be coherent, so that writes are visible to the GPU right after unmapping the buffer
            memAlloc.memoryTypeIndex = getMemoryTypeIndex(memReqs.memoryTypeBits, VkMemoryProperty_HOST_VISIBLE_BIT or VkMemoryProperty_HOST_COHERENT_BIT)
            vkAllocateMemory(device, memAlloc, null, stagingBuffers.vertices::memory).check()
            // Map and copy
            vkMapMemory(device, stagingBuffers.vertices.memory, 0, memAlloc.allocationSize, 0, data).check()
            memCopy(vertexBuffer.address, data[0], vertexBufferSize)
            vkUnmapMemory(device, stagingBuffers.vertices.memory)
            vkBindBufferMemory(device, stagingBuffers.vertices.buffer, stagingBuffers.vertices.memory, 0).check()

            // Create a device local buffer to which the (host local) vertex data will be copied and which will be used for rendering
            vertexBufferInfo.usage = VkBufferUsage_VERTEX_BUFFER_BIT or VkBufferUsage_TRANSFER_DST_BIT
            vkCreateBuffer(device, vertexBufferInfo, null, vertices::buffer).check()
            vkGetBufferMemoryRequirements(device, vertices.buffer, memReqs)
            memAlloc.allocationSize = memReqs.size
            memAlloc.memoryTypeIndex = getMemoryTypeIndex(memReqs.memoryTypeBits, VkMemoryProperty_DEVICE_LOCAL_BIT)
            vkAllocateMemory(device, memAlloc, null, vertices::memory).check()
            vkBindBufferMemory(device, vertices.buffer, vertices.memory, 0).check()

            // Index buffer
            val indexbufferInfo = cVkBufferCreateInfo {
                type = VkStructureType_BUFFER_CREATE_INFO
                size = indexBufferSize
                usage = VkImageUsage_TRANSFER_SRC_BIT
            }
            // Copy index data to a buffer visible to the host (staging buffer)
            vkCreateBuffer(device, indexbufferInfo, null, stagingBuffers.indices::buffer).check()
            vkGetBufferMemoryRequirements(device, stagingBuffers.indices.buffer, memReqs)
            memAlloc.allocationSize = memReqs.size
            memAlloc.memoryTypeIndex = getMemoryTypeIndex(memReqs.memoryTypeBits, VkMemoryProperty_HOST_VISIBLE_BIT or VkMemoryProperty_HOST_COHERENT_BIT)
            vkAllocateMemory(device, memAlloc, null, stagingBuffers.indices::memory).check()
            vkMapMemory(device, stagingBuffers.indices.memory, 0, indexBufferSize, 0, data).check()
            memCopy(indexBuffer.address, data[0], indexBufferSize)
            vkUnmapMemory(device, stagingBuffers.indices.memory)
            vkBindBufferMemory(device, stagingBuffers.indices.buffer, stagingBuffers.indices.memory, 0).check()

            // Create destination buffer with device only visibility
            indexbufferInfo.usage = VkBufferUsage_INDEX_BUFFER_BIT or VkBufferUsage_TRANSFER_DST_BIT
            vkCreateBuffer(device, indexbufferInfo, null, indices::buffer).check()
            vkGetBufferMemoryRequirements(device, indices.buffer, memReqs)
            memAlloc.allocationSize = memReqs.size
            memAlloc.memoryTypeIndex = getMemoryTypeIndex(memReqs.memoryTypeBits, VkMemoryProperty_DEVICE_LOCAL_BIT)
            vkAllocateMemory(device, memAlloc, null, indices::memory).check()
            vkBindBufferMemory(device, indices.buffer, indices.memory, 0).check()

            val cmdBufferBeginInfo = cVkCommandBufferBeginInfo {
                type = VkStructureType_COMMAND_BUFFER_BEGIN_INFO
                next = NULL
            }

            /*  Buffer copies have to be submitted to a queue, so we need a command buffer for them
                Note: Some devices offer a dedicated transfer queue (with only the transfer bit set) that may be faster
                when doing lots of copies             */
            val copyCmd = getCommandBuffer(true)

            // Put buffer region copies into command buffer
            val copyRegion = mVkBufferCopy(1)
            // Vertex buffer
            copyRegion.size = vertexBufferSize
            vkCmdCopyBuffer(copyCmd, stagingBuffers.vertices.buffer, vertices.buffer, copyRegion)
            // Index buffer
            copyRegion.size = indexBufferSize
            vkCmdCopyBuffer(copyCmd, stagingBuffers.indices.buffer, indices.buffer, copyRegion)


            // Flushing the command buffer will also submit it to the queue and uses a fence to ensure that all commands have been executed before returning
            flushCommandBuffer(copyCmd)

            // Destroy staging buffers
            // Note: Staging buffer must not be deleted before the copies have been submitted and executed
            vkDestroyBuffer(device, stagingBuffers.vertices.buffer)
            vkFreeMemory(device, stagingBuffers.vertices.memory)
            vkDestroyBuffer(device, stagingBuffers.indices.buffer, null)
            vkFreeMemory(device, stagingBuffers.indices.memory, null)
        } else {
            /*  Don't use staging
                Create host-visible buffers only and use these for rendering. This is not advised and will usually
                result in lower rendering performance             */

            // Vertex buffer
            val vertexBufferInfo = cVkBufferCreateInfo {
                type = VkStructureType_BUFFER_CREATE_INFO
                size = vertexBufferSize
                usage = VkBufferUsage_VERTEX_BUFFER_BIT
            }

            // Copy vertex data to a buffer visible to the host
            vkCreateBuffer(device, vertexBufferInfo, null, vertices::buffer).check()
            vkGetBufferMemoryRequirements(device, vertices.buffer, memReqs)
            memAlloc.allocationSize = memReqs.size
            // VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT is host visible memory, and VK_MEMORY_PROPERTY_HOST_COHERENT_BIT makes sure writes are directly visible
            memAlloc.memoryTypeIndex = getMemoryTypeIndex(memReqs.memoryTypeBits, VkMemoryProperty_HOST_VISIBLE_BIT or VkMemoryProperty_HOST_COHERENT_BIT)
            vkAllocateMemory(device, memAlloc, null, vertices::memory).check()
            vkMapMemory(device, vertices.memory, 0, memAlloc.allocationSize, 0, data).check()
            memCopy(vertexBuffer.address, data[0], vertexBufferSize)
            vkUnmapMemory(device, vertices.memory)
            vkBindBufferMemory(device, vertices.buffer, vertices.memory, 0).check()

            // Index buffer
            val indexbufferInfo = cVkBufferCreateInfo {
                type = VkStructureType_BUFFER_CREATE_INFO
                size = indexBufferSize
                usage = VkBufferUsage_INDEX_BUFFER_BIT
            }

            // Copy index data to a buffer visible to the host
            vkCreateBuffer(device, indexbufferInfo, null, indices::buffer).check()
            vkGetBufferMemoryRequirements(device, indices.buffer, memReqs)
            memAlloc.allocationSize = memReqs.size
            memAlloc.memoryTypeIndex = getMemoryTypeIndex(memReqs.memoryTypeBits, VkMemoryProperty_HOST_VISIBLE_BIT or VkMemoryProperty_HOST_COHERENT_BIT)
            vkAllocateMemory(device, memAlloc, null, indices::memory).check()
            vkMapMemory(device, indices.memory, 0, indexBufferSize, 0, data).check()
            memCopy(indexBuffer.address, data[0], indexBufferSize)
            vkUnmapMemory(device, indices.memory)
            vkBindBufferMemory(device, indices.buffer, indices.memory, 0).check()
        }
    }

    fun setupDescriptorPool() = withStack {
        // We need to tell the API the number of max. requested descriptors per type
        val typeCounts = mVkDescriptorPoolSize(1) {
            // This example only uses one descriptor type (uniform buffer) and only requests one descriptor of this type
            type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
            descriptorCount = 1
        }
        // For additional types you need to add new entries in the type count list
        // E.g. for two combined image samplers :
        // typeCounts[1].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        // typeCounts[1].descriptorCount = 2;

        // Create the global descriptor pool
        // All descriptors used in this example are allocated from this pool
        val descriptorPoolInfo = cVkDescriptorPoolCreateInfo {
            type = VkStructureType_DESCRIPTOR_POOL_CREATE_INFO
            next = NULL
            poolSizes = typeCounts
            // Set the max. number of descriptor sets that can be requested from this pool (requesting beyond this limit will result in an error)
            maxSets = 1
        }
        vkCreateDescriptorPool(device, descriptorPoolInfo, null, ::descriptorPool).check()
    }

    fun setupDescriptorSetLayout() = withStack {
        /*  Setup layout of descriptors used in this example
            Basically connects the different shader stages to descriptors for binding uniform buffers, image samplers, etc.
            So every shader binding should map to one descriptor set layout binding */

        // Binding 0: Uniform buffer (Vertex shader)
        val layoutBinding = mVkDescriptorSetLayoutBinding(1) {
            descriptorType = VkDescriptorType_UNIFORM_BUFFER
            descriptorCount = 1
            stageFlags = VkShaderStage_VERTEX_BIT
            immutableSamplers = null
        }

        val descriptorLayout = cVkDescriptorSetLayoutCreateInfo {
            type = VkStructureType_DESCRIPTOR_SET_LAYOUT_CREATE_INFO
            next = NULL
            bindings = layoutBinding
        }

        vkCreateDescriptorSetLayout(device, descriptorLayout, null, ::descriptorSetLayout).check()

        // Create the pipeline layout that is used to generate the rendering pipelines that are based on this descriptor set layout
        // In a more complex scenario you would have different pipeline layouts for different descriptor set layouts that could be reused
        val pipelineLayoutCreateInfo = cVkPipelineLayoutCreateInfo {
            type = VkStructureType_PIPELINE_LAYOUT_CREATE_INFO
            next = NULL
            setLayouts = descriptorSetLayout.toLongBuffer()
        }

        vkCreatePipelineLayout(device, pipelineLayoutCreateInfo, null, ::pipelineLayout).check()
    }

    fun setupDescriptorSet() = withStack {
        // Allocate a new descriptor set from the global descriptor pool
        val allocInfo = cVkDescriptorSetAllocateInfo {
            type = VkStructureType_DESCRIPTOR_SET_ALLOCATE_INFO
            descriptorPool = this@VulkanExample.descriptorPool
            setLayouts = descriptorSetLayout.toLongBuffer()
        }
        vkAllocateDescriptorSets(device, allocInfo, ::descriptorSet).check()

        /*  Update the descriptor set determining the shader binding points
            For every binding point used in a shader there needs to be one descriptor set matching that binding point   */

        val writeDescriptorSet = mVkWriteDescriptorSet(1) {
            // Binding 0 : Uniform buffer
            type = VkStructureType_WRITE_DESCRIPTOR_SET
            dstSet = descriptorSet
            descriptorType = VkDescriptorType_UNIFORM_BUFFER
            bufferInfo = uniformBufferVS.descriptor
            // Binds this uniform buffer to binding point 0
            dstBinding = 0
        }

        vkUpdateDescriptorSets(device, writeDescriptorSet, null)
    }

    /** Create the depth (and stencil) buffer attachments used by our framebuffers
     *  Note: Override of virtual function in the base class and called from within VulkanExampleBase::prepare  */
    override fun setupDepthStencil() = withStack {
        // Create an optimal image used as the depth stencil attachment
        val image = cVkImageCreateInfo {
            type = VkStructureType_IMAGE_CREATE_INFO
            imageType = VkImageViewType_2D
            format = depthFormat
            // Use example's height and width
            extent.wtf(size.x, size.y, 1)
            mipLevels = 1
            arrayLayers = 1
            samples = VkSampleCount_1_BIT
            tiling = VkImageTiling_OPTIMAL
            usage = VkImageUsage_DEPTH_STENCIL_ATTACHMENT_BIT or VkImageUsage_TRANSFER_SRC_BIT
            initialLayout = VkImageLayout_UNDEFINED
        }
        vkCreateImage(device, image, null, depthStencil::image)

        // Allocate memory for the image (device local) and bind it to our image
        val memAlloc = cVkMemoryAllocateInfo {
            type = VkStructureType_MEMORY_ALLOCATE_INFO
            val memReqs = mVkMemoryRequirements()
            vkGetImageMemoryRequirements(device, depthStencil.image, memReqs)
            allocationSize = memReqs.size
            memoryTypeIndex = getMemoryTypeIndex(memReqs.memoryTypeBits, VkMemoryProperty_DEVICE_LOCAL_BIT)
        }
        vkAllocateMemory(device, memAlloc, null, depthStencil::mem).check()
        vkBindImageMemory(device, depthStencil.image, depthStencil.mem, 0).check()

        /*  Create a view for the depth stencil image
            Images aren't directly accessed in Vulkan, but rather through views described by a subresource range
            This allows for multiple views of one image with differing ranges (e.g. for different layers)   */
        val depthStencilView = cVkImageViewCreateInfo {
            type = VkStructureType_IMAGE_VIEW_CREATE_INFO
            viewType = VkImageViewType_2D
            format = depthFormat
            with(subresourceRange) {
                aspectMask = VkImageAspect_DEPTH_BIT or VkImageAspect_STENCIL_BIT
                baseMipLevel = 0
                levelCount = 1
                baseArrayLayer = 0
                layerCount = 1
            }
            this.image = depthStencil.image
        }
        vkCreateImageView(device, depthStencilView, null, depthStencil::view).check()
    }

    /** Create a frame buffer for each swap chain image
     *  Note: Override of virtual function in the base class and called from within VulkanExampleBase::prepare  */
    override fun setupFrameBuffer() = withStack {
        // Create a frame buffer for every image in the swapchain
        frameBuffers resize swapChain.imageCount
        for (i in frameBuffers.indices) {
            val attachments = vkImageViewOf(
                    swapChain.buffers[i].view,  // Color attachment is the view of the swapchain image
                    depthStencil.view)          // Depth/Stencil attachment is the same for all frame buffers

            val frameBufferCreateInfo = cVkFramebufferCreateInfo().also {
                it.type = VkStructureType_FRAMEBUFFER_CREATE_INFO
                // All frame buffers use the same renderpass setup
                it.renderPass = renderPass
                it.attachments = attachments
                it.size(size, 1)
            }
            // Create the framebuffer
            vkCreateFramebuffer(device, frameBufferCreateInfo, null, frameBuffers, i).check()
        }
    }

    /** Render pass setup
     *  Render passes are a new concept in Vulkan. They describe the attachments used during rendering and may contain
     *  multiple subpasses with attachment dependencies
     *  This allows the driver to know up-front what the rendering will look like and is a good opportunity to optimize
     *  especially on tile-based renderers (with multiple subpasses)
     *  Using sub pass dependencies also adds implicit layout transitions for the attachment used, so we don't need
     *  to add explicit image memory barriers to transform them
     *  Note: Override of virtual function in the base class and called from within VulkanExampleBase::prepare  */
    override fun setupRenderPass() = withStack {
        // This example will use a single render pass with one subpass

        // Descriptors for the attachments used by this renderpass
        val attachments = cVkAttachmentDescription(2)

        // Color attachment
        with(attachments[0]) {
            format = swapChain.colorFormat                  // Use the color format selected by the swapchain
            samples = VkSampleCount_1_BIT                   // We don't use multi sampling in this example
            loadOp = VkAttachmentLoadOp_CLEAR               // Clear this attachment at the start of the render pass
            storeOp = VkAttachmentStoreOp_STORE             // Keep it's contents after the render pass is finished (for displaying it)
            stencilLoadOp = VkAttachmentLoadOp_DONT_CARE    // We don't use stencil, so don't care for load
            stencilStoreOp = VkAttachmentStoreOp_DONT_CARE  // Same for store
            initialLayout = VkImageLayout_UNDEFINED         // Layout at render pass start. Initial doesn't matter, so we use undefined
            finalLayout = VkImageLayout_PRESENT_SRC_KHR     // Layout to which the attachment is transitioned when the render pass is finished
        }
        // As we want to present the color buffer to the swapchain, we transition to PRESENT_KHR
        // Depth attachment
        with(attachments[1]) {
            format = depthFormat                                            // A proper depth format is selected in the example base
            samples = VkSampleCount_1_BIT
            loadOp = VkAttachmentLoadOp_CLEAR                               // Clear depth at start of first subpass
            storeOp = VkAttachmentStoreOp_DONT_CARE                         // We don't need depth after render pass has finished (DONT_CARE may result in better performance)
            stencilLoadOp = VkAttachmentLoadOp_DONT_CARE                    // No stencil
            stencilStoreOp = VkAttachmentStoreOp_DONT_CARE                  // No Stencil
            initialLayout = VkImageLayout_UNDEFINED                         // Layout at render pass start. Initial doesn't matter, so we use undefined
            finalLayout = VkImageLayout_DEPTH_STENCIL_ATTACHMENT_OPTIMAL    // Transition to depth/stencil attachment
        }

        // Setup attachment references
        val colorReference = cVkAttachmentReference(1) {
            attachment = 0                                      // Attachment 0 is color
            layout = VkImageLayout_COLOR_ATTACHMENT_OPTIMAL     // Attachment layout used as color during the subpass
        }

        val depthReference = cVkAttachmentReference {
            attachment = 1                                              // Attachment 1 is color
            layout = VkImageLayout_DEPTH_STENCIL_ATTACHMENT_OPTIMAL     // Attachment used as depth/stemcil used during the subpass
        }

        // Setup a single subpass reference
        val subpassDescription = cVkSubpassDescription(1) {
            pipelineBindPoint = VkPipelineBindPoint_GRAPHICS
            colorAttachments = colorReference                   // Reference to the color attachment in slot 0
            depthStencilAttachment = depthReference             // Reference to the depth attachment in slot 1
            inputAttachments = null                             // (Input attachments not used by this example)
            preserveAttachments = null                          // (Preserve attachments not used by this example)
            resolveAttachments = null                           // Resolve attachments are resolved at the end of a sub pass and can be used for e.g. multi sampling
        }

        /*  Setup subpass dependencies
            These will add the implicit ttachment layout transitionss specified by the attachment descriptions
            The actual usage layout is preserved through the layout specified in the attachment reference
            Each subpass dependency will introduce a memory and execution dependency between the source and dest subpass described by
            srcStageMask, dstStageMask, srcAccessMask, dstAccessMask (and dependencyFlags is set)
            Note: VK_SUBPASS_EXTERNAL is a special constant that refers to all commands executed outside of the actual renderpass)  */
        val dependencies = mVkSubpassDependency(2)

        /*  First dependency at the start of the renderpass
            Does the transition from final to initial layout         */
        with(dependencies[0]) {
            srcSubpass = VK_SUBPASS_EXTERNAL    // Producer of the dependency
            dstSubpass = 0                      // Consumer is our single subpass that will wait for the execution depdendency
            srcStageMask = VkPipelineStage_BOTTOM_OF_PIPE_BIT
            dstStageMask = VkPipelineStage_COLOR_ATTACHMENT_OUTPUT_BIT
            srcAccessMask = VkAccess_MEMORY_READ_BIT
            dstAccessMask = VkAccess_COLOR_ATTACHMENT_READ_BIT or VkAccess_COLOR_ATTACHMENT_WRITE_BIT
            dependencyFlags = VkDependency_BY_REGION_BIT
        }

        /*  Second dependency at the end the renderpass
            Does the transition from the initial to the final layout         */
        with(dependencies[1]) {
            srcSubpass = 0                      // Producer of the dependency is our single subpass
            dstSubpass = VK_SUBPASS_EXTERNAL    // Consumer are all commands outside of the renderpass
            srcStageMask = VkPipelineStage_COLOR_ATTACHMENT_OUTPUT_BIT
            dstStageMask = VkPipelineStage_BOTTOM_OF_PIPE_BIT
            srcAccessMask = VkAccess_COLOR_ATTACHMENT_READ_BIT or VkAccess_COLOR_ATTACHMENT_WRITE_BIT
            dstAccessMask = VkAccess_MEMORY_READ_BIT
            dependencyFlags = VkDependency_BY_REGION_BIT
        }

        // Create the actual renderpass
        val renderPassInfo = cVkRenderPassCreateInfo {
            type = VkStructureType_RENDER_PASS_CREATE_INFO
            this.attachments = attachments                  // Descriptions of the attachments used by the render pass
            // We only use one subpass in this example, Description of that subpass
            subpasses = subpassDescription
            this.dependencies = dependencies                // Subpass dependencies used by the render pass
        }

        vkCreateRenderPass(device, renderPassInfo, null, ::renderPass).check()
    }

    /** Vulkan loads its shaders from an immediate binary representation called SPIR-V
     *  Shaders are compiled offline from e.g. GLSL using the reference glslang compiler
     *  This function loads such a shader from a binary file and returns a shader module structure  */
    fun loadSPIRVShader(filename: String): VkShaderModule = withStack {

        val file = File(ClassLoader.getSystemResource(filename).toURI())

        return if (file.exists() && file.canRead()) {

            val shaderModule = mallocLong()

            file.readBytes().toBuffer().use { shaderCode ->
                // Create a new shader module that will be used for pipeline creation
                val moduleCreateInfo = cVkShaderModuleCreateInfo {
                    type = VkStructureType_SHADER_MODULE_CREATE_INFO
                    code = shaderCode
                }

                vkCreateShaderModule(device, moduleCreateInfo, null, shaderModule)
            }

            shaderModule[0]
        } else {
            System.err.println("Error: Could not open shader file \"$filename\"")
            NULL
        }
    }

    fun preparePipelines() = withStack {
        /*  Create the graphics pipeline used in this example
            Vulkan uses the concept of rendering pipelines to encapsulate fixed states, replacing OpenGL's complex state machine
            A pipeline is then stored and hashed on the GPU making pipeline changes very fast
            Note: There are still a few dynamic states that are not directly part of the pipeline (but the info that they are used is)  */

//        val pipelineCreateInfo = cVkGraphicsPipelineCreateInfo(1) {
//            type = VkStructureType_GRAPHICS_PIPELINE_CREATE_INFO
//            // The layout used for this pipeline (can be shared among multiple pipelines using the same layout)
//            layout = pipelineLayout
//            // Renderpass this pipeline is attached to
//            renderPass = this@VulkanExample.renderPass
//        }
//
//        /*  Construct the different states making up the pipeline
//
//            Input assembly state describes how primitives are assembled
//            This pipeline will assemble vertex data as a triangle lists (though we only use one triangle)   */
//        val inputAssemblyState = cVkPipelineInputAssemblyStateCreateInfo {
//            type = VkStructureType_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO
//            topology = VkPrimitiveTopology_TRIANGLE_LIST
//        }
//
//        // Rasterization state
//        val rasterizationState = cVkPipelineRasterizationStateCreateInfo {
//            type = VkStructureType_PIPELINE_RASTERIZATION_STATE_CREATE_INFO
//            polygonMode = VkPoligonMode_FILL
//            cullMode = VkCullMode_NONE
//            frontFace = VkFrontFace_COUNTER_CLOCKWISE
//            depthClampEnable = false
//            rasterizerDiscardEnable = false
//            depthBiasEnable = false
//            lineWidth = 1f
//        }
//
//        /*  Color blend state describes how blend factors are calculated (if used)
//            We need one blend attachment state per color attachment (even if blending is not used         */
//        val blendAttachmentState = cVkPipelineColorBlendAttachmentState(1) {
//            colorWriteMask = 0xf
//            blendEnable = false
//        }
//        val colorBlendState = cVkPipelineColorBlendStateCreateInfo {
//            type = VkStructureType_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO
//            attachments = blendAttachmentState
//        }
//
//        /*  Viewport state sets the number of viewports and scissor used in this pipeline
//            Note: This is actually overriden by the dynamic states (see below)         */
//        val viewportState = cVkPipelineViewportStateCreateInfo {
//            type = VkStructureType_PIPELINE_VIEWPORT_STATE_CREATE_INFO
//            viewportCount = 1
//            scissorCount = 1
//        }
//
//        /** Enable dynamic states
//         *  Most states are baked into the pipeline, but there are still a few dynamic states that can be changed within a command buffer
//         *  To be able to change these we need do specify which dynamic states will be changed using this pipeline.
//         *  Their actual states are set later on in the command buffer.
//         *  For this example we will set the viewport and scissor using dynamic states  */
//        val dynamicStateEnables = arrayListOf(VkDynamicState_VIEWPORT, VkDynamicState_SCISSOR)
//        val dynamicState = cVkPipelineDynamicStateCreateInfo {
//            type = VkStructureType_PIPELINE_DYNAMIC_STATE_CREATE_INFO
//            dynamicStates = dynamicStateEnables.toIntBuffer()
//        }
//
//        /*  Depth and stencil state containing depth and stencil compare and test operations
//            We only use depth tests and want depth tests and writes to be enabled and compare with less or equal         */
//        val depthStencilState = cVkPipelineDepthStencilStateCreateInfo {
//            type = VkStructureType_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO
//            depthTestEnable = true
//            depthWriteEnable = true
//            depthCompareOp = VkCompareOp_LESS_OR_EQUAL
//            depthBoundsTestEnable = false
//            with(back) {
//                failOp = VkStencilOp_KEEP
//                passOp = VkStencilOp_KEEP
//                compareOp = VkCompareOp_ALWAYS
//            }
//            stencilTestEnable = false
//            front = back
//        }
//
//        /*  Multi sampling state
//            This example does not make use fo multi sampling (for anti-aliasing), the state must still be set and passed to the pipeline         */
//        val multisampleState = cVkPipelineMultisampleStateCreateInfo {
//            type = VkStructureType_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO
//            rasterizationSamples = VkSampleCount_1_BIT
//            sampleMask = null
//        }
//
//        /*  Vertex input descriptions
//            Specifies the vertex input parameters for a pipeline
//
//            Vertex input binding
//            This example uses a single vertex input binding at binding point 0 (see vkCmdBindVertexBuffers) */
//        val vertexInputBinding = cVkVertexInputBindingDescription(1) {
//            binding = 0
//            stride = Vertex.size
//            inputRate = VkVertexInputRate_VERTEX
//        }
//
//        // Inpute attribute bindings describe shader attribute locations and memory layouts
//        val vertexInputAttributs = cVkVertexInputAttributeDescription(2)
//        /*  These match the following shader layout (see triangle.vert):
//            layout (location = 0) in vec3 inPos;
//            layout (location = 1) in vec3 inColor;  */
//
//        with(vertexInputAttributs[0]) {
//            // Attribute location 0: Position
//            binding = 0
//            location = 0
//            // Position attribute is three 32 bit signed (SFLOAT) floats (R32 G32 B32)
//            format = VkFormat_R32G32B32_SFLOAT
//            offset = Vertex.offsetPosition
//        }
//        with(vertexInputAttributs[1]) {
//            // Attribute location 1: Color
//            binding = 0
//            location = 1
//            // Color attribute is three 32 bit signed (SFLOAT) floats (R32 G32 B32)
//            format = VkFormat_R32G32B32_SFLOAT
//            offset = Vertex.offsetColor
//        }
//
//        // Vertex input state used for pipeline creation
//        val vertexInputState = cVkPipelineVertexInputStateCreateInfo {
//            type = VkStructureType_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO
//            vertexBindingDescriptions = vertexInputBinding
//            vertexAttributeDescriptions = vertexInputAttributs
//        }
//
//        // Shaders
//        val shaderStages = cVkPipelineShaderStageCreateInfo(2)
//
//        // Vertex shader
//        with(shaderStages[0]) {
//            type = VkStructureType_PIPELINE_SHADER_STAGE_CREATE_INFO
//            // Set pipeline stage for this shader
//            stage = VkShaderStage_VERTEX_BIT
//            // Load binary SPIR-V shader
//            module = loadSPIRVShader("shaders/triangle/triangle.vert.spv")
//            // Main entry point for the shader
//            name = "main"
//            assert(module != NULL)
//        }
//
//        // Fragment shader
//        with(shaderStages[1]) {
//            type = VkStructureType_PIPELINE_SHADER_STAGE_CREATE_INFO
//            // Set pipeline stage for this shader
//            stage = VkShaderStage_FRAGMENT_BIT
//            // Load binary SPIR-V shader
//            module = loadSPIRVShader("shaders/triangle/triangle.frag.spv")
//            // Main entry point for the shader
//            name = "main"
//            assert(module != NULL)
//        }
//
//        // Set pipeline shader stage info
//        pipelineCreateInfo[0].also {
//            it.stages = shaderStages
//
//            // Assign the pipeline states to the pipeline creation info structure
//            it.vertexInputState = vertexInputState
//            it.inputAssemblyState = inputAssemblyState
//            it.rasterizationState = rasterizationState
//            it.colorBlendState = colorBlendState
//            it.multisampleState = multisampleState
//            it.viewportState = viewportState
//            it.depthStencilState = depthStencilState
//            it.renderPass = renderPass
//            it.dynamicState = dynamicState
//            it.tessellationState = null
//        }

        val pipelineCreateInfo = VkGraphicsPipelineCreateInfo.calloc(1)
                .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .layout(pipelineLayout)
                .renderPass(renderPass)

        val inputAssemblyState = VkPipelineInputAssemblyStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)

        val rasterizationState = VkPipelineRasterizationStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .polygonMode(VK_POLYGON_MODE_FILL)
                .cullMode(VK_CULL_MODE_NONE)
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .depthClampEnable(false)
                .rasterizerDiscardEnable(false)
                .depthBiasEnable(false)
                .lineWidth(1f)

        val blendAttachmentState = VkPipelineColorBlendAttachmentState.calloc(1)
                .colorWriteMask(0xf)
                .blendEnable(false)
        val colorBlendState = VkPipelineColorBlendStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .pAttachments(blendAttachmentState)

        val viewportState = VkPipelineViewportStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1)
                .scissorCount(1)

        val dynamicStateEnables = intBufferOf(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)
        val dynamicState = VkPipelineDynamicStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                .pDynamicStates(dynamicStateEnables)

        val depthStencilState = VkPipelineDepthStencilStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                .depthTestEnable(true)
                .depthWriteEnable(true)
                .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL)
                .depthBoundsTestEnable(false)
                .stencilTestEnable(false)
        depthStencilState.back().failOp(VK_STENCIL_OP_KEEP)
                .passOp(VK_STENCIL_OP_KEEP)
                .compareOp(VK_COMPARE_OP_ALWAYS)
        depthStencilState.front(depthStencilState.back())

        val multisampleState = VkPipelineMultisampleStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
                .pSampleMask(null)

        val vertexInputBinding = VkVertexInputBindingDescription.calloc(1)
                .binding(0)
                .stride(Vertex.size)
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX)

        val vertexInputAttributs = VkVertexInputAttributeDescription.calloc(2)
        vertexInputAttributs[0].binding(0)
                .location(0)
                .format(VK_FORMAT_R32G32B32_SFLOAT)
                .offset(0)
        vertexInputAttributs[1].binding(0)
                .location(1)
                .format(VK_FORMAT_R32G32B32_SFLOAT)
                .offset(Vec3.size)

        val vertexInputState = VkPipelineVertexInputStateCreateInfo.calloc()
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                .pVertexBindingDescriptions(vertexInputBinding)
                .pVertexAttributeDescriptions(vertexInputAttributs)

        val shaderStages = VkPipelineShaderStageCreateInfo.calloc(2)

        shaderStages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_VERTEX_BIT)
                .module(loadShader("shaders/triangle/triangle.vert.spv", device))
                .pName("main".utf8)
        assert(shaderStages[0].module != VK_NULL_HANDLE)

        shaderStages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(loadShader("shaders/triangle/triangle.frag.spv", device))
                .pName("main".utf8)
        assert(shaderStages[1].module != VK_NULL_HANDLE)

        pipelineCreateInfo
                .pStages(shaderStages)
                .pVertexInputState(vertexInputState)
                .pInputAssemblyState(inputAssemblyState)
                .pRasterizationState(rasterizationState)
                .pColorBlendState(colorBlendState)
                .pMultisampleState(multisampleState)
                .pViewportState(viewportState)
                .pDepthStencilState(depthStencilState)
                .renderPass(renderPass)
                .pDynamicState(dynamicState)

        // Create rendering pipeline using the specified states
        vkCreateGraphicsPipelines(device, pipelineCache, pipelineCreateInfo, null, ::pipeline).check()

        // Shader modules are no longer needed once the graphics pipeline has been created
        vkDestroyShaderModule(device, shaderStages)
    }

    fun prepareUniformBuffers() = withStack {
        /*  Prepare and initialize a uniform buffer block containing shader uniforms
            Single uniforms like in OpenGL are no longer present in Vulkan. All Shader uniforms are passed
            via uniform buffer blocks         */
        val memReqs = mVkMemoryRequirements()

        // Vertex shader uniform buffer block
        val allocInfo = cVkMemoryAllocateInfo {
            type = VkStructureType_MEMORY_ALLOCATE_INFO
            next = NULL
            allocationSize = 0
            memoryTypeIndex = 0
        }

        val bufferInfo = cVkBufferCreateInfo {
            type = VkStructureType_BUFFER_CREATE_INFO
            size = uboVS.size
            // This buffer will be used as a uniform buffer
            usage = VkBufferUsage_UNIFORM_BUFFER_BIT
        }

        // Create a new buffer
        vkCreateBuffer(device, bufferInfo, null, uniformBufferVS::buffer).check()
        // Get memory requirements including size, alignment and memory type
        vkGetBufferMemoryRequirements(device, uniformBufferVS.buffer, memReqs)
        allocInfo.allocationSize = memReqs.size
        /*  Get the memory type index that supports host visibile memory access
            Most implementations offer multiple memory types and selecting the correct one to allocate memory from is crucial
            We also want the buffer to be host coherent so we don't have to flush (or sync after every update.
            Note: This may affect performance so you might not want to do this in a real world application that updates
            buffers on a regular base   */
        allocInfo.memoryTypeIndex = getMemoryTypeIndex(memReqs.memoryTypeBits, VkMemoryProperty_HOST_VISIBLE_BIT or VkMemoryProperty_HOST_COHERENT_BIT)
        // Allocate memory for the uniform buffer
        vkAllocateMemory(device, allocInfo, null, uniformBufferVS::memory).check()
        // Bind memory to buffer
        vkBindBufferMemory(device, uniformBufferVS.buffer, uniformBufferVS.memory, 0).check()

        // Store information in the uniform's descriptor that is used by the descriptor set
        uniformBufferVS.descriptor.buffer = uniformBufferVS.buffer
        uniformBufferVS.descriptor.offset = 0
        uniformBufferVS.descriptor.range = uboVS.size

        updateUniformBuffers()
    }

    fun updateUniformBuffers() = withStack {
        // Update matrices
        uboVS.projectionMatrix = glm.perspective(60f.rad, this@VulkanExample.size.aspect, 0.1f, 256f)

        uboVS.viewMatrix = glm.translate(Mat4(1f), 0f, 0f, zoom)

        uboVS.modelMatrix = Mat4(1f)
                .rotate(rotation.x.rad, 1f, 0f, 0f)
                .rotate(rotation.y.rad, 0f, 1f, 0f)
                .rotate(rotation.z.rad, 0f, 0f, 1f)

        // Map uniform buffer and update it
        val pData = mallocPointer()
        vkMapMemory(device, uniformBufferVS.memory, 0, uboVS.size, 0, pData)
        val buffer = MemoryUtil.memByteBuffer(pData[0], Mat4.size * 3)
        with(uboVS) {
            projectionMatrix to buffer
            modelMatrix.to(buffer, Mat4.size)
            viewMatrix.to(buffer, Mat4.size * 2)
        }
        /*  Unmap after data has been copied
            Note: Since we requested a host coherent memory type for the uniform buffer, the write is instantly visible to the GPU         */
        vkUnmapMemory(device, uniformBufferVS.memory)
    }

    override fun prepare() {
        super.prepare()
        prepareSynchronizationPrimitives()
        prepareVertices(USE_STAGING)
        prepareUniformBuffers()
        setupDescriptorSetLayout()
        preparePipelines()
        setupDescriptorPool()
        setupDescriptorSet()
        buildCommandBuffers()
        prepared = true
    }

    override fun render() {
        if (!prepared) return
        draw()
    }

    override fun viewChanged() {
        // This function is called by the base example class each time the view is changed by user input
        updateUniformBuffers()
    }
}