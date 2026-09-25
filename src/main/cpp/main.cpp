// Write C++ code here.
//
// Do not forget to dynamically load the C++ library into your application.
//
// For instance,
//
// In MainActivity.java:
//    static {
//       System.loadLibrary("main");
//    }
//
// Or, in MainActivity.kt:
//    companion object {
//      init {
//         System.loadLibrary("main")
//      }
//    }

#include <cstdint>
#include <cstring>

#include "habgen/OggGenerator.hpp"
#include <jni.h>

class Writer {
    FILE *const file;

public:
    Writer(const char *filename) : file(fopen(filename, "wb")) {
        if (!file) {
            throw std::runtime_error("Failed to open file for writing");
        }
    }
    ~Writer() {
        if (file) {
            fclose(file);
        }
    }

    void write(const std::uint8_t *data, std::size_t size) {
        if (!file) {
            throw std::runtime_error("File not open for writing");
        }

        if (fwrite(data, 1, size, file) != size) {
            throw std::runtime_error("Failed to write data to file");
        }
    }
};

extern "C"
JNIEXPORT jlong JNICALL
Java_io_hapticlabs_hapticlabsplayer_OGGBuilder_newOGGGenerator(JNIEnv *env, jobject thiz,
                                                               jstring output_path,
                                                               jbyteArray hab_buffer,
                                                               jfloat hab_duration,
                                                               jint hab48k_hz_divider,
                                                               jfloat quality,
                                                               jint media_sample_rate,
                                                               jshort media_channel_count,
                                                               jstring title, jstring album) {
    jsize hab_buffer_size = env->GetArrayLength(hab_buffer);
    auto *hab_buffer_bytes = reinterpret_cast<std::uint8_t *>( env->GetByteArrayElements(
            hab_buffer, nullptr));

    return reinterpret_cast<jlong>(new habgen::OggGenerator<Writer>(hab_buffer_bytes,
                                                                    hab_buffer_size, quality,
                                                                    hab48k_hz_divider,
                                                                    media_sample_rate,
                                                                    media_channel_count,
                                                                    hab_duration,
                                                                    env->GetStringUTFChars(title,
                                                                                           nullptr),
                                                                    env->GetStringUTFChars(album,
                                                                                           nullptr),
                                                                    env->GetStringUTFChars(
                                                                            output_path, nullptr)));
}

extern "C"
JNIEXPORT void JNICALL
Java_io_hapticlabs_hapticlabsplayer_OGGBuilder_deleteOGGGenerator(JNIEnv *env, jobject thiz,
                                                                  jlong pointer) {
    delete reinterpret_cast<habgen::OggGenerator<Writer> *>(pointer);
}
extern "C"
JNIEXPORT void JNICALL
Java_io_hapticlabs_hapticlabsplayer_OGGBuilder_pushDataToOGGGenerator(JNIEnv *env, jobject thiz,
                                                                      jlong pointer,
                                                                      jbyteArray buffer) {
    jsize buffer_size = env->GetArrayLength(buffer);
    auto *buffer_bytes = reinterpret_cast<std::uint8_t *>( env->GetByteArrayElements(
            buffer, nullptr));
    reinterpret_cast<habgen::OggGenerator<Writer> *>(pointer)->pushAudioSamples(buffer_bytes,
                                                                                buffer_size);
}