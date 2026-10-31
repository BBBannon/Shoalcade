package com.swordfish.libretrodroid3ds

/**
 * A custom Vulkan driver unpacked into app-private storage: [directory] holds [libraryName]
 * (for example `libvulkan_freedreno.so`). Loaded in place of the system driver on Adreno.
 */
data class VulkanDriver(
    val directory: String,
    val libraryName: String
)
