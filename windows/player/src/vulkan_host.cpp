/*
 * ShoalcadePlayer: the Vulkan context for Vulkan cores (part of Shoalcade).
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
#include "vulkan_host.h"

#include <algorithm>
#include <cstring>

#include "log.h"

#ifdef _WIN32
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>

#include "vulkan/vulkan_win32.h"
#endif

namespace sc {
#ifdef _WIN32
namespace {
const char *kDeviceExtensions[] = {VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME,
                                   VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME};
constexpr VkExternalMemoryHandleTypeFlagBits kMemHandle = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
constexpr VkExternalSemaphoreHandleTypeFlagBits kSemHandle = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
constexpr VkFormat kTargetFormat = VK_FORMAT_R8G8B8A8_UNORM;

std::string version_text(uint32_t v) {
    return std::to_string(VK_API_VERSION_MAJOR(v)) + "." + std::to_string(VK_API_VERSION_MINOR(v)) + "." +
           std::to_string(VK_API_VERSION_PATCH(v));
}

/** Same bytes per pixel and channel order as our R8G8B8A8 target: a plain copy (no conversion, sRGB bits kept). */
bool copy_compatible(VkFormat f) { return f == VK_FORMAT_R8G8B8A8_UNORM || f == VK_FORMAT_R8G8B8A8_SRGB; }
} // namespace

#define SC_VK_INSTANCE_FUNCS(X)                     \
    X(vkDestroyInstance)                            \
    X(vkEnumeratePhysicalDevices)                   \
    X(vkGetPhysicalDeviceProperties)                \
    X(vkGetPhysicalDeviceProperties2)               \
    X(vkGetPhysicalDeviceQueueFamilyProperties)     \
    X(vkGetPhysicalDeviceMemoryProperties)          \
    X(vkGetPhysicalDeviceFormatProperties)          \
    X(vkGetPhysicalDeviceImageFormatProperties2)    \
    X(vkGetPhysicalDeviceExternalSemaphoreProperties) \
    X(vkEnumerateDeviceExtensionProperties)         \
    X(vkCreateDevice)                               \
    X(vkGetDeviceProcAddr)

#define SC_VK_DEVICE_FUNCS(X)         \
    X(vkDestroyDevice)                \
    X(vkGetDeviceQueue)               \
    X(vkQueueSubmit)                  \
    X(vkQueueWaitIdle)                \
    X(vkCreateCommandPool)            \
    X(vkDestroyCommandPool)           \
    X(vkAllocateCommandBuffers)       \
    X(vkResetCommandBuffer)           \
    X(vkBeginCommandBuffer)           \
    X(vkEndCommandBuffer)             \
    X(vkCmdPipelineBarrier)           \
    X(vkCmdCopyImage)                 \
    X(vkCmdBlitImage)                 \
    X(vkCreateFence)                  \
    X(vkDestroyFence)                 \
    X(vkWaitForFences)                \
    X(vkResetFences)                  \
    X(vkCreateSemaphore)              \
    X(vkDestroySemaphore)             \
    X(vkCreateImage)                  \
    X(vkDestroyImage)                 \
    X(vkGetImageMemoryRequirements)   \
    X(vkAllocateMemory)               \
    X(vkFreeMemory)                   \
    X(vkBindImageMemory)              \
    X(vkGetMemoryWin32HandleKHR)      \
    X(vkGetSemaphoreWin32HandleKHR)

struct VulkanHost::Fn {
    PFN_vkCreateInstance vkCreateInstance = nullptr;
    PFN_vkEnumerateInstanceVersion vkEnumerateInstanceVersion = nullptr;
#define SC_VK_MEMBER(n) PFN_##n n = nullptr;
    SC_VK_INSTANCE_FUNCS(SC_VK_MEMBER)
    SC_VK_DEVICE_FUNCS(SC_VK_MEMBER)
#undef SC_VK_MEMBER
};

void close_shared_handle(void *h) {
    if (h) CloseHandle((HANDLE)h);
}

