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

#include "vulkanbackend.h"

#include <dlfcn.h>
#include <media/NdkImageReader.h>
#include <algorithm>
#include <cstring>

#include "../log.h"

#ifdef LIBRETRODROID_ADRENOTOOLS
#include <adrenotools/driver.h>
#endif

namespace libretrodroid {

namespace {

constexpr uint32_t MINIMUM_API_VERSION = VK_API_VERSION_1_1;
constexpr uint64_t FENCE_TIMEOUT_NS = 5'000'000'000ULL;

const char* const FRAME_EXPORT_EXTENSIONS[] = {
    VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
    VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
};

const char* const SURFACE_INSTANCE_EXTENSIONS[] = {
    VK_KHR_SURFACE_EXTENSION_NAME,
    VK_KHR_ANDROID_SURFACE_EXTENSION_NAME,
};

constexpr int32_t SURFACE_WIDTH = 640;
constexpr int32_t SURFACE_HEIGHT = 480;
constexpr int32_t SURFACE_MAX_IMAGES = 2;

bool hasExtension(const std::vector<VkExtensionProperties>& available, const char* name) {
    return std::any_of(available.begin(), available.end(), [name](const VkExtensionProperties& ext) {
        return strcmp(ext.extensionName, name) == 0;
    });
}

std::vector<const char*> withExtensions(const char* const* names, uint32_t count,
                                        const std::vector<const char*>& extra) {
    std::vector<const char*> merged(names, names + count);
    for (const char* name : extra) {
        bool present = std::any_of(merged.begin(), merged.end(), [name](const char* existing) {
            return strcmp(existing, name) == 0;
        });
        if (!present) merged.push_back(name);
    }
    return merged;
}

}

void VulkanBackend::configure(bool prefer, const DriverConfig& driver) {
    preferVulkan = prefer;
    if (driver == driverConfig || isStarted()) return;

    driverConfig = driver;
    availability = -1;
    vk = Dispatch {};
    if (loaderHandle != nullptr) {
        dlclose(loaderHandle);
        loaderHandle = nullptr;
    }
}

void* VulkanBackend::openCustomDriver() const {
#ifdef LIBRETRODROID_ADRENOTOOLS
    auto withSlash = [](const std::string& path) { return path.back() == '/' ? path : path + "/"; };
    const std::string driverDirectory = withSlash(driverConfig.directory);
    const std::string hookDirectory = withSlash(driverConfig.hookDirectory);
    const std::string scratchDirectory = driverConfig.scratchDirectory.empty()
        ? std::string()
        : withSlash(driverConfig.scratchDirectory);

    void* handle = adrenotools_open_libvulkan(
        RTLD_NOW | RTLD_LOCAL,
        ADRENOTOOLS_DRIVER_CUSTOM,
        scratchDirectory.empty() ? nullptr : scratchDirectory.c_str(),
        hookDirectory.c_str(),
        driverDirectory.c_str(),
        driverConfig.library.c_str(),
        nullptr,
        nullptr
    );
    if (handle == nullptr) {
        LOGW("Custom Vulkan driver %s%s could not be loaded; using the system driver",
             driverDirectory.c_str(), driverConfig.library.c_str());
    } else {
        LOGI("Custom Vulkan driver loaded: %s%s", driverDirectory.c_str(), driverConfig.library.c_str());
    }
    return handle;
#else
    LOGW("Custom Vulkan drivers are only supported on arm64; using the system driver");
    return nullptr;
#endif
}

bool VulkanBackend::loadLoader() {
    if (vk.getInstanceProcAddr != nullptr) return true;

    if (driverConfig.isCustom() && !driverConfig.hookDirectory.empty()) {
        loaderHandle = openCustomDriver();
    }
    if (loaderHandle == nullptr) {
        loaderHandle = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    }
    if (loaderHandle == nullptr) {
        LOGW("Vulkan loader unavailable: %s", dlerror());
        return false;
    }

    vk.getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(
        dlsym(loaderHandle, "vkGetInstanceProcAddr"));
    if (vk.getInstanceProcAddr == nullptr) {
        LOGW("Vulkan loader has no vkGetInstanceProcAddr");
        return false;
    }

    vk.enumerateInstanceVersion = reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
        vk.getInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion"));
    vk.createInstance = reinterpret_cast<PFN_vkCreateInstance>(
        vk.getInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance"));
    return vk.createInstance != nullptr;
}

bool VulkanBackend::isAvailable() {
    if (availability >= 0) return availability == 1;
    availability = 0;

    if (!loadLoader()) return false;

    uint32_t loaderVersion = VK_API_VERSION_1_0;
    if (vk.enumerateInstanceVersion != nullptr) {
        vk.enumerateInstanceVersion(&loaderVersion);
    }
    if (loaderVersion < MINIMUM_API_VERSION) {
        LOGW("Vulkan loader version %u.%u is below 1.1", VK_API_VERSION_MAJOR(loaderVersion),
             VK_API_VERSION_MINOR(loaderVersion));
        return false;
    }

    VkApplicationInfo app { VK_STRUCTURE_TYPE_APPLICATION_INFO };
    app.pApplicationName = "LibretroDroid";
    app.apiVersion = MINIMUM_API_VERSION;
    VkInstanceCreateInfo info { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    info.pApplicationInfo = &app;

    VkInstance probe = VK_NULL_HANDLE;
    if (vk.createInstance(&info, nullptr, &probe) != VK_SUCCESS) {
        LOGW("Vulkan probe instance could not be created");
        return false;
    }

    auto destroy = reinterpret_cast<PFN_vkDestroyInstance>(vk.getInstanceProcAddr(probe, "vkDestroyInstance"));
    auto enumerate = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(
        vk.getInstanceProcAddr(probe, "vkEnumeratePhysicalDevices"));
    auto extensions = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(
        vk.getInstanceProcAddr(probe, "vkEnumerateDeviceExtensionProperties"));

    uint32_t count = 1;
    VkPhysicalDevice device = VK_NULL_HANDLE;
    VkResult enumerated = enumerate(probe, &count, &device);
    bool supported = (enumerated == VK_SUCCESS || enumerated == VK_INCOMPLETE) && count > 0;

    if (supported) {
        uint32_t extensionCount = 0;
        extensions(device, nullptr, &extensionCount, nullptr);
        std::vector<VkExtensionProperties> available(extensionCount);
        extensions(device, nullptr, &extensionCount, available.data());
        for (const char* name : FRAME_EXPORT_EXTENSIONS) {
            if (!hasExtension(available, name)) {
                LOGW("Vulkan device lacks %s", name);
                supported = false;
            }
        }
    }

    destroy(probe, nullptr);
    availability = supported ? 1 : 0;
    LOGI("Vulkan HW rendering %s", supported ? "available" : "unavailable");
    return supported;
}

void VulkanBackend::setNegotiationInterface(
    const retro_hw_render_context_negotiation_interface_vulkan* received
) {
    negotiation = received;
}

uint32_t VulkanBackend::requestedApiVersion() const {
    uint32_t version = MINIMUM_API_VERSION;
    if (negotiation != nullptr && negotiation->get_application_info != nullptr) {
        const VkApplicationInfo* app = negotiation->get_application_info();
        if (app != nullptr) version = std::max(version, app->apiVersion);
    }
    return version;
}

std::vector<const char*> VulkanBackend::requiredDeviceExtensions() const {
    std::vector<const char*> extensions { std::begin(FRAME_EXPORT_EXTENSIONS), std::end(FRAME_EXPORT_EXTENSIONS) };
    if (surface != VK_NULL_HANDLE) extensions.push_back(VK_KHR_SWAPCHAIN_EXTENSION_NAME);
    return extensions;
}

std::vector<const char*> VulkanBackend::surfaceInstanceExtensions() const {
    auto enumerate = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
        vk.getInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceExtensionProperties"));
    if (enumerate == nullptr) return {};

    uint32_t count = 0;
    enumerate(nullptr, &count, nullptr);
    std::vector<VkExtensionProperties> available(count);
    enumerate(nullptr, &count, available.data());

    std::vector<const char*> extensions;
    for (const char* name : SURFACE_INSTANCE_EXTENSIONS) {
        if (!hasExtension(available, name)) {
            LOGW("Vulkan instance lacks %s; cores get no presentation surface", name);
            return {};
        }
        extensions.push_back(name);
    }
    return extensions;
}

VkInstance VulkanBackend::createInstanceWrapper(void* opaque, const VkInstanceCreateInfo* createInfo) {
    auto* self = static_cast<VulkanBackend*>(opaque);
    VkInstanceCreateInfo info = *createInfo;
    VkApplicationInfo app { VK_STRUCTURE_TYPE_APPLICATION_INFO };
    if (info.pApplicationInfo != nullptr) app = *info.pApplicationInfo;
    app.apiVersion = std::max(app.apiVersion, MINIMUM_API_VERSION);
    info.pApplicationInfo = &app;

    const auto surfaceExtensions = self->surfaceInstanceExtensions();
    const auto extensions = withExtensions(info.ppEnabledExtensionNames, info.enabledExtensionCount, surfaceExtensions);
    info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    info.ppEnabledExtensionNames = extensions.data();

    VkInstance created = VK_NULL_HANDLE;
    if (self->vk.createInstance(&info, nullptr, &created) != VK_SUCCESS) {
        LOGE("vkCreateInstance failed in the core's create_instance");
        return VK_NULL_HANDLE;
    }
    self->surfaceExtensionsEnabled = !surfaceExtensions.empty();
    return created;
}

bool VulkanBackend::createInstance() {
    if (negotiation != nullptr && negotiation->interface_version >= 2 && negotiation->create_instance != nullptr) {
        const VkApplicationInfo* app = negotiation->get_application_info != nullptr
            ? negotiation->get_application_info()
            : nullptr;
        instance = negotiation->create_instance(vk.getInstanceProcAddr, app, &createInstanceWrapper, this);
        if (instance != VK_NULL_HANDLE) return true;
        LOGW("Core create_instance failed; creating the instance in the frontend");
    }

    VkApplicationInfo app { VK_STRUCTURE_TYPE_APPLICATION_INFO };
    if (negotiation != nullptr && negotiation->get_application_info != nullptr) {
        const VkApplicationInfo* coreApp = negotiation->get_application_info();
        if (coreApp != nullptr) app = *coreApp;
    }
    if (app.pApplicationName == nullptr) app.pApplicationName = "LibretroDroid";
    app.apiVersion = requestedApiVersion();

    const auto surfaceExtensions = surfaceInstanceExtensions();
    VkInstanceCreateInfo info { VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
    info.pApplicationInfo = &app;
    info.enabledExtensionCount = static_cast<uint32_t>(surfaceExtensions.size());
    info.ppEnabledExtensionNames = surfaceExtensions.data();
    if (vk.createInstance(&info, nullptr, &instance) != VK_SUCCESS) {
        LOGE("vkCreateInstance failed");
        instance = VK_NULL_HANDLE;
        return false;
    }
    surfaceExtensionsEnabled = !surfaceExtensions.empty();
    return true;
}

void VulkanBackend::createPresentationSurface() {
    if (!surfaceExtensionsEnabled) return;

    vk.createAndroidSurface = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(
        vk.getInstanceProcAddr(instance, "vkCreateAndroidSurfaceKHR"));
    vk.destroySurface = reinterpret_cast<PFN_vkDestroySurfaceKHR>(
        vk.getInstanceProcAddr(instance, "vkDestroySurfaceKHR"));
    if (vk.createAndroidSurface == nullptr || vk.destroySurface == nullptr) return;

    if (AImageReader_new(SURFACE_WIDTH, SURFACE_HEIGHT, AIMAGE_FORMAT_RGBA_8888, SURFACE_MAX_IMAGES,
                         &surfaceReader) != AMEDIA_OK) {
        LOGW("Offscreen window for the Vulkan presentation surface could not be created");
        surfaceReader = nullptr;
        return;
    }

    ANativeWindow* window = nullptr;
    if (AImageReader_getWindow(surfaceReader, &window) != AMEDIA_OK || window == nullptr) {
        destroyPresentationSurface();
        return;
    }

    VkAndroidSurfaceCreateInfoKHR info { VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR };
    info.window = window;
    if (vk.createAndroidSurface(instance, &info, nullptr, &surface) != VK_SUCCESS) {
        LOGW("vkCreateAndroidSurfaceKHR failed; cores get no presentation surface");
        surface = VK_NULL_HANDLE;
        destroyPresentationSurface();
        return;
    }
    LOGI("Vulkan presentation surface ready");
}

void VulkanBackend::destroyPresentationSurface() {
    if (surface != VK_NULL_HANDLE && vk.destroySurface != nullptr) {
        vk.destroySurface(instance, surface, nullptr);
    }
    surface = VK_NULL_HANDLE;
    if (surfaceReader != nullptr) {
        AImageReader_delete(surfaceReader);
        surfaceReader = nullptr;
    }
}

bool VulkanBackend::loadInstanceFunctions() {
    auto load = [this](const char* name) { return vk.getInstanceProcAddr(instance, name); };
    vk.destroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(load("vkDestroyInstance"));
    vk.enumeratePhysicalDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(load("vkEnumeratePhysicalDevices"));
    vk.getPhysicalDeviceProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(
        load("vkGetPhysicalDeviceProperties"));
    vk.getPhysicalDeviceQueueFamilyProperties = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(
        load("vkGetPhysicalDeviceQueueFamilyProperties"));
    vk.enumerateDeviceExtensionProperties = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(
        load("vkEnumerateDeviceExtensionProperties"));
    vk.getPhysicalDeviceFeatures = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures>(
        load("vkGetPhysicalDeviceFeatures"));
    vk.createDevice = reinterpret_cast<PFN_vkCreateDevice>(load("vkCreateDevice"));
    vk.getDeviceProcAddr = reinterpret_cast<PFN_vkGetDeviceProcAddr>(load("vkGetDeviceProcAddr"));
    return vk.destroyInstance && vk.enumeratePhysicalDevices && vk.createDevice && vk.getDeviceProcAddr;
}

bool VulkanBackend::pickPhysicalDevice() {
    uint32_t count = 0;
    vk.enumeratePhysicalDevices(instance, &count, nullptr);
    if (count == 0) return false;
    std::vector<VkPhysicalDevice> devices(count);
    vk.enumeratePhysicalDevices(instance, &count, devices.data());
    gpu = devices.front();

    VkPhysicalDeviceProperties properties {};
    vk.getPhysicalDeviceProperties(gpu, &properties);
    LOGI("Vulkan device: %s (api %u.%u.%u, driver %u)", properties.deviceName,
         VK_API_VERSION_MAJOR(properties.apiVersion), VK_API_VERSION_MINOR(properties.apiVersion),
         VK_API_VERSION_PATCH(properties.apiVersion), properties.driverVersion);
    return true;
}

VkDevice VulkanBackend::createDeviceWrapper(VkPhysicalDevice physicalDevice, void* opaque,
                                            const VkDeviceCreateInfo* createInfo) {
    auto* self = static_cast<VulkanBackend*>(opaque);
    const auto extensions = withExtensions(createInfo->ppEnabledExtensionNames, createInfo->enabledExtensionCount,
                                           self->requiredDeviceExtensions());

    VkDeviceCreateInfo info = *createInfo;
    info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    info.ppEnabledExtensionNames = extensions.data();

    VkDevice created = VK_NULL_HANDLE;
    if (self->vk.createDevice(physicalDevice, &info, nullptr, &created) != VK_SUCCESS) {
        LOGE("vkCreateDevice failed in the core's create_device2");
        return VK_NULL_HANDLE;
    }
    return created;
}

bool VulkanBackend::createDeviceWithoutNegotiation() {
    uint32_t familyCount = 0;
    vk.getPhysicalDeviceQueueFamilyProperties(gpu, &familyCount, nullptr);
    std::vector<VkQueueFamilyProperties> families(familyCount);
    vk.getPhysicalDeviceQueueFamilyProperties(gpu, &familyCount, families.data());

    const VkQueueFlags wanted = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT;
    auto found = std::find_if(families.begin(), families.end(), [wanted](const VkQueueFamilyProperties& family) {
        return (family.queueFlags & wanted) == wanted;
    });
    if (found == families.end()) {
        LOGE("No Vulkan queue family supports graphics and compute");
        return false;
    }
    queueFamily = static_cast<uint32_t>(std::distance(families.begin(), found));

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo { VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
    queueInfo.queueFamilyIndex = queueFamily;
    queueInfo.queueCount = 1;
    queueInfo.pQueuePriorities = &priority;

    VkPhysicalDeviceFeatures features {};
    vk.getPhysicalDeviceFeatures(gpu, &features);
    features.robustBufferAccess = VK_FALSE;

    auto extensions = requiredDeviceExtensions();
    VkDeviceCreateInfo info { VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
    info.queueCreateInfoCount = 1;
    info.pQueueCreateInfos = &queueInfo;
    info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    info.ppEnabledExtensionNames = extensions.data();
    info.pEnabledFeatures = &features;

    if (vk.createDevice(gpu, &info, nullptr, &device) != VK_SUCCESS) {
        LOGE("vkCreateDevice failed");
        device = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL VulkanBackend::coreGetInstanceProcAddr(VkInstance instance, const char* name) {
    if (strcmp(name, "vkCreateDevice") == 0) {
        return reinterpret_cast<PFN_vkVoidFunction>(&coreCreateDevice);
    }
    return getInstance().vk.getInstanceProcAddr(instance, name);
}

VKAPI_ATTR VkResult VKAPI_CALL VulkanBackend::coreCreateDevice(VkPhysicalDevice physicalDevice,
                                                               const VkDeviceCreateInfo* createInfo,
                                                               const VkAllocationCallbacks* allocator,
                                                               VkDevice* created) {
    auto& self = getInstance();
    const auto extensions = withExtensions(createInfo->ppEnabledExtensionNames, createInfo->enabledExtensionCount,
                                           self.requiredDeviceExtensions());
    VkDeviceCreateInfo info = *createInfo;
    info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    info.ppEnabledExtensionNames = extensions.data();
    return self.vk.createDevice(physicalDevice, &info, allocator, created);
}

bool VulkanBackend::coreCreatesDevice() const {
    return negotiation != nullptr &&
           ((negotiation->interface_version >= 2 && negotiation->create_device2 != nullptr) ||
            negotiation->create_device != nullptr);
}

bool VulkanBackend::createDeviceThroughCore() {
    retro_vulkan_context context {};

    if (negotiation->interface_version >= 2 && negotiation->create_device2 != nullptr) {
        negotiatedDevice = negotiation->create_device2(
            &context, instance, gpu, surface, vk.getInstanceProcAddr, &createDeviceWrapper, this);
        if (!negotiatedDevice) {
            negotiatedDevice = negotiation->create_device2(
                &context, instance, VK_NULL_HANDLE, surface, vk.getInstanceProcAddr,
                &createDeviceWrapper, this);
        }
    } else {
        auto extensions = requiredDeviceExtensions();
        VkPhysicalDeviceFeatures requiredFeatures {};
        negotiatedDevice = negotiation->create_device(
            &context, instance, gpu, surface, &coreGetInstanceProcAddr,
            extensions.data(), static_cast<unsigned>(extensions.size()), nullptr, 0, &requiredFeatures);
    }

    if (!negotiatedDevice) return false;
    gpu = context.gpu;
    device = context.device;
    queue = context.queue;
    queueFamily = context.queue_family_index;
    return device != VK_NULL_HANDLE && queue != VK_NULL_HANDLE;
}

bool VulkanBackend::createDevice() {
    if (coreCreatesDevice()) {
        if (createDeviceThroughCore()) return true;
        if (surface != VK_NULL_HANDLE) {
            LOGW("The core could not create a Vulkan device for the presentation surface; retrying without it");
            destroyPresentationSurface();
            if (createDeviceThroughCore()) return true;
        }
        LOGE("The core could not create its Vulkan device");
        return false;
    }

    if (!createDeviceWithoutNegotiation()) return false;
    vk.getDeviceQueue = reinterpret_cast<PFN_vkGetDeviceQueue>(vk.getDeviceProcAddr(device, "vkGetDeviceQueue"));
    vk.getDeviceQueue(device, queueFamily, 0, &queue);
    return queue != VK_NULL_HANDLE;
}

bool VulkanBackend::loadDeviceFunctions() {
    auto load = [this](const char* name) { return vk.getDeviceProcAddr(device, name); };
    vk.destroyDevice = reinterpret_cast<PFN_vkDestroyDevice>(load("vkDestroyDevice"));
    vk.getDeviceQueue = reinterpret_cast<PFN_vkGetDeviceQueue>(load("vkGetDeviceQueue"));
    vk.deviceWaitIdle = reinterpret_cast<PFN_vkDeviceWaitIdle>(load("vkDeviceWaitIdle"));
    vk.createCommandPool = reinterpret_cast<PFN_vkCreateCommandPool>(load("vkCreateCommandPool"));
    vk.destroyCommandPool = reinterpret_cast<PFN_vkDestroyCommandPool>(load("vkDestroyCommandPool"));
    vk.allocateCommandBuffers = reinterpret_cast<PFN_vkAllocateCommandBuffers>(load("vkAllocateCommandBuffers"));
    vk.beginCommandBuffer = reinterpret_cast<PFN_vkBeginCommandBuffer>(load("vkBeginCommandBuffer"));
    vk.endCommandBuffer = reinterpret_cast<PFN_vkEndCommandBuffer>(load("vkEndCommandBuffer"));
    vk.resetCommandBuffer = reinterpret_cast<PFN_vkResetCommandBuffer>(load("vkResetCommandBuffer"));
    vk.cmdPipelineBarrier = reinterpret_cast<PFN_vkCmdPipelineBarrier>(load("vkCmdPipelineBarrier"));
    vk.cmdBlitImage = reinterpret_cast<PFN_vkCmdBlitImage>(load("vkCmdBlitImage"));
    vk.cmdClearColorImage = reinterpret_cast<PFN_vkCmdClearColorImage>(load("vkCmdClearColorImage"));
    vk.createFence = reinterpret_cast<PFN_vkCreateFence>(load("vkCreateFence"));
    vk.destroyFence = reinterpret_cast<PFN_vkDestroyFence>(load("vkDestroyFence"));
    vk.waitForFences = reinterpret_cast<PFN_vkWaitForFences>(load("vkWaitForFences"));
    vk.resetFences = reinterpret_cast<PFN_vkResetFences>(load("vkResetFences"));
    vk.queueSubmit = reinterpret_cast<PFN_vkQueueSubmit>(load("vkQueueSubmit"));
    vk.createImage = reinterpret_cast<PFN_vkCreateImage>(load("vkCreateImage"));
    vk.destroyImage = reinterpret_cast<PFN_vkDestroyImage>(load("vkDestroyImage"));
    vk.allocateMemory = reinterpret_cast<PFN_vkAllocateMemory>(load("vkAllocateMemory"));
    vk.freeMemory = reinterpret_cast<PFN_vkFreeMemory>(load("vkFreeMemory"));
    vk.bindImageMemory = reinterpret_cast<PFN_vkBindImageMemory>(load("vkBindImageMemory"));
    vk.getAndroidHardwareBufferProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
        load("vkGetAndroidHardwareBufferPropertiesANDROID"));

    return vk.destroyDevice && vk.queueSubmit && vk.cmdBlitImage && vk.cmdClearColorImage && vk.createImage &&
           vk.getAndroidHardwareBufferProperties;
}

bool VulkanBackend::createFrameResources() {
    VkCommandPoolCreateInfo poolInfo { VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = queueFamily;
    if (vk.createCommandPool(device, &poolInfo, nullptr, &commandPool) != VK_SUCCESS) return false;

    VkCommandBufferAllocateInfo allocInfo { VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
    allocInfo.commandPool = commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    if (vk.allocateCommandBuffers(device, &allocInfo, &commandBuffer) != VK_SUCCESS) return false;

    VkFenceCreateInfo fenceInfo { VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    return vk.createFence(device, &fenceInfo, nullptr, &frameFence) == VK_SUCCESS;
}

bool VulkanBackend::start() {
    if (isStarted()) return true;
    if (!isAvailable()) return false;

    if (!createInstance() || !loadInstanceFunctions()) {
        LOGE("Vulkan context could not be created");
        shutdown();
        return false;
    }
    createPresentationSurface();
    if (!pickPhysicalDevice() || !createDevice() || !loadDeviceFunctions() || !createFrameResources()) {
        LOGE("Vulkan context could not be created");
        shutdown();
        return false;
    }

    renderInterface = {};
    renderInterface.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    renderInterface.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    renderInterface.handle = this;
    renderInterface.instance = instance;
    renderInterface.gpu = gpu;
    renderInterface.device = device;
    renderInterface.get_device_proc_addr = vk.getDeviceProcAddr;
    renderInterface.get_instance_proc_addr = vk.getInstanceProcAddr;
    renderInterface.queue = queue;
    renderInterface.queue_index = queueFamily;
    renderInterface.set_image = &callbackSetImage;
    renderInterface.get_sync_index = &callbackGetSyncIndex;
    renderInterface.get_sync_index_mask = &callbackGetSyncIndexMask;
    renderInterface.set_command_buffers = &callbackSetCommandBuffers;
    renderInterface.wait_sync_index = &callbackWaitSyncIndex;
    renderInterface.lock_queue = &callbackLockQueue;
    renderInterface.unlock_queue = &callbackUnlockQueue;
    renderInterface.set_signal_semaphore = &callbackSetSignalSemaphore;

    LOGI("Vulkan context ready (negotiated=%d, queue family %u)", negotiatedDevice, queueFamily);
    return true;
}

const retro_hw_render_interface_vulkan* VulkanBackend::getRenderInterface() const {
    return isStarted() ? &renderInterface : nullptr;
}

void VulkanBackend::destroyFrameBuffer() {
    if (device != VK_NULL_HANDLE) {
        waitForFrame();
        if (frameImage != VK_NULL_HANDLE) vk.destroyImage(device, frameImage, nullptr);
        if (frameMemory != VK_NULL_HANDLE) vk.freeMemory(device, frameMemory, nullptr);
    }
    frameImage = VK_NULL_HANDLE;
    frameMemory = VK_NULL_HANDLE;
    if (frameBuffer != nullptr) {
        AHardwareBuffer_release(frameBuffer);
        frameBuffer = nullptr;
    }
    frameWidth = 0;
    frameHeight = 0;
}

bool VulkanBackend::ensureFrameBuffer(unsigned width, unsigned height) {
    if (!isStarted() || width == 0 || height == 0) return false;
    if (frameBuffer != nullptr && width == frameWidth && height == frameHeight) return true;

    destroyFrameBuffer();

    AHardwareBuffer_Desc desc {};
    desc.width = width;
    desc.height = height;
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (AHardwareBuffer_allocate(&desc, &frameBuffer) != 0) {
        LOGE("AHardwareBuffer allocation failed for %ux%u", width, height);
        frameBuffer = nullptr;
        return false;
    }

    VkAndroidHardwareBufferPropertiesANDROID properties { VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID };
    if (vk.getAndroidHardwareBufferProperties(device, frameBuffer, &properties) != VK_SUCCESS) {
        LOGE("vkGetAndroidHardwareBufferPropertiesANDROID failed");
        destroyFrameBuffer();
        return false;
    }

    VkExternalMemoryImageCreateInfo externalInfo { VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO };
    externalInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo imageInfo { VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
    imageInfo.pNext = &externalInfo;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R8G8B8A8_UNORM;
    imageInfo.extent = { width, height, 1 };
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (vk.createImage(device, &imageInfo, nullptr, &frameImage) != VK_SUCCESS) {
        LOGE("vkCreateImage failed for the shared frame image");
        destroyFrameBuffer();
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo { VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID };
    importInfo.buffer = frameBuffer;
    VkMemoryDedicatedAllocateInfo dedicatedInfo { VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO };
    dedicatedInfo.pNext = &importInfo;
    dedicatedInfo.image = frameImage;

    VkMemoryAllocateInfo allocInfo { VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO };
    allocInfo.pNext = &dedicatedInfo;
    allocInfo.allocationSize = properties.allocationSize;
    allocInfo.memoryTypeIndex = static_cast<uint32_t>(__builtin_ctz(properties.memoryTypeBits));
    if (vk.allocateMemory(device, &allocInfo, nullptr, &frameMemory) != VK_SUCCESS ||
        vk.bindImageMemory(device, frameImage, frameMemory, 0) != VK_SUCCESS) {
        LOGE("Importing the shared frame buffer into Vulkan failed");
        destroyFrameBuffer();
        return false;
    }

    frameWidth = width;
    frameHeight = height;
    clearFrameImage();
    LOGI("Vulkan shared frame buffer ready: %ux%u", width, height);
    return true;
}

void VulkanBackend::clearFrameImage() {
    waitForFrame();

    vk.resetCommandBuffer(commandBuffer, 0);
    VkCommandBufferBeginInfo begin { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk.beginCommandBuffer(commandBuffer, &begin);

    const VkImageSubresourceRange range { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };

    VkImageMemoryBarrier toTransfer { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toTransfer.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.image = frameImage;
    toTransfer.subresourceRange = range;
    vk.cmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          0, 0, nullptr, 0, nullptr, 1, &toTransfer);

    const VkClearColorValue black {};
    vk.cmdClearColorImage(commandBuffer, frameImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &black, 1, &range);

    VkImageMemoryBarrier release { VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
    release.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    release.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    release.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    release.srcQueueFamilyIndex = queueFamily;
    release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    release.image = frameImage;
    release.subresourceRange = range;
    vk.cmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                          0, 0, nullptr, 0, nullptr, 1, &release);
    vk.endCommandBuffer(commandBuffer);

    VkSubmitInfo submit { VK_STRUCTURE_TYPE_SUBMIT_INFO };
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &commandBuffer;
    VkResult result;
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        result = vk.queueSubmit(queue, 1, &submit, frameFence);
    }
    if (result != VK_SUCCESS) {
        LOGE("Clearing the shared frame buffer failed: %d", result);
        return;
    }
    frameInFlight = true;
    waitForFrame();
}

void VulkanBackend::waitForFrame() {
    if (!frameInFlight) return;
    vk.waitForFences(device, 1, &frameFence, VK_TRUE, FENCE_TIMEOUT_NS);
    vk.resetFences(device, 1, &frameFence);
    frameInFlight = false;
}

bool VulkanBackend::presentFrame(unsigned width, unsigned height) {
    if (!isStarted() || frameImage == VK_NULL_HANDLE || pendingImage.image == VK_NULL_HANDLE) return false;

    waitForFrame();

    const uint32_t copyWidth = std::min(width, frameWidth);
    const uint32_t copyHeight = std::min(height, frameHeight);
    const bool generalLayout = pendingImage.layout == VK_IMAGE_LAYOUT_GENERAL;
    const VkImageLayout sourceLayout = generalLayout ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    const bool transferOwnership = pendingImage.srcQueueFamily != VK_QUEUE_FAMILY_IGNORED &&
                                   pendingImage.srcQueueFamily != queueFamily;

    vk.resetCommandBuffer(commandBuffer, 0);
    VkCommandBufferBeginInfo begin { VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    vk.beginCommandBuffer(commandBuffer, &begin);

    VkImageSubresourceRange sourceRange = pendingImage.range;
    sourceRange.levelCount = 1;
    sourceRange.layerCount = 1;

    VkImageMemoryBarrier acquire[2] {};
    acquire[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    acquire[0].srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    acquire[0].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    acquire[0].oldLayout = pendingImage.layout;
    acquire[0].newLayout = sourceLayout;
    acquire[0].srcQueueFamilyIndex = transferOwnership ? pendingImage.srcQueueFamily : VK_QUEUE_FAMILY_IGNORED;
    acquire[0].dstQueueFamilyIndex = transferOwnership ? queueFamily : VK_QUEUE_FAMILY_IGNORED;
    acquire[0].image = pendingImage.image;
    acquire[0].subresourceRange = sourceRange;

    acquire[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    acquire[1].srcAccessMask = 0;
    acquire[1].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    acquire[1].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    acquire[1].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    acquire[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    acquire[1].dstQueueFamilyIndex = queueFamily;
    acquire[1].image = frameImage;
    acquire[1].subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };

    vk.cmdPipelineBarrier(commandBuffer,
                          VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                          0, 0, nullptr, 0, nullptr, 2, acquire);

    VkImageBlit blit {};
    blit.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, sourceRange.baseMipLevel, sourceRange.baseArrayLayer, 1 };
    blit.srcOffsets[1] = { static_cast<int32_t>(copyWidth), static_cast<int32_t>(copyHeight), 1 };
    blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
    blit.dstOffsets[1] = { static_cast<int32_t>(copyWidth), static_cast<int32_t>(copyHeight), 1 };
    vk.cmdBlitImage(commandBuffer, pendingImage.image, sourceLayout, frameImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_NEAREST);

    VkImageMemoryBarrier release[2] {};
    release[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    release[0].srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    release[0].dstAccessMask = 0;
    release[0].oldLayout = sourceLayout;
    release[0].newLayout = pendingImage.layout;
    release[0].srcQueueFamilyIndex = transferOwnership ? queueFamily : VK_QUEUE_FAMILY_IGNORED;
    release[0].dstQueueFamilyIndex = transferOwnership ? pendingImage.srcQueueFamily : VK_QUEUE_FAMILY_IGNORED;
    release[0].image = pendingImage.image;
    release[0].subresourceRange = sourceRange;

    release[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    release[1].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    release[1].dstAccessMask = 0;
    release[1].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    release[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    release[1].srcQueueFamilyIndex = queueFamily;
    release[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    release[1].image = frameImage;
    release[1].subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };

    vk.cmdPipelineBarrier(commandBuffer,
                          VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                          0, 0, nullptr, 0, nullptr, 2, release);
    vk.endCommandBuffer(commandBuffer);

    std::vector<VkCommandBuffer> submitted = pendingCommandBuffers;
    submitted.push_back(commandBuffer);
    const bool useSemaphores = pendingCommandBuffers.empty();
    std::vector<VkPipelineStageFlags> waitStages(pendingImage.waitSemaphores.size(), VK_PIPELINE_STAGE_TRANSFER_BIT);

    VkSubmitInfo submit { VK_STRUCTURE_TYPE_SUBMIT_INFO };
    if (useSemaphores && !pendingImage.waitSemaphores.empty()) {
        submit.waitSemaphoreCount = static_cast<uint32_t>(pendingImage.waitSemaphores.size());
        submit.pWaitSemaphores = pendingImage.waitSemaphores.data();
        submit.pWaitDstStageMask = waitStages.data();
    }
    submit.commandBufferCount = static_cast<uint32_t>(submitted.size());
    submit.pCommandBuffers = submitted.data();
    if (pendingSignalSemaphore != VK_NULL_HANDLE) {
        submit.signalSemaphoreCount = 1;
        submit.pSignalSemaphores = &pendingSignalSemaphore;
    }

    VkResult result;
    {
        std::lock_guard<std::mutex> lock(queueMutex);
        result = vk.queueSubmit(queue, 1, &submit, frameFence);
    }

    pendingImage.waitSemaphores.clear();
    pendingCommandBuffers.clear();
    pendingSignalSemaphore = VK_NULL_HANDLE;

    if (result != VK_SUCCESS) {
        LOGE("Vulkan frame submit failed: %d", result);
        return false;
    }
    frameInFlight = true;
    waitForFrame();
    return true;
}

void VulkanBackend::callbackSetImage(void* handle, const retro_vulkan_image* image, uint32_t numSemaphores,
                                     const VkSemaphore* semaphores, uint32_t srcQueueFamily) {
    auto* self = static_cast<VulkanBackend*>(handle);
    self->pendingImage.image = image->create_info.image;
    self->pendingImage.layout = image->image_layout;
    self->pendingImage.range = image->create_info.subresourceRange;
    self->pendingImage.srcQueueFamily = srcQueueFamily;
    self->pendingImage.waitSemaphores.assign(semaphores, semaphores + numSemaphores);
}

uint32_t VulkanBackend::callbackGetSyncIndex(void*) {
    return 0;
}

uint32_t VulkanBackend::callbackGetSyncIndexMask(void*) {
    return 1;
}

void VulkanBackend::callbackSetCommandBuffers(void* handle, uint32_t numCommandBuffers,
                                              const VkCommandBuffer* commandBuffers) {
    auto* self = static_cast<VulkanBackend*>(handle);
    self->pendingCommandBuffers.assign(commandBuffers, commandBuffers + numCommandBuffers);
}

void VulkanBackend::callbackWaitSyncIndex(void* handle) {
    static_cast<VulkanBackend*>(handle)->waitForFrame();
}

void VulkanBackend::callbackLockQueue(void* handle) {
    static_cast<VulkanBackend*>(handle)->queueMutex.lock();
}

void VulkanBackend::callbackUnlockQueue(void* handle) {
    static_cast<VulkanBackend*>(handle)->queueMutex.unlock();
}

void VulkanBackend::callbackSetSignalSemaphore(void* handle, VkSemaphore semaphore) {
    static_cast<VulkanBackend*>(handle)->pendingSignalSemaphore = semaphore;
}

void VulkanBackend::destroyCoreDevice() {
    if (device != VK_NULL_HANDLE && vk.deviceWaitIdle != nullptr) {
        vk.deviceWaitIdle(device);
    }
    if (negotiation != nullptr && negotiation->destroy_device != nullptr && negotiatedDevice) {
        negotiation->destroy_device();
    }
}

void VulkanBackend::shutdown() {
    if (device != VK_NULL_HANDLE) {
        if (vk.deviceWaitIdle != nullptr) vk.deviceWaitIdle(device);
        destroyFrameBuffer();
        if (frameFence != VK_NULL_HANDLE) vk.destroyFence(device, frameFence, nullptr);
        if (commandPool != VK_NULL_HANDLE) vk.destroyCommandPool(device, commandPool, nullptr);
        if (vk.destroyDevice != nullptr) vk.destroyDevice(device, nullptr);
    }
    destroyPresentationSurface();
    if (instance != VK_NULL_HANDLE && vk.destroyInstance != nullptr) {
        vk.destroyInstance(instance, nullptr);
    }
    surfaceExtensionsEnabled = false;

    frameFence = VK_NULL_HANDLE;
    commandPool = VK_NULL_HANDLE;
    commandBuffer = VK_NULL_HANDLE;
    frameInFlight = false;
    device = VK_NULL_HANDLE;
    queue = VK_NULL_HANDLE;
    gpu = VK_NULL_HANDLE;
    instance = VK_NULL_HANDLE;
    negotiation = nullptr;
    negotiatedDevice = false;
    pendingImage = PendingImage {};
    pendingCommandBuffers.clear();
    pendingSignalSemaphore = VK_NULL_HANDLE;
    renderInterface = {};
}

}
