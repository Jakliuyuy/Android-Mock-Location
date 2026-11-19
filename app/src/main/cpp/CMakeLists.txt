cmake_minimum_required(VERSION 3.22.1)
project(mocksec LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

add_library(mocksec SHARED
    ed25519_verify.c
    mocksec.cpp
)

target_compile_options(mocksec PRIVATE
    -O3
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
    -fno-rtti
    -fno-exceptions
)

target_link_options(mocksec PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -s
)

find_library(log-lib log)
target_link_libraries(mocksec ${log-lib})
