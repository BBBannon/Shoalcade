/*
 * ShoalcadePlayer: the Vulkan context for Vulkan cores (Xbox 360, Xenia; plan-windows-x360.md X-1) (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The core renders with Vulkan (RETRO_HW_CONTEXT_VULKAN) and hands each frame over with set_image. We copy it into one
 * Vulkan image of our own whose memory, and two semaphores, are exported to OpenGL (VK_KHR_external_memory_win32,
 * VK_KHR_external_semaphore_win32 / GL_EXT_memory_object_win32, GL_EXT_semaphore_win32). OpenGL then draws that image
 * as an ordinary texture, so the window, menu, screenshots and icon stay the OpenGL ones (video.cpp).
 *
 * vulkan-1.dll is loaded at run time: the player still needs only SDL3.dll beside it. The device is the same GPU as the
 * OpenGL context (matched by LUID), made by the core when it offers the context negotiation interface (Xenia does),
 * else by us. Windows only: elsewhere create() refuses.
 */
#pragma once
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#ifndef VK_NO_PROTOTYPES
#define VK_NO_PROTOTYPES
#endif
#include "libretro_vulkan.h"

namespace sc {
/** The OpenGL context's GPU (GL_EXT_memory_object / _win32), to pick the same one in Vulkan. */
struct GpuId {
    bool has_luid = false;
    uint8_t luid[8] = {};
    bool has_uuid = false;
    uint8_t uuid[16] = {};
};

/** The shared picture, for OpenGL to import. The handles are the caller's: close them once imported. */
struct SharedTarget {
    void *memory = nullptr; // HANDLE of the image's memory (opaque Win32)
    uint64_t size = 0;      // its allocation size
    void *ready = nullptr;  // HANDLE: Vulkan signals it when a frame is in the image
    void *done = nullptr;   // HANDLE: OpenGL signals it when it is done with the image
    unsigned width = 0, height = 0;
};

/** Closes a handle from SharedTarget (once OpenGL has imported it). */
void close_shared_handle(void *h);

class VulkanHost {
public:
    VulkanHost() = default;
    VulkanHost(const VulkanHost &) = delete;
    VulkanHost &operator=(const VulkanHost &) = delete;
    ~VulkanHost() { destroy(); }

    /** Instance + device on the GL context's GPU. `neg` = the core's negotiation interface, or nullptr. */
    bool create(const retro_hw_render_context_negotiation_interface_vulkan *neg, const GpuId &gl, std::string &err);
    /** After the core's context_destroy: waits for the GPU, frees everything, calls the core's destroy_device. */
    void destroy();
    bool active() const { return device_ != VK_NULL_HANDLE; }
    const retro_hw_render_interface_vulkan *interface() const { return &iface_; }
    const std::string &gpu_name() const { return gpu_name_; }

    /** (Re)makes the shared image at w x h (any old one is freed after the GPU is idle). */
    bool make_target(unsigned w, unsigned h, SharedTarget &out, std::string &err);
    unsigned target_width() const { return target_w_; }
    unsigned target_height() const { return target_h_; }
    /** A set_image arrived since the last frame. */
    bool has_new_image() const { return image_new_; }

    /**
     * At each video_refresh. copy: the core's frame (w x h, from the last set_image) goes into the shared image and
     * `ready` is signalled; gl_signalled: OpenGL signalled `done` (it held the image), so Vulkan waits for it first.
     * Also submits what the core left for this frame (set_command_buffers, set_signal_semaphore). False = the GPU
     * refused (err says why); the session can't go on.
     */
    bool frame(bool copy, unsigned w, unsigned h, bool gl_signalled, std::string &err);

private:
    struct Fn;
    bool load_loader(std::string &err);
    bool pick_gpu(const GpuId &gl, std::string &err);
    bool make_device(const retro_hw_render_context_negotiation_interface_vulkan *neg, std::string &err);
    bool own_device(std::string &err);
    bool load_device_functions(std::string &err);
    void free_target();
    /** Waits (at most 10 s) for sync index i's last submit. False = the GPU didn't finish it (hung or lost). */
    bool wait_fence(uint32_t i);
    void record_copy(VkCommandBuffer cmd, unsigned w, unsigned h);

    // retro_hw_render_interface_vulkan
    static void set_image_cb(void *h, const retro_vulkan_image *image, uint32_t n, const VkSemaphore *sems, uint32_t family);
    static uint32_t get_sync_index_cb(void *h);
    static uint32_t get_sync_index_mask_cb(void *h);
    static void set_command_buffers_cb(void *h, uint32_t n, const VkCommandBuffer *cmds);
    static void wait_sync_index_cb(void *h);
    static void lock_queue_cb(void *h);
    static void unlock_queue_cb(void *h);
    static void set_signal_semaphore_cb(void *h, VkSemaphore s);
    // negotiation v2 wrappers
    static VkInstance create_instance_wrapper(void *opaque, const VkInstanceCreateInfo *info);
    static VkDevice create_device_wrapper(VkPhysicalDevice gpu, void *opaque, const VkDeviceCreateInfo *info);

    static constexpr uint32_t kSync = 2; // frames in flight (the core's sync indexes)

    void *lib_ = nullptr;
    Fn *fn_ = nullptr;
    PFN_vkGetInstanceProcAddr gipa_ = nullptr;
    const retro_hw_render_context_negotiation_interface_vulkan *neg_ = nullptr;
    bool core_asked_ = false;       // the core's create_device(2) was called: its destroy_device is owed
    bool core_made_device_ = false; // ... and it made the device
    VkInstance instance_ = VK_NULL_HANDLE;
    VkPhysicalDevice gpu_ = VK_NULL_HANDLE;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t family_ = 0;
    uint32_t api_version_ = 0;
    std::string gpu_name_;
    retro_hw_render_interface_vulkan iface_{};
    std::recursive_mutex queue_mutex_; // lock_queue: the core may submit from its own threads
    std::mutex fence_mutex_;

    VkCommandPool pool_ = VK_NULL_HANDLE;
    VkCommandBuffer cmd_[kSync] = {};
    VkFence fence_[kSync] = {};
    bool fence_pending_[kSync] = {};
    std::atomic<uint32_t> index_{0};

    // The shared image.
    VkImage target_ = VK_NULL_HANDLE;
    VkDeviceMemory target_mem_ = VK_NULL_HANDLE;
    VkSemaphore ready_ = VK_NULL_HANDLE, done_ = VK_NULL_HANDLE;
    unsigned target_w_ = 0, target_h_ = 0;
    bool target_used_ = false; // OpenGL has had it (its layout is then SHADER_READ_ONLY, owned outside Vulkan)

    // What the core gave for the next frame.
    bool image_new_ = false;
    VkImage src_image_ = VK_NULL_HANDLE;
    VkImageLayout src_layout_ = VK_IMAGE_LAYOUT_UNDEFINED;
    VkFormat src_format_ = VK_FORMAT_UNDEFINED;
    VkImageSubresourceRange src_range_{};
    uint32_t src_family_ = VK_QUEUE_FAMILY_IGNORED;
    std::vector<VkSemaphore> wait_sems_;
    std::vector<VkCommandBuffer> core_cmds_;
    VkSemaphore core_signal_ = VK_NULL_HANDLE;
    bool warned_blit_ = false;
};
} // namespace sc