bool VulkanHost::load_loader(std::string &err) {
    HMODULE m = LoadLibraryW(L"vulkan-1.dll");
    if (!m) return err = "This game needs Vulkan, and Windows has no Vulkan driver here (vulkan-1.dll not found). "
                         "Install your graphics card's latest driver.", false;
    lib_ = m;
    gipa_ = (PFN_vkGetInstanceProcAddr)(void *)GetProcAddress(m, "vkGetInstanceProcAddr");
    if (!gipa_) return err = "vulkan-1.dll has no vkGetInstanceProcAddr", false;
    fn_ = new Fn();
    fn_->vkCreateInstance = (PFN_vkCreateInstance)gipa_(VK_NULL_HANDLE, "vkCreateInstance");
    fn_->vkEnumerateInstanceVersion = (PFN_vkEnumerateInstanceVersion)gipa_(VK_NULL_HANDLE, "vkEnumerateInstanceVersion");
    if (!fn_->vkCreateInstance) return err = "the Vulkan loader has no vkCreateInstance", false;
    return true;
}

VkInstance VulkanHost::create_instance_wrapper(void *opaque, const VkInstanceCreateInfo *info) {
    auto *self = static_cast<VulkanHost *>(opaque);
    VkInstanceCreateInfo ci = *info;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    if (info->pApplicationInfo) app = *info->pApplicationInfo;
    app.apiVersion = std::max(app.apiVersion, self->api_version_); // never below what we need (1.1)
    ci.pApplicationInfo = &app;
    VkInstance inst = VK_NULL_HANDLE;
    VkResult r = self->fn_->vkCreateInstance(&ci, nullptr, &inst);
    if (r != VK_SUCCESS) LOGE("vulkan: the core's instance couldn't be made (VkResult %d)", (int)r);
    return r == VK_SUCCESS ? inst : VK_NULL_HANDLE;
}

VkDevice VulkanHost::create_device_wrapper(VkPhysicalDevice gpu, void *opaque, const VkDeviceCreateInfo *info) {
    auto *self = static_cast<VulkanHost *>(opaque);
    VkDeviceCreateInfo ci = *info;
    std::vector<const char *> exts(info->ppEnabledExtensionNames,
                                   info->ppEnabledExtensionNames + info->enabledExtensionCount);
    for (const char *e : kDeviceExtensions)
        if (std::none_of(exts.begin(), exts.end(), [e](const char *x) { return std::strcmp(x, e) == 0; }))
            exts.push_back(e);
    ci.enabledExtensionCount = (uint32_t)exts.size();
    ci.ppEnabledExtensionNames = exts.data();
    VkDevice dev = VK_NULL_HANDLE;
    VkResult r = self->fn_->vkCreateDevice(gpu, &ci, nullptr, &dev);
    if (r != VK_SUCCESS) LOGE("vulkan: the core's device couldn't be made (VkResult %d)", (int)r);
    return r == VK_SUCCESS ? dev : VK_NULL_HANDLE;
}

