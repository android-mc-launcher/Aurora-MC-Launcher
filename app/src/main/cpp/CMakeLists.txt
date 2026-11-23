cmake_minimum_required(VERSION 3.31)
project(mcjl_engine LANGUAGES CXX)

# C++23：由 app/build.gradle.kts 传入 -DMCJL_CPP_STANDARD=23
if (NOT DEFINED MCJL_CPP_STANDARD)
    set(MCJL_CPP_STANDARD 23)
endif ()
set(CMAKE_CXX_STANDARD ${MCJL_CPP_STANDARD})
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)          # 生成 -std=c++23（非 gnu++23）
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

add_library(mcjl_engine SHARED
        src/engine.cpp
        src/jni_bridge.cpp)

target_include_directories(mcjl_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

target_compile_options(mcjl_engine PRIVATE
        -Wall -Wextra -Wpedantic -fexceptions -frtti)

target_compile_definitions(mcjl_engine PRIVATE
        MCJL_CPP_STANDARD=${MCJL_CPP_STANDARD})

# log：Android logcat；${CMAKE_DL_LIBS}：dlopen/dlsym（Android bionic 自带，host 为 libdl）
target_link_libraries(mcjl_engine PRIVATE log ${CMAKE_DL_LIBS})

# 仅导出 JNI_OnLoad / JNI_OnUnload，其余符号全部隐藏
set_target_properties(mcjl_engine PROPERTIES
        CXX_VISIBILITY_PRESET hidden
        VISIBILITY_INLINES_HIDDEN ON)
