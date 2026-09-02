
# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html.
# For more examples on how to use CMake, see https://github.com/android/ndk-samples.

# Sets the minimum CMake version required for this project.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name. The project name can be accessed via ${ PROJECT_NAME},
# Since this is the top level CMakeLists.txt, the project name is also accessible
# with ${CMAKE_PROJECT_NAME} (both CMake variables are in-sync within the top level
# build script scope).
project("habGen")

# Support host JNI when not building for Android
if(NOT ANDROID)
    find_package(JNI REQUIRED)
endif()

# Disable tests for subprojects
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(habgen_BUILD_TESTS OFF CACHE BOOL "" FORCE)

include (FetchContent)
FetchContent_Declare(ogg GIT_REPOSITORY https://github.com/xiph/ogg.git    GIT_TAG v1.3.5)
FetchContent_MakeAvailable(ogg)

# vorbis depends on ogg. When building for Android, vorbis's FindOgg.cmake
# often fails to find the ogg library we just built via FetchContent.
# We provide the variables it expects to satisfy find_package(Ogg).
set(OGG_INCLUDE_DIR "${ogg_SOURCE_DIR}/include;${ogg_BINARY_DIR}/include" CACHE PATH "" FORCE)
set(OGG_LIBRARY ogg CACHE STRING "" FORCE)
set(OGG_VERSION_STRING "1.3.5" CACHE STRING "" FORCE)

FetchContent_Declare(vorbis GIT_REPOSITORY https://github.com/xiph/vorbis.git    GIT_TAG v1.3.7)
FetchContent_MakeAvailable(vorbis)

set(HAPTICLABS_HABGEN_SOURCE_DIR
        "${CMAKE_CURRENT_LIST_DIR}/../../../../../oggCompleter"
        CACHE PATH "Path to the HabGen source tree")

if(NOT EXISTS "${HAPTICLABS_HABGEN_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
            "HabGen source tree not found at ${HAPTICLABS_HABGEN_SOURCE_DIR}. "
            "Set HAPTICLABS_HABGEN_SOURCE_DIR to a checkout of the habgen repository.")
endif()

set(HAPTICLABS_HABGEN_TARGETS
    habgen::habgen
)

# Creates and names a library, sets it as either STATIC
# or SHARED, and provides the relative paths to its source code.
# You can define multiple libraries, and CMake builds them for you.
# Gradle automatically packages shared libraries with your APK.
#
# In this top level CMakeLists.txt, ${CMAKE_PROJECT_NAME} is used to define
# the target library name; in the sub-module's CMakeLists.txt, ${PROJECT_NAME}
# is preferred for the same purpose.
#
# In order to load a library into your app from Java/Kotlin, you must call
# System.loadLibrary() and pass the name of the library defined here;
# for GameActivity/NativeActivity derived applications, the same library name must be
# used in the AndroidManifest.xml file.
add_library(${CMAKE_PROJECT_NAME} SHARED
    # List C/C++ source files with relative paths to this CMakeLists.txt.
    main.cpp)

if(NOT ANDROID)
    target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE ${JNI_INCLUDE_DIRS})
    target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE ${JNI_LIBRARIES})
endif()

add_subdirectory("${HAPTICLABS_HABGEN_SOURCE_DIR}"
                    "${CMAKE_BINARY_DIR}/external/hapticlabs_habgen")

target_include_directories(${CMAKE_PROJECT_NAME}
    PRIVATE "${HAPTICLABS_HABGEN_SOURCE_DIR}/src"
)

# Specifies libraries CMake should link to your target library. You
# can link libraries from various origins, such as libraries defined in this
# build script, prebuilt third-party libraries, or Android system libraries.
target_link_libraries(${CMAKE_PROJECT_NAME} PRIVATE
    # List libraries link to the target library
    ${HAPTICLABS_HABGEN_TARGETS}
)
