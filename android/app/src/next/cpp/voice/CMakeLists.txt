# The word-timing engine: plain C, no libraries. Built by CI (not by Gradle) once per ABI, into android/app/src/next/jniLibs/<abi>/libstrata_voice.so.
cmake_minimum_required(VERSION 3.18)
project(strata_voice C)
set(CMAKE_C_STANDARD 11)
add_library(strata_voice SHARED strata_voice.c strata_syllables.c strata_voice_jni.c)
target_compile_options(strata_voice PRIVATE -O2)
target_link_libraries(strata_voice m)
target_link_options(strata_voice PRIVATE -Wl,-z,max-page-size=16384)
