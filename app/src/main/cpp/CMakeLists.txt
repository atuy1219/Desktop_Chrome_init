cmake_minimum_required(VERSION 3.22.1)
project(chrome_native_omnibox_probe C)

add_library(chrome_omnibox_probe SHARED native_omnibox_probe.c)
set_property(TARGET chrome_omnibox_probe PROPERTY C_STANDARD 11)

target_compile_options(chrome_omnibox_probe PRIVATE
    -Wall -Wextra -Werror -fvisibility=hidden
    -fstack-protector-strong)
target_link_libraries(chrome_omnibox_probe PRIVATE log dl)
