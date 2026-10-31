cmake_minimum_required(VERSION 3.22.1)

project(rime_jni C)

set(CMAKE_C_STANDARD 11)

# Prebuilt librime + its dynamic dependency closure live in jniLibs/<abi>/.
# They are vendored by android/scripts/prepare-rime-deps.ps1 and copied into
# app/src/main/jniLibs so that the APK is self-contained.
set(JNILIBS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

if(NOT EXISTS ${JNILIBS_DIR}/librime.so)
    message(FATAL_ERROR "librime.so missing for ${ANDROID_ABI} in ${JNILIBS_DIR}")
endif()

add_library(rime_jni SHARED rime_jni.c)

target_include_directories(rime_jni PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

target_compile_options(rime_jni PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)

# Link the whole closure explicitly so the NDK linker can satisfy every
# undefined symbol that librime.so imports from its sibling libraries.
target_link_libraries(rime_jni
        ${JNILIBS_DIR}/librime.so
        ${JNILIBS_DIR}/libglog.so
        ${JNILIBS_DIR}/libgflags.so
        ${JNILIBS_DIR}/libyaml-cpp.so
        ${JNILIBS_DIR}/libleveldb.so
        ${JNILIBS_DIR}/libsnappy.so
        ${JNILIBS_DIR}/libmarisa.so
        ${JNILIBS_DIR}/libopencc.so
        ${JNILIBS_DIR}/libc++_shared.so
        log)
