cmake_minimum_required(VERSION 3.22.1)
project(kate_core C)

add_library(kate_core SHARED
    bridge.c
    kate_stubs.c
    suggestions.c
    audio/audio_stream.c
    audio/wake_word.c
    audio/vad.c
    nlp/tokenizer.c
    nlp/intent_parser.c
    brain/emotion_engine.c
    brain/habit_engine.c
    brain/situation_engine.c
    brain/decision_engine.c
)

find_library(log-lib log)
find_library(aaudio-lib aaudio)

target_link_libraries(kate_core
    ${aaudio-lib}
    ${log-lib}
    m
)

target_include_directories(kate_core PRIVATE ${CMAKE_SOURCE_DIR})

# Modern toolchain flags for Android 14 Go compatibility
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -fPIC")
set(CMAKE_SHARED_LINKER_FLAGS 
    "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--hash-style=both")

target_compile_options(kate_core PRIVATE
    -O2
    -fPIC
    -Wall
)

target_link_options(kate_core PRIVATE
    -Wl,--hash-style=both
    -Wl,-z,max-page-size=16384
)
