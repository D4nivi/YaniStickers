#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

#include "webp/decode.h"
#include "webp/demux.h"
#include "webp/encode.h"
#include "webp/mux.h"

namespace {

constexpr const char* kLogTag = "YaniWebpJni";

struct EncoderSession {
    WebPAnimEncoder* enc = nullptr;
    int width = 0;
    int height = 0;
    int current_timestamp_ms = 0;
    int frame_count = 0;
    std::vector<uint8_t> rgba_buffer;
    std::string last_error;
};

struct DecoderSession {
    std::vector<uint8_t> raw_data;
    WebPAnimDecoder* dec = nullptr;
    int width = 0;
    int height = 0;
    int frame_count = 0;
    int prev_timestamp_ms = 0;
};

inline uint8_t UnpremultiplyChannel(uint8_t channel, uint8_t alpha) {
    if (alpha == 0) return 0;
    if (alpha == 255) return channel;
    const uint32_t value = (static_cast<uint32_t>(channel) * 255u + (alpha / 2u)) / alpha;
    return static_cast<uint8_t>(std::min<uint32_t>(value, 255u));
}

inline uint8_t PremultiplyChannel(uint8_t channel, uint8_t alpha) {
    if (alpha == 0) return 0;
    if (alpha == 255) return channel;
    return static_cast<uint8_t>((static_cast<uint32_t>(channel) * alpha + 127u) / 255u);
}

bool EncodeRgbaFrame(
    EncoderSession* session,
    const uint8_t* rgba_data,
    int stride,
    jint durationMs,
    jfloat quality,
    jboolean lossless,
    jint method
) {
    WebPPicture pic;
    if (!WebPPictureInit(&pic)) {
        session->last_error = "WebPPictureInit falló.";
        return false;
    }
    pic.use_argb = 1;
    pic.width = session->width;
    pic.height = session->height;
    if (!WebPPictureImportRGBA(&pic, rgba_data, stride)) {
        WebPPictureFree(&pic);
        session->last_error = "WebPPictureImportRGBA falló.";
        return false;
    }

    WebPConfig config;
    if (!WebPConfigInit(&config)) {
        WebPPictureFree(&pic);
        session->last_error = "WebPConfigInit falló.";
        return false;
    }
    config.lossless = lossless ? 1 : 0;
    config.quality = std::clamp(static_cast<float>(quality), 0.0f, 100.0f);
    config.method = std::clamp(static_cast<int>(method), 0, 6);
    config.thread_level = 1;
    config.pass = 1;
    config.preprocessing = 0;
    config.exact = 0;

    if (!WebPValidateConfig(&config)) {
        WebPPictureFree(&pic);
        session->last_error = "Configuración WebP inválida.";
        return false;
    }

    const int ok = WebPAnimEncoderAdd(session->enc, &pic, session->current_timestamp_ms, &config);
    if (!ok) {
        const char* err = WebPAnimEncoderGetError(session->enc);
        session->last_error = (err != nullptr && err[0] != '\0') ? err : "WebPAnimEncoderAdd falló.";
        WebPPictureFree(&pic);
        return false;
    }

    WebPPictureFree(&pic);
    session->current_timestamp_ms += durationMs;
    session->frame_count += 1;
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_github_danivi_yanistickers_data_NativeWebpCodec_nativeCreateEncoder(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint width,
    jint height,
    jint loopCount,
    jboolean minimizeSize
) {
    if (width <= 0 || height <= 0) {
        return 0;
    }

    WebPAnimEncoderOptions enc_options;
    if (!WebPAnimEncoderOptionsInit(&enc_options)) {
        return 0;
    }
    enc_options.anim_params.loop_count = loopCount;
    enc_options.anim_params.bgcolor = 0x00000000u;
    enc_options.minimize_size = minimizeSize ? 1 : 0;
    enc_options.allow_mixed = 0;

    WebPAnimEncoder* enc = WebPAnimEncoderNew(width, height, &enc_options);
    if (enc == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "WebPAnimEncoderNew falló");
        return 0;
    }

    auto* session = new EncoderSession();
    session->enc = enc;
    session->width = width;
    session->height = height;
    session->rgba_buffer.resize(static_cast<size_t>(width) * static_cast<size_t>(height) * 4u);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jboolean JNICALL
Java_io_github_danivi_yanistickers_data_NativeWebpCodec_nativeAddFrame(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobject bitmap,
    jint durationMs,
    jfloat quality,
    jboolean lossless,
    jint method
) {
    auto* session = reinterpret_cast<EncoderSession*>(handle);
    if (session == nullptr || session->enc == nullptr || bitmap == nullptr || durationMs <= 0) {
        return JNI_FALSE;
    }

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        session->last_error = "No se pudo leer la información del Bitmap.";
        return JNI_FALSE;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        static_cast<int>(info.width) != session->width ||
        static_cast<int>(info.height) != session->height) {
        session->last_error = "Formato o dimensiones del Bitmap incompatibles con el codificador.";
        return JNI_FALSE;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        session->last_error = "No se pudieron bloquear los píxeles del Bitmap.";
        return JNI_FALSE;
    }

    const auto* src_base = static_cast<const uint8_t*>(pixels);
    const size_t dst_stride = static_cast<size_t>(session->width) * 4u;
    for (int y = 0; y < session->height; ++y) {
        const uint8_t* src_row = src_base + static_cast<size_t>(y) * info.stride;
        uint8_t* dst_row = session->rgba_buffer.data() + static_cast<size_t>(y) * dst_stride;

        // Comprobar rápidamente si toda la fila es opaca para copiar en bloque con memcpy
        uint8_t row_alpha_and = 0xFFu;
        for (int x = 0; x < session->width; ++x) {
            row_alpha_and &= src_row[static_cast<size_t>(x) * 4u + 3u];
        }
        if (row_alpha_and == 0xFFu) {
            std::memcpy(dst_row, src_row, dst_stride);
            continue;
        }

        for (int x = 0; x < session->width; ++x) {
            const size_t idx = static_cast<size_t>(x) * 4u;
            const uint8_t a = src_row[idx + 3];
            if (a == 255) {
                dst_row[idx] = src_row[idx];
                dst_row[idx + 1] = src_row[idx + 1];
                dst_row[idx + 2] = src_row[idx + 2];
                dst_row[idx + 3] = 255;
            } else if (a == 0) {
                dst_row[idx] = 0;
                dst_row[idx + 1] = 0;
                dst_row[idx + 2] = 0;
                dst_row[idx + 3] = 0;
            } else {
                dst_row[idx] = UnpremultiplyChannel(src_row[idx], a);
                dst_row[idx + 1] = UnpremultiplyChannel(src_row[idx + 1], a);
                dst_row[idx + 2] = UnpremultiplyChannel(src_row[idx + 2], a);
                dst_row[idx + 3] = a;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);

    return EncodeRgbaFrame(
        session,
        session->rgba_buffer.data(),
        static_cast<int>(dst_stride),
        durationMs,
        quality,
        lossless,
        method
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_io_github_danivi_yanistickers_data_NativeWebpCodec_nativeAddRgbaBufferFrame(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobject directRgbaBuffer,
    jint durationMs,
    jfloat quality,
    jboolean lossless,
    jint method
) {
    auto* session = reinterpret_cast<EncoderSession*>(handle);
    if (session == nullptr || session->enc == nullptr || directRgbaBuffer == nullptr || durationMs <= 0) {
        return JNI_FALSE;
    }

    auto* rgba_ptr = static_cast<const uint8_t*>(env->GetDirectBufferAddress(directRgbaBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(directRgbaBuffer);
    const jlong expected = static_cast<jlong>(session->width) * static_cast<jlong>(session->height) * 4L;
    if (rgba_ptr == nullptr || capacity < expected) {
        session->last_error = "Buffer RGBA directo inválido o insuficiente.";
        return JNI_FALSE;
    }

    return EncodeRgbaFrame(
        session,
        rgba_ptr,
        session->width * 4,
        durationMs,
        quality,
        lossless,
        method
    ) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL
Java_io_github_danivi_yanistickers_data_NativeWebpCodec_nativeFinishEncoder(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<EncoderSession*>(handle);
    if (session == nullptr || session->enc == nullptr || session->frame_count <= 0) {
        return nullptr;
    }

    if (!WebPAnimEncoderAdd(session->enc, nullptr, session->current_timestamp_ms, nullptr)) {
        const char* err = WebPAnimEncoderGetError(session->enc);
        session->last_error = (err != nullptr && err[0] != '\0') ? err : "Error al cerrar el último frame.";
        return nullptr;
    }

    WebPData webp_data;
    WebPDataInit(&webp_data);
    if (!WebPAnimEncoderAssemble(session->enc, &webp_data)) {
        const char* err = WebPAnimEncoderGetError(session->enc);
        session->last_error = (err != nullptr && err[0] != '\0') ? err : "WebPAnimEncoderAssemble falló.";
        WebPDataClear(&webp_data);
        return nullptr;
    }

    jbyteArray out = env->NewByteArray(static_cast<jsize>(webp_data.size));
    if (out != nullptr && webp_data.size > 0) {
        env->SetByteArrayRegion(
            out,
            0,
            static_cast<jsize>(webp_data.size),
            reinterpret_cast<const jbyte*>(webp_data.bytes)
        );
    }
    WebPDataClear(&webp_data);
    return out;
}

JNIEXPORT jstring JNICALL
Java_io_github_danivi_yanistickers_data_NativeWebpCodec_nativeGetLastError(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<EncoderSession*>(handle);
    if (session == nullptr || session->last_error.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(session->last_error.c_str());
}

JNIEXPORT void JNICALL
Java_io_github_danivi_yanistickers_data_NativeWebpCodec_nativeDestroyEncoder(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<EncoderSession*>(handle);
    if (session != nullptr) {
        if (session->enc != nullptr) {
            WebPAnimEncoderDelete(session->enc);
        }
        delete session;
    }
}

JNIEXPORT jlong JNICALL
Java_io_github_danivi_yanistickers_data_NativeWebpCodec_nativeCreateDecoder(
    JNIEnv* env,
    jobject /*thiz*/,
    jbyteArray webpBytes
) {
    if (webpBytes == nullptr) {
        return 0;
    }
    const jsize length = env->GetArrayLength(webpBytes);
    if (length <= 0) {
        return 0;
    }

    auto* session = new DecoderSession();
    session->raw_data.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        webpBytes,
        0,
        length,
        reinterpret_cast<jbyte*>(session->raw_data.data())
    );

    WebPData data;
    data.bytes = session->raw_data.data();
    data.size = session->raw_data.size();

    WebPAnimDecoderOptions dec_options;
    if (!WebPAnimDecoderOptionsInit(&dec_options)) {
        delete session;
        return 0;
    }
    dec_options.color_mode = MODE_RGBA;

    WebPAnimDecoder* dec = WebPAnimDecoderNew(&data, &dec_options);
    if (dec == nullptr) {
        delete session;
        return 0;
    }

    WebPAnimInfo info;
    if (!WebPAnimDecoderGetInfo(dec, &info) || info.canvas_width == 0 || info.canvas_height == 0) {
        WebPAnimDecoderDelete(dec);
        delete session;
        return 0;
    }

    session->dec = dec;
    session->width = static_cast<int>(info.canvas_width);
    session->height = static_cast<int>(info.canvas_height);
    session->frame_count = static_cast<int>(info.frame_count);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jintArray JNICALL
Java_io_github_danivi_yanistickers_data_NativeWebpCodec_nativeGetDecoderInfo(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<DecoderSession*>(handle);
    if (session == nullptr) {
        return nullptr;
    }
    jint values[3] = {session->width, session->height, session->frame_count};
    jintArray out = env->NewIntArray(3);
    if (out != nullptr) {
        env->SetIntArrayRegion(out, 0, 3, values);
    }
    return out;
}

JNIEXPORT jint JNICALL
Java_io_github_danivi_yanistickers_data_NativeWebpCodec_nativeDecodeNextFrame(
    JNIEnv* env,
    jobject /*thiz*/,
    jlong handle,
    jobject targetBitmap
) {
    auto* session = reinterpret_cast<DecoderSession*>(handle);
    if (session == nullptr || session->dec == nullptr || targetBitmap == nullptr) {
        return -2;
    }
    if (!WebPAnimDecoderHasMoreFrames(session->dec)) {
        return -1;
    }

    uint8_t* frame_rgba = nullptr;
    int timestamp_ms = 0;
    if (!WebPAnimDecoderGetNext(session->dec, &frame_rgba, &timestamp_ms) || frame_rgba == nullptr) {
        return -2;
    }

    const int duration_ms = std::max(1, timestamp_ms - session->prev_timestamp_ms);
    session->prev_timestamp_ms = timestamp_ms;

    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, targetBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return -2;
    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        static_cast<int>(info.width) != session->width ||
        static_cast<int>(info.height) != session->height) {
        return -2;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, targetBitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        return -2;
    }

    auto* dst_base = static_cast<uint8_t*>(pixels);
    const size_t src_stride = static_cast<size_t>(session->width) * 4u;
    for (int y = 0; y < session->height; ++y) {
        const uint8_t* src_row = frame_rgba + static_cast<size_t>(y) * src_stride;
        uint8_t* dst_row = dst_base + static_cast<size_t>(y) * info.stride;
        for (int x = 0; x < session->width; ++x) {
            const size_t idx = static_cast<size_t>(x) * 4u;
            const uint8_t a = src_row[idx + 3];
            if (a == 255) {
                dst_row[idx] = src_row[idx];
                dst_row[idx + 1] = src_row[idx + 1];
                dst_row[idx + 2] = src_row[idx + 2];
                dst_row[idx + 3] = 255;
            } else if (a == 0) {
                dst_row[idx] = 0;
                dst_row[idx + 1] = 0;
                dst_row[idx + 2] = 0;
                dst_row[idx + 3] = 0;
            } else {
                dst_row[idx] = PremultiplyChannel(src_row[idx], a);
                dst_row[idx + 1] = PremultiplyChannel(src_row[idx + 1], a);
                dst_row[idx + 2] = PremultiplyChannel(src_row[idx + 2], a);
                dst_row[idx + 3] = a;
            }
        }
    }

    AndroidBitmap_unlockPixels(env, targetBitmap);
    return duration_ms;
}

JNIEXPORT void JNICALL
Java_io_github_danivi_yanistickers_data_NativeWebpCodec_nativeDestroyDecoder(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jlong handle
) {
    auto* session = reinterpret_cast<DecoderSession*>(handle);
    if (session != nullptr) {
        if (session->dec != nullptr) {
            WebPAnimDecoderDelete(session->dec);
        }
        delete session;
    }
}

}  // extern "C"
