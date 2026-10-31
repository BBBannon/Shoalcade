/*
 *     Copyright (C) 2026  Argosy contributors
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#ifndef LIBRETRODROID_VULKANBACKEND_H
#define LIBRETRODROID_VULKANBACKEND_H

#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#ifndef VK_NO_PROTOTYPES
#define VK_NO_PROTOTYPES
#endif

#include <vulkan/vulkan.h>
#include <android/hardware_buffer.h>
#include <mutex>
#include <string>
#include <vector>

#include "../libretro/libretro-common/include/libretro.h"
#include "../libretro/libretro-common/include/libretro_vulkan.h"

struct AImageReader;

namespace libretrodroid {

/**
 * Hosts a libretro core's Vulkan context next to the GLES presentation pipeline.
 *
 * The core renders into its own images; each finished frame is copied into an
 * AHardwareBuffer-backed image that the GL side imports as a texture, so shaders,
 * captures and secondary output keep working unchanged. Every entry point runs on the
 * GL thread that drives retro_run.
 */
class VulkanBackend {
public:
    static VulkanBackend& getInstance() {
        static VulkanBackend instance;
        return instance;
    }
    VulkanBackend(VulkanBackend const&) = delete;
    void operator=(VulkanBackend const&) = delete;

    struct DriverConfig {
        std::string directory;
        std::string library;
        std::string hookDirectory;
        std::string scratchDirectory;

        bool isCustom() const { return !directory.empty() && !library.empty(); }
        bool operator==(const DriverConfig& other) const {
            return directory == other.directory && library == other.library &&
                   hookDirectory == other.hookDirectory && scratchDirectory == other.scratchDirectory;
        }
    };

    void configure(bool preferVulkan, const DriverConfig& driver);
    bool isPreferred() const { return preferVulkan; }
    bool isAvailable();
    void setNegotiationInterface(const retro_hw_render_context_negotiation_interface_vulkan* negotiation);
    bool start();
    bool isStarted() const { return device != VK_NULL_HANDLE; }
    const retro_hw_render_interface_vulkan* getRenderInterface() const;

    bool ensureFrameBuffer(unsigned width, unsigned height);
    AHardwareBuffer* getFrameBuffer() const { return frameBuffer; }
    unsigned getFrameBufferWidth() const { return frameWidth; }
    unsigned getFrameBufferHeight() const { return frameHeight; }

    bool presentFrame(unsigned width, unsigned height);

    void destroyCoreDevice();
    void shutdown();

private:
    VulkanBackend() = default;

    struct Dispatch {
        PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
        PFN_vkEnumerateInstanceVersion enumerateInstanceVersion = nullptr;
        PFN_vkCreateInstance createInstance = nullptr;
        PFN_vkDestroyInstance destroyInstance = nullptr;
        PFN_vkEnumeratePhysicalDevices enumeratePhysicalDevices = nullptr;
        PFN_vkGetPhysicalDeviceProperties getPhysicalDeviceProperties = nullptr;
        PFN_vkGetPhysicalDeviceQueueFamilyProperties getPhysicalDeviceQueueFamilyProperties = nullptr;
        PFN_vkEnumerateDeviceExtensionProperties enumerateDeviceExtensionProperties = nullptr;
        PFN_vkGetPhysicalDeviceFeatures getPhysicalDeviceFeatures = nullptr;
        PFN_vkCreateDevice createDevice = nullptr;
        PFN_vkGetDeviceProcAddr getDeviceProcAddr = nullptr;
        PFN_vkDestroyDevice destroyDevice = nullptr;
        PFN_vkGetDeviceQueue getDeviceQueue = nullptr;
        PFN_vkDeviceWaitIdle deviceWaitIdle = nullptr;
        PFN_vkCreateCommandPool createCommandPool = nullptr;
        PFN_vkDestroyCommandPool destroyCommandPool = nullptr;
        PFN_vkAllocateCommandBuffers allocateCommandBuffers = nullptr;
        PFN_vkBeginCommandBuffer beginCommandBuffer = nullptr;
        PFN_vkEndCommandBuffer endCommandBuffer = nullptr;
        PFN_vkResetCommandBuffer resetCommandBuffer = nullptr;
        PFN_vkCmdPipelineBarrier cmdPipelineBarrier = nullptr;
        PFN_vkCmdBlitImage cmdBlitImage = nullptr;
        PFN_vkCmdClearColorImage cmdClearColorImage = nullptr;
        PFN_vkCreateFence createFence = nullptr;
        PFN_vkDestroyFence destroyFence = nullptr;
        PFN_vkWaitForFences waitForFences = nullptr;
        PFN_vkResetFences resetFences = nullptr;
        PFN_vkQueueSubmit queueSubmit = nullptr;
        PFN_vkCreateImage createImage = nullptr;
        PFN_vkDestroyImage destroyImage = nullptr;
        PFN_vkAllocateMemory allocateMemory = nullptr;
        PFN_vkFreeMemory freeMemory = nullptr;
        PFN_vkBindImageMemory bindImageMemory = nullptr;
        PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAndroidHardwareBufferProperties = nullptr;
        PFN_vkCreateAndroidSurfaceKHR createAndroidSurface = nullptr;
        PFN_vkDestroySurfaceKHR destroySurface = nullptr;
    };

