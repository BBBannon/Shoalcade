cmake_minimum_required(VERSION 3.22.1)
project(shoalcade_link C)

# Link status, loaded by Kotlin (LinkNative).
add_library(sclinkstate SHARED linkstate.c)
target_compile_options(sclinkstate PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)

# The shim LibretroDroid loads in place of the real core for link sessions.
add_library(linkshim SHARED linkshim.c)
target_compile_options(linkshim PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(linkshim sclinkstate log dl)

# The shim LibretroDroid loads in place of mGBA for Game Boy games with a cartridge clock.
add_library(clockshim SHARED clockshim.c)
target_compile_options(clockshim PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(clockshim log dl)
target_link_options(clockshim PRIVATE "-Wl,-z,max-page-size=16384")

# The shim LibretroDroid loads in place of mkxp-z for RPG Maker games (D147).
add_library(rpgshim SHARED rpgshim.c)
target_compile_options(rpgshim PRIVATE -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(rpgshim log dl)
target_link_options(rpgshim PRIVATE "-Wl,-z,max-page-size=16384")

# 16 KB page alignment: required on newer Android devices that use 16 KB memory pages.
target_link_options(sclinkstate PRIVATE "-Wl,-z,max-page-size=16384")
target_link_options(linkshim PRIVATE "-Wl,-z,max-page-size=16384")