bool VulkanHost::create(const retro_hw_render_context_negotiation_interface_vulkan *neg, const GpuId &gl,
                        std::string &err) {
    destroy();
    if (!load_loader(err)) return false;
    if (neg && neg->interface_type != RETRO_HW_RENDER_CONTEXT_NEGOTIATION_INTERFACE_VULKAN) neg = nullptr;
    neg_ = neg;

    uint32_t loader = VK_API_VERSION_1_0;
    if (fn_->vkEnumerateInstanceVersion) fn_->vkEnumerateInstanceVersion(&loader);
    if (loader < VK_API_VERSION_1_1)
        return err = "This game needs Vulkan 1.1; this PC's Vulkan is " + version_text(loader) +
                     ". Install your graphics card's latest driver.", false;

    // The core's application info sets the instance version (Xenia asks for 1.3); never below 1.1.
    static const VkApplicationInfo kDefaultApp{VK_STRUCTURE_TYPE_APPLICATION_INFO, nullptr, "Shoalcade", 0,
                                               "Shoalcade", 0, VK_API_VERSION_1_1};
    const VkApplicationInfo *core_app = neg && neg->get_application_info ? neg->get_application_info() : nullptr;
    VkApplicationInfo app = core_app ? *core_app : kDefaultApp;
    app.apiVersion = std::max(app.apiVersion, (uint32_t)VK_API_VERSION_1_1);
    api_version_ = app.apiVersion;
    if (neg && neg->interface_version >= 2 && neg->create_instance) {
        instance_ = neg->create_instance(gipa_, &app, create_instance_wrapper, this);
    } else {
        VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ci.pApplicationInfo = &app;
        VkResult r = fn_->vkCreateInstance(&ci, nullptr, &instance_);
        if (r != VK_SUCCESS) instance_ = VK_NULL_HANDLE;
    }
    if (!instance_) return err = "Vulkan couldn't start (no instance)", false;
#define SC_VK_LOAD_I(n)                                              \
    fn_->n = (PFN_##n)gipa_(instance_, #n);                          \
    if (!fn_->n) return err = "Vulkan function missing: " #n, false;
    SC_VK_INSTANCE_FUNCS(SC_VK_LOAD_I)
#undef SC_VK_LOAD_I
    if (!pick_gpu(gl, err)) return false;
    if (!make_device(neg, err)) return false;
    if (!load_device_functions(err)) return false;

    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pci.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pci.queueFamilyIndex = family_;
    if (fn_->vkCreateCommandPool(device_, &pci, nullptr, &pool_) != VK_SUCCESS) return err = "Vulkan: no command pool", false;
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ai.commandPool = pool_;
    ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ai.commandBufferCount = kSync;
    if (fn_->vkAllocateCommandBuffers(device_, &ai, cmd_) != VK_SUCCESS) return err = "Vulkan: no command buffers", false;
    for (uint32_t i = 0; i < kSync; i++) {
        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        if (fn_->vkCreateFence(device_, &fci, nullptr, &fence_[i]) != VK_SUCCESS) return err = "Vulkan: no fence", false;
    }

    iface_ = retro_hw_render_interface_vulkan{};
    iface_.interface_type = RETRO_HW_RENDER_INTERFACE_VULKAN;
    iface_.interface_version = RETRO_HW_RENDER_INTERFACE_VULKAN_VERSION;
    iface_.handle = this;
    iface_.instance = instance_;
    iface_.gpu = gpu_;
    iface_.device = device_;
    iface_.get_device_proc_addr = fn_->vkGetDeviceProcAddr;
    iface_.get_instance_proc_addr = gipa_;
    iface_.queue = queue_;
    iface_.queue_index = family_;
    iface_.set_image = set_image_cb;
    iface_.get_sync_index = get_sync_index_cb;
    iface_.get_sync_index_mask = get_sync_index_mask_cb;
    iface_.set_command_buffers = set_command_buffers_cb;
    iface_.wait_sync_index = wait_sync_index_cb;
    iface_.lock_queue = lock_queue_cb;
    iface_.unlock_queue = unlock_queue_cb;
    iface_.set_signal_semaphore = set_signal_semaphore_cb;
    LOGI("Vulkan %s | %s | device made by %s | queue family %u", version_text(api_version_).c_str(), gpu_name_.c_str(),
         core_made_device_ ? "the core" : "the player", family_);
    return true;
}

bool VulkanHost::pick_gpu(const GpuId &gl, std::string &err) {
    uint32_t n = 0;
    fn_->vkEnumeratePhysicalDevices(instance_, &n, nullptr);
    std::vector<VkPhysicalDevice> gpus(n);
    if (n) fn_->vkEnumeratePhysicalDevices(instance_, &n, gpus.data());
    if (gpus.empty()) return err = "This game needs a Vulkan graphics card, and Vulkan found none.", false;
    std::string names;
    for (VkPhysicalDevice g : gpus) {
        VkPhysicalDeviceIDProperties id{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
        VkPhysicalDeviceProperties2 p{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2, &id};
        fn_->vkGetPhysicalDeviceProperties2(g, &p);
        names += std::string(names.empty() ? "" : ", ") + p.properties.deviceName;
        bool same = (gl.has_luid && id.deviceLUIDValid && std::memcmp(gl.luid, id.deviceLUID, 8) == 0) ||
                    (!gl.has_luid && gl.has_uuid && std::memcmp(gl.uuid, id.deviceUUID, 16) == 0);
        if (!same) continue;
        if (p.properties.apiVersion < VK_API_VERSION_1_1)
            return err = std::string("This game needs Vulkan 1.1; ") + p.properties.deviceName + " has " +
                         version_text(p.properties.apiVersion) + ". Install your graphics card's latest driver.", false;
        gpu_ = g;
        gpu_name_ = p.properties.deviceName;
        return true;
    }
    LOGE("vulkan: no Vulkan device is the OpenGL one (Vulkan has: %s)", names.c_str());
    return err = "Shoalcade couldn't find the graphics card OpenGL uses among the Vulkan ones (" + names +
                 "). Set Shoalcade to use one graphics card in Windows' graphics settings.", false;
}

bool VulkanHost::make_device(const retro_hw_render_context_negotiation_interface_vulkan *neg, std::string &err) {
    uint32_t n = 0;
    fn_->vkEnumerateDeviceExtensionProperties(gpu_, nullptr, &n, nullptr);
    std::vector<VkExtensionProperties> have(n);
    if (n) fn_->vkEnumerateDeviceExtensionProperties(gpu_, nullptr, &n, have.data());
    for (const char *e : kDeviceExtensions)
        if (std::none_of(have.begin(), have.end(), [e](const VkExtensionProperties &p) { return std::strcmp(p.extensionName, e) == 0; }))
            return err = "This game needs a graphics driver that can share Vulkan pictures with OpenGL (" +
                         std::string(e) + " missing on " + gpu_name_ + "). Install your graphics card's latest driver.", false;

    retro_vulkan_context ctx{};
    bool made = false;
    if (neg && neg->interface_version >= 2 && neg->create_device2) {
        core_asked_ = true;
        made = neg->create_device2(&ctx, instance_, gpu_, VK_NULL_HANDLE, gipa_, create_device_wrapper, this);
    } else if (neg && neg->create_device) {
        core_asked_ = true;
        static const VkPhysicalDeviceFeatures kNoFeatures{};
        made = neg->create_device(&ctx, instance_, gpu_, VK_NULL_HANDLE, gipa_, kDeviceExtensions,
                                  (unsigned)(sizeof kDeviceExtensions / sizeof *kDeviceExtensions), nullptr, 0,
                                  &kNoFeatures);
    }
    if (made) {
        core_made_device_ = true;
        device_ = ctx.device;
        queue_ = ctx.queue;
        family_ = ctx.queue_family_index;
        if (ctx.gpu != gpu_) return err = "the game made its Vulkan device on another graphics card", false;
        if (!device_ || !queue_) return err = "the game's Vulkan device came back empty", false;
        return true;
    }
    if (neg && (neg->create_device || neg->create_device2)) LOGW("vulkan: the core didn't make the device; the player does");
    return own_device(err);
}

bool VulkanHost::own_device(std::string &err) {
    uint32_t n = 0;
    fn_->vkGetPhysicalDeviceQueueFamilyProperties(gpu_, &n, nullptr);
    std::vector<VkQueueFamilyProperties> fams(n);
    if (n) fn_->vkGetPhysicalDeviceQueueFamilyProperties(gpu_, &n, fams.data());
    uint32_t want = VK_QUEUE_GRAPHICS_BIT | VK_QUEUE_COMPUTE_BIT, f = 0;
    while (f < n && (fams[f].queueFlags & want) != want) f++;
    if (f == n) return err = gpu_name_ + " has no Vulkan queue for graphics and compute", false;
    float priority = 1.0f;
    VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    q.queueFamilyIndex = f;
    q.queueCount = 1;
    q.pQueuePriorities = &priority;
    VkDeviceCreateInfo ci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    ci.queueCreateInfoCount = 1;
    ci.pQueueCreateInfos = &q;
    ci.enabledExtensionCount = (uint32_t)(sizeof kDeviceExtensions / sizeof *kDeviceExtensions);
    ci.ppEnabledExtensionNames = kDeviceExtensions;
    VkResult r = fn_->vkCreateDevice(gpu_, &ci, nullptr, &device_);
    if (r != VK_SUCCESS) {
        device_ = VK_NULL_HANDLE;
        return err = "Vulkan couldn't start on " + gpu_name_ + " (VkResult " + std::to_string((int)r) + ")", false;
    }
    family_ = f;
    auto get_queue = (PFN_vkGetDeviceQueue)fn_->vkGetDeviceProcAddr(device_, "vkGetDeviceQueue");
    if (!get_queue) return err = "Vulkan function missing: vkGetDeviceQueue", false;
    get_queue(device_, f, 0, &queue_);
    return true;
}

bool VulkanHost::load_device_functions(std::string &err) {
#define SC_VK_LOAD_D(n)                                              \
    fn_->n = (PFN_##n)fn_->vkGetDeviceProcAddr(device_, #n);         \
    if (!fn_->n) return err = "Vulkan function missing: " #n, false;
    SC_VK_DEVICE_FUNCS(SC_VK_LOAD_D)
#undef SC_VK_LOAD_D
    return true;
}

bool VulkanHost::wait_fence(uint32_t i) {
    std::lock_guard<std::mutex> g(fence_mutex_);
    if (!fence_pending_[i]) return true;
    if (fn_->vkWaitForFences(device_, 1, &fence_[i], VK_TRUE, 10000000000ull) != VK_SUCCESS) return false;
    fn_->vkResetFences(device_, 1, &fence_[i]);
    fence_pending_[i] = false;
    return true;
}

void VulkanHost::free_target() {
    if (!device_) return;
    if (ready_) fn_->vkDestroySemaphore(device_, ready_, nullptr), ready_ = VK_NULL_HANDLE;
    if (done_) fn_->vkDestroySemaphore(device_, done_, nullptr), done_ = VK_NULL_HANDLE;
    if (target_) fn_->vkDestroyImage(device_, target_, nullptr), target_ = VK_NULL_HANDLE;
    if (target_mem_) fn_->vkFreeMemory(device_, target_mem_, nullptr), target_mem_ = VK_NULL_HANDLE;
    target_w_ = target_h_ = 0;
    target_used_ = false;
}

bool VulkanHost::make_target(unsigned w, unsigned h, SharedTarget &out, std::string &err) {
    out = SharedTarget{};
    {
        std::lock_guard<std::recursive_mutex> q(queue_mutex_);
        fn_->vkQueueWaitIdle(queue_);
    }
    for (uint32_t i = 0; i < kSync; i++) wait_fence(i);
    free_target();

    const VkImageUsageFlags usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                                    VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    VkPhysicalDeviceExternalImageFormatInfo ext_info{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO};
    ext_info.handleType = kMemHandle;
    VkPhysicalDeviceImageFormatInfo2 fmt_info{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2, &ext_info};
    fmt_info.format = kTargetFormat;
    fmt_info.type = VK_IMAGE_TYPE_2D;
    fmt_info.tiling = VK_IMAGE_TILING_OPTIMAL;
    fmt_info.usage = usage;
    VkExternalImageFormatProperties ext_props{VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES};
    VkImageFormatProperties2 fmt_props{VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2, &ext_props};
    if (fn_->vkGetPhysicalDeviceImageFormatProperties2(gpu_, &fmt_info, &fmt_props) != VK_SUCCESS ||
        !(ext_props.externalMemoryProperties.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT))
        return err = "the graphics driver can't share a Vulkan picture with OpenGL (" + gpu_name_ + ")", false;
    VkPhysicalDeviceExternalSemaphoreInfo sem_info{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO};
    sem_info.handleType = kSemHandle;
    VkExternalSemaphoreProperties sem_props{VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES};
    fn_->vkGetPhysicalDeviceExternalSemaphoreProperties(gpu_, &sem_info, &sem_props);
    if (!(sem_props.externalSemaphoreFeatures & VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT))
        return err = "the graphics driver can't share Vulkan semaphores with OpenGL (" + gpu_name_ + ")", false;

    VkExternalMemoryImageCreateInfo emi{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
    emi.handleTypes = kMemHandle;
    VkImageCreateInfo ici{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO, &emi};
    ici.imageType = VK_IMAGE_TYPE_2D;
    ici.format = kTargetFormat;
    ici.extent = {w, h, 1};
    ici.mipLevels = 1;
    ici.arrayLayers = 1;
    ici.samples = VK_SAMPLE_COUNT_1_BIT;
    ici.tiling = VK_IMAGE_TILING_OPTIMAL;
    ici.usage = usage;
    ici.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ici.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (fn_->vkCreateImage(device_, &ici, nullptr, &target_) != VK_SUCCESS) {
        target_ = VK_NULL_HANDLE;
        return err = "Vulkan: the shared picture couldn't be made", false;
    }
    VkMemoryRequirements req;
    fn_->vkGetImageMemoryRequirements(device_, target_, &req);
    VkPhysicalDeviceMemoryProperties mp;
    fn_->vkGetPhysicalDeviceMemoryProperties(gpu_, &mp);
    uint32_t type = UINT32_MAX;
    for (uint32_t i = 0; i < mp.memoryTypeCount && type == UINT32_MAX; i++)
        if ((req.memoryTypeBits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) type = i;
    if (type == UINT32_MAX) return free_target(), err = "Vulkan: no memory for the shared picture", false;
    VkMemoryDedicatedAllocateInfo ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
    ded.image = target_;
    VkExportMemoryAllocateInfo exp{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO, &ded};
    exp.handleTypes = kMemHandle;
    VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO, &exp};
    mai.allocationSize = req.size;
    mai.memoryTypeIndex = type;
    if (fn_->vkAllocateMemory(device_, &mai, nullptr, &target_mem_) != VK_SUCCESS) {
        target_mem_ = VK_NULL_HANDLE;
        free_target();
        return err = "Vulkan: out of graphics memory for the shared picture", false;
    }
    fn_->vkBindImageMemory(device_, target_, target_mem_, 0);
    VkExportSemaphoreCreateInfo esi{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
    esi.handleTypes = kSemHandle;
    VkSemaphoreCreateInfo sci{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO, &esi};
    if (fn_->vkCreateSemaphore(device_, &sci, nullptr, &ready_) != VK_SUCCESS ||
        fn_->vkCreateSemaphore(device_, &sci, nullptr, &done_) != VK_SUCCESS) {
        free_target();
        return err = "Vulkan: the shared semaphores couldn't be made", false;
    }
    VkMemoryGetWin32HandleInfoKHR mh{VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR};
    mh.memory = target_mem_;
    mh.handleType = kMemHandle;
    HANDLE mem = nullptr, rdy = nullptr, dn = nullptr;
    VkSemaphoreGetWin32HandleInfoKHR sh{VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR};
    sh.handleType = kSemHandle;
    bool ok = fn_->vkGetMemoryWin32HandleKHR(device_, &mh, &mem) == VK_SUCCESS;
    sh.semaphore = ready_;
    ok = ok && fn_->vkGetSemaphoreWin32HandleKHR(device_, &sh, &rdy) == VK_SUCCESS;
    sh.semaphore = done_;
    ok = ok && fn_->vkGetSemaphoreWin32HandleKHR(device_, &sh, &dn) == VK_SUCCESS;
    if (!ok) {
        close_shared_handle(mem), close_shared_handle(rdy), close_shared_handle(dn);
        free_target();
        return err = "Vulkan: the shared picture couldn't be handed to OpenGL", false;
    }
    target_w_ = w, target_h_ = h;
    out.memory = mem, out.size = req.size, out.ready = rdy, out.done = dn, out.width = w, out.height = h;
    LOGI("vulkan: shared picture %ux%u (%llu bytes)", w, h, (unsigned long long)req.size);
    return true;
}

void VulkanHost::record_copy(VkCommandBuffer cmd, unsigned w, unsigned h) {
    fn_->vkResetCommandBuffer(cmd, 0);
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    fn_->vkBeginCommandBuffer(cmd, &bi);

    const bool general = src_layout_ == VK_IMAGE_LAYOUT_GENERAL;
    const VkImageLayout src_now = general ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    const bool transfer = src_family_ != VK_QUEUE_FAMILY_IGNORED && src_family_ != family_;
    VkImageSubresourceRange src_range = src_range_;
    src_range.levelCount = 1, src_range.layerCount = 1;
    src_range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;

    // Ours from OpenGL (or new) -> transfer destination; the core's -> transfer source (its queue family's hand-over).
    VkImageMemoryBarrier in[2] = {{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}, {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}};
    in[0].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    in[0].oldLayout = target_used_ ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;
    in[0].newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    in[0].srcQueueFamilyIndex = target_used_ ? VK_QUEUE_FAMILY_EXTERNAL : VK_QUEUE_FAMILY_IGNORED;
    in[0].dstQueueFamilyIndex = target_used_ ? family_ : VK_QUEUE_FAMILY_IGNORED;
    in[0].image = target_;
    in[0].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    in[1].srcAccessMask = VK_ACCESS_MEMORY_WRITE_BIT;
    in[1].dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    in[1].oldLayout = src_layout_;
    in[1].newLayout = src_now;
    in[1].srcQueueFamilyIndex = transfer ? src_family_ : VK_QUEUE_FAMILY_IGNORED;
    in[1].dstQueueFamilyIndex = transfer ? family_ : VK_QUEUE_FAMILY_IGNORED;
    in[1].image = src_image_;
    in[1].subresourceRange = src_range;
    fn_->vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr,
                              0, nullptr, 2, in);

    VkImageSubresourceLayers src_layers{VK_IMAGE_ASPECT_COLOR_BIT, src_range.baseMipLevel, src_range.baseArrayLayer, 1};
    VkImageSubresourceLayers dst_layers{VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    if (copy_compatible(src_format_)) {
        VkImageCopy r{src_layers, {0, 0, 0}, dst_layers, {0, 0, 0}, {w, h, 1}};
        fn_->vkCmdCopyImage(cmd, src_image_, src_now, target_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r);
    } else {
        VkImageBlit r{};
        r.srcSubresource = src_layers;
        r.srcOffsets[1] = {(int32_t)w, (int32_t)h, 1};
        r.dstSubresource = dst_layers;
        r.dstOffsets[1] = {(int32_t)w, (int32_t)h, 1};
        fn_->vkCmdBlitImage(cmd, src_image_, src_now, target_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &r,
                            VK_FILTER_NEAREST);
    }

    // Ours -> OpenGL (shader read, outside Vulkan); the core's back to its layout and queue family.
    VkImageMemoryBarrier out[2] = {{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}, {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}};
    out[0].srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    out[0].oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    out[0].newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    out[0].srcQueueFamilyIndex = family_;
    out[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
    out[0].image = target_;
    out[0].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    out[1].srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    out[1].oldLayout = src_now;
    out[1].newLayout = src_layout_;
    out[1].srcQueueFamilyIndex = transfer ? family_ : VK_QUEUE_FAMILY_IGNORED;
    out[1].dstQueueFamilyIndex = transfer ? src_family_ : VK_QUEUE_FAMILY_IGNORED;
    out[1].image = src_image_;
    out[1].subresourceRange = src_range;
    fn_->vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, 0, nullptr,
                              0, nullptr, 2, out);
    fn_->vkEndCommandBuffer(cmd);
}

bool VulkanHost::frame(bool copy, unsigned w, unsigned h, bool gl_signalled, std::string &err) {
    if (!device_) return true;
    // OpenGL waits for `ready` exactly when copy is asked for: a copy not made here would leave it waiting for good.
    if (copy && !(image_new_ && target_ && w && h && w <= target_w_ && h <= target_h_))
        return err = "the game's frame doesn't fit the shared picture", false;
    if (gl_signalled && !copy) return err = "OpenGL let go of the shared picture with no frame to copy", false;
    if (copy && !copy_compatible(src_format_)) {
        VkFormatProperties fp;
        fn_->vkGetPhysicalDeviceFormatProperties(gpu_, src_format_, &fp);
        if (!(fp.optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT))
            return err = "the game's Vulkan picture format (" + std::to_string((int)src_format_) +
                         ") can't be copied for showing", false;
        if (!warned_blit_) LOGI("vulkan: the core's picture format %d is converted (blit)", (int)src_format_);
        warned_blit_ = true;
    }
    std::vector<VkSemaphore> waits;
    std::vector<VkPipelineStageFlags> stages;
    std::vector<VkCommandBuffer> cmds = core_cmds_;
    std::vector<VkSemaphore> signals;
    if (copy && gl_signalled) waits.push_back(done_), stages.push_back(VK_PIPELINE_STAGE_TRANSFER_BIT);
    // The core's set_image semaphores: waited once, and not when it used set_command_buffers (libretro_vulkan.h).
    if (copy && core_cmds_.empty())
        for (VkSemaphore s : wait_sems_) waits.push_back(s), stages.push_back(VK_PIPELINE_STAGE_TRANSFER_BIT);
    if (copy) {
        if (!wait_fence(index_)) return err = "the graphics card stopped answering (a frame took over 10 s)", false;
        record_copy(cmd_[index_], w, h);
        cmds.push_back(cmd_[index_]);
        signals.push_back(ready_);
    }
    if (core_signal_) signals.push_back(core_signal_);
    core_cmds_.clear();
    wait_sems_.clear();
    core_signal_ = VK_NULL_HANDLE;
    if (copy) image_new_ = false;
    if (cmds.empty() && signals.empty()) return true;

    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = (uint32_t)waits.size();
    si.pWaitSemaphores = waits.data();
    si.pWaitDstStageMask = stages.data();
    si.commandBufferCount = (uint32_t)cmds.size();
    si.pCommandBuffers = cmds.data();
    si.signalSemaphoreCount = (uint32_t)signals.size();
    si.pSignalSemaphores = signals.data();
    if (!wait_fence(index_)) return err = "the graphics card stopped answering (a frame took over 10 s)", false;
    VkResult r;
    {
        std::lock_guard<std::recursive_mutex> q(queue_mutex_);
        std::lock_guard<std::mutex> f(fence_mutex_);
        r = fn_->vkQueueSubmit(queue_, 1, &si, fence_[index_]);
        if (r == VK_SUCCESS) fence_pending_[index_] = true;
    }
    if (r != VK_SUCCESS)
        return err = "the graphics card stopped taking the game's frames (Vulkan error " + std::to_string((int)r) + ")", false;
    if (copy) target_used_ = true;
    // Next sync index: its last frame must be done before the core reuses what it gave then.
    index_ = (index_.load() + 1) % kSync;
    if (!wait_fence(index_)) return err = "the graphics card stopped answering (a frame took over 10 s)", false;
    return true;
}

void VulkanHost::set_image_cb(void *h, const retro_vulkan_image *image, uint32_t n, const VkSemaphore *sems,
                              uint32_t family) {
    auto *self = static_cast<VulkanHost *>(h);
    if (!image || !image->create_info.image) {
        self->image_new_ = false;
        return;
    }
    self->src_image_ = image->create_info.image;
    self->src_layout_ = image->image_layout;
    self->src_format_ = image->create_info.format;
    self->src_range_ = image->create_info.subresourceRange;
    self->src_family_ = family;
    self->wait_sems_.assign(sems, sems + (sems ? n : 0));
    self->image_new_ = true;
}

uint32_t VulkanHost::get_sync_index_cb(void *h) { return static_cast<VulkanHost *>(h)->index_; }
uint32_t VulkanHost::get_sync_index_mask_cb(void *) { return (1u << kSync) - 1; }

void VulkanHost::set_command_buffers_cb(void *h, uint32_t n, const VkCommandBuffer *cmds) {
    auto *self = static_cast<VulkanHost *>(h);
    self->core_cmds_.assign(cmds, cmds + (cmds ? n : 0));
}

void VulkanHost::wait_sync_index_cb(void *h) {
    auto *self = static_cast<VulkanHost *>(h);
    self->wait_fence(self->index_);
}

void VulkanHost::lock_queue_cb(void *h) { static_cast<VulkanHost *>(h)->queue_mutex_.lock(); }
void VulkanHost::unlock_queue_cb(void *h) { static_cast<VulkanHost *>(h)->queue_mutex_.unlock(); }
void VulkanHost::set_signal_semaphore_cb(void *h, VkSemaphore s) { static_cast<VulkanHost *>(h)->core_signal_ = s; }

void VulkanHost::destroy() {
    if (device_ && fn_ && fn_->vkQueueWaitIdle) {
        {
            std::lock_guard<std::recursive_mutex> q(queue_mutex_);
            fn_->vkQueueWaitIdle(queue_);
        }
        for (uint32_t i = 0; i < kSync; i++) wait_fence(i);
        for (auto &f : fence_)
            if (f) fn_->vkDestroyFence(device_, f, nullptr), f = VK_NULL_HANDLE;
        if (pool_) fn_->vkDestroyCommandPool(device_, pool_, nullptr), pool_ = VK_NULL_HANDLE;
        free_target();
    }
    // The core's own device resources go first (libretro_vulkan.h), then the device and instance, which are ours.
    if (core_asked_ && neg_ && neg_->destroy_device) neg_->destroy_device();
    if (device_ && fn_) {
        auto destroy_device = fn_->vkDestroyDevice ? fn_->vkDestroyDevice
                                                   : (PFN_vkDestroyDevice)fn_->vkGetDeviceProcAddr(device_, "vkDestroyDevice");
        if (destroy_device) destroy_device(device_, nullptr);
    }
    if (instance_ && fn_ && fn_->vkDestroyInstance) fn_->vkDestroyInstance(instance_, nullptr);
    if (lib_) FreeLibrary((HMODULE)lib_);
    delete fn_;
    fn_ = nullptr;
    lib_ = nullptr;
    gipa_ = nullptr;
    neg_ = nullptr;
    core_asked_ = core_made_device_ = false;
    instance_ = VK_NULL_HANDLE, gpu_ = VK_NULL_HANDLE, device_ = VK_NULL_HANDLE, queue_ = VK_NULL_HANDLE;
    iface_ = retro_hw_render_interface_vulkan{};
    std::fill(std::begin(cmd_), std::end(cmd_), VK_NULL_HANDLE);
    std::fill(std::begin(fence_pending_), std::end(fence_pending_), false);
    index_ = 0;
    image_new_ = false;
    wait_sems_.clear(), core_cmds_.clear();
    core_signal_ = VK_NULL_HANDLE;
}

#else // not Windows: the OpenGL side imports Win32 handles, so Vulkan cores are refused here.

struct VulkanHost::Fn {};
void close_shared_handle(void *) {}
bool VulkanHost::create(const retro_hw_render_context_negotiation_interface_vulkan *, const GpuId &, std::string &err) {
    return err = "Vulkan games play only in the Windows player", false;
}
void VulkanHost::destroy() {}
bool VulkanHost::make_target(unsigned, unsigned, SharedTarget &, std::string &err) {
    return err = "Vulkan games play only in the Windows player", false;
}
bool VulkanHost::frame(bool, unsigned, unsigned, bool, std::string &) { return true; }
#endif
} // namespace sc