    struct PendingImage {
        VkImage image = VK_NULL_HANDLE;
        VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
        VkImageSubresourceRange range {};
        uint32_t srcQueueFamily = VK_QUEUE_FAMILY_IGNORED;
        std::vector<VkSemaphore> waitSemaphores;
    };

    bool loadLoader();
    void* openCustomDriver() const;
    bool loadInstanceFunctions();
    bool loadDeviceFunctions();
    bool createInstance();
    std::vector<const char*> surfaceInstanceExtensions() const;
    void createPresentationSurface();
    void destroyPresentationSurface();
    bool pickPhysicalDevice();
    bool createDevice();
    bool coreCreatesDevice() const;
    bool createDeviceThroughCore();
    bool createDeviceWithoutNegotiation();
    bool createFrameResources();
    void clearFrameImage();
    void destroyFrameBuffer();
    std::vector<const char*> requiredDeviceExtensions() const;
    uint32_t requestedApiVersion() const;

    static VkInstance createInstanceWrapper(void* opaque, const VkInstanceCreateInfo* createInfo);
    static VkDevice createDeviceWrapper(VkPhysicalDevice gpu, void* opaque, const VkDeviceCreateInfo* createInfo);
    static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL coreGetInstanceProcAddr(VkInstance instance, const char* name);
    static VKAPI_ATTR VkResult VKAPI_CALL coreCreateDevice(VkPhysicalDevice gpu, const VkDeviceCreateInfo* createInfo,
                                                           const VkAllocationCallbacks* allocator, VkDevice* created);

    static void callbackSetImage(void* handle, const retro_vulkan_image* image, uint32_t numSemaphores,
                                 const VkSemaphore* semaphores, uint32_t srcQueueFamily);
    static uint32_t callbackGetSyncIndex(void* handle);
    static uint32_t callbackGetSyncIndexMask(void* handle);
    static void callbackSetCommandBuffers(void* handle, uint32_t numCommandBuffers, const VkCommandBuffer* commandBuffers);
    static void callbackWaitSyncIndex(void* handle);
    static void callbackLockQueue(void* handle);
    static void callbackUnlockQueue(void* handle);
    static void callbackSetSignalSemaphore(void* handle, VkSemaphore semaphore);

    void waitForFrame();

    void* loaderHandle = nullptr;
    bool preferVulkan = false;
    DriverConfig driverConfig;
    Dispatch vk;
    const retro_hw_render_context_negotiation_interface_vulkan* negotiation = nullptr;
    bool negotiatedDevice = false;
    int availability = -1;

    VkInstance instance = VK_NULL_HANDLE;
    bool surfaceExtensionsEnabled = false;
    AImageReader* surfaceReader = nullptr;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkPhysicalDevice gpu = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;

    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    VkFence frameFence = VK_NULL_HANDLE;
    bool frameInFlight = false;

    AHardwareBuffer* frameBuffer = nullptr;
    VkImage frameImage = VK_NULL_HANDLE;
    VkDeviceMemory frameMemory = VK_NULL_HANDLE;
    unsigned frameWidth = 0;
    unsigned frameHeight = 0;

    PendingImage pendingImage;
    std::vector<VkCommandBuffer> pendingCommandBuffers;
    VkSemaphore pendingSignalSemaphore = VK_NULL_HANDLE;

    std::mutex queueMutex;
    retro_hw_render_interface_vulkan renderInterface {};
};

}

#endif //LIBRETRODROID_VULKANBACKEND_H
