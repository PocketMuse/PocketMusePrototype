#include <jni.h>
#include <sd15/runtime.hpp>

#include <atomic>
#include <chrono>
#include <new>
#include <stdexcept>

namespace {

using Clock = std::chrono::steady_clock;
using Token = sd15::GenerationCancellationToken;
std::atomic_flag generating = ATOMIC_FLAG_INIT;

struct ReleaseGeneration {
    ~ReleaseGeneration() { generating.clear(std::memory_order_release); }
};

struct PendingJavaException {};

Token* token_at(jlong handle) noexcept {
    return reinterpret_cast<Token*>(handle);
}

jlong elapsed_nanos(Clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start).count();
}

void throw_java(JNIEnv* env, const char* type, const char* message) noexcept {
    if (env->ExceptionCheck()) return;
    jclass exception = env->FindClass(type);
    if (!exception) return;
    // ThrowNew requires modified UTF-8. Preserve ASCII diagnostics without passing raw UTF-8.
    char ascii[1024];
    size_t length = 0;
    for (; length < sizeof(ascii) - 1 && message[length]; ++length) {
        const auto ch = static_cast<unsigned char>(message[length]);
        ascii[length] = ch < 128 ? message[length] : '?';
    }
    ascii[length] = '\0';
    env->ThrowNew(exception, ascii);
}

std::string read_path(JNIEnv* env, jstring value) {
    // Kotlin has already validated that the path is ASCII.
    std::string path(static_cast<size_t>(env->GetStringLength(value)) + 1, '\0');
    env->GetStringUTFRegion(value, 0, static_cast<jsize>(path.size() - 1), path.data());
    if (env->ExceptionCheck()) throw PendingJavaException{};
    path.pop_back();
    return path;
}

jbyteArray copy_rgb(JNIEnv* env, const sd15::Image& image) {
    constexpr auto size = sd15::kImageSize;
    if (image.width != size || image.height != size || image.rgb.size() != size * size * 3) {
        throw std::runtime_error("Unexpected Runtime RGB8 dimensions");
    }
    const auto count = static_cast<jsize>(image.rgb.size());
    auto rgb = env->NewByteArray(count);
    if (!rgb) throw PendingJavaException{};
    env->SetByteArrayRegion(rgb, 0, count, reinterpret_cast<const jbyte*>(image.rgb.data()));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(rgb);
        throw PendingJavaException{};
    }
    return rgb;
}

jmethodID callback_method(JNIEnv* env, jobject callback, const char* signature) {
    if (!callback) return nullptr;
    jclass type = env->GetObjectClass(callback);
    if (!type) throw PendingJavaException{};
    jmethodID update = env->GetMethodID(type, "update", signature);
    env->DeleteLocalRef(type);
    if (!update) throw PendingJavaException{};
    return update;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_pocketmuseprototype_runtime_NativeRuntime_createToken(JNIEnv* env, jobject) {
    auto* token = new (std::nothrow) Token();
    if (!token) throw_java(env, "java/lang/OutOfMemoryError", "Cannot allocate generation token");
    return reinterpret_cast<jlong>(token);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_pocketmuseprototype_runtime_NativeRuntime_requestCancel(JNIEnv*, jobject, jlong handle) {
    token_at(handle)->request_cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_pocketmuseprototype_runtime_NativeRuntime_destroyToken(JNIEnv*, jobject, jlong handle) {
    delete token_at(handle);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_pocketmuseprototype_runtime_NativeRuntime_generate(
        JNIEnv* env, jobject, jlong handle, jstring model_dir, jstring tokenizer_dir,
        jstring cache_dir, jstring preview_decoder, jint steps, jlong seed, jbyteArray prompt,
        jobject progress_callback, jobject preview_callback) {
    const char* failure_type = "com/example/pocketmuseprototype/runtime/RuntimeInitializationException";
    try {
        auto& token = *token_at(handle);
        if (token.is_requested()) throw sd15::GenerationCancelled{};
        if (generating.test_and_set(std::memory_order_acquire)) {
            throw_java(env, "com/example/pocketmuseprototype/runtime/RuntimeGenerationException",
                       "Another SD1.5 generation is in progress");
            return nullptr;
        }
        const ReleaseGeneration release_generation;

        sd15::RuntimeOptions config;
        config.model_dir = read_path(env, model_dir);
        config.tokenizer_dir = read_path(env, tokenizer_dir);
        config.cache_dir = read_path(env, cache_dir);
        config.preview_decoder_path = read_path(env, preview_decoder);
        const auto runtime_start = Clock::now();
        sd15::Sd15Runtime runtime(config);
        const jlong runtime_nanos = elapsed_nanos(runtime_start);
        failure_type = "com/example/pocketmuseprototype/runtime/RuntimeGenerationException";

        sd15::GenerateOptions request(sd15::SamplingPreset::Lcm, steps, 1.0f);
        request.seed = seed;
        request.preview_interval = preview_callback ? 1 : 0;
        const auto prompt_size = env->GetArrayLength(prompt);
        request.prompt.resize(static_cast<size_t>(prompt_size));
        if (prompt_size != 0) {
            env->GetByteArrayRegion(prompt, 0, prompt_size, reinterpret_cast<jbyte*>(request.prompt.data()));
            if (env->ExceptionCheck()) throw PendingJavaException{};
        }

        const auto update_progress = callback_method(env, progress_callback, "(Ljava/lang/String;IIJ)V");
        const auto update_preview = callback_method(env, preview_callback, "(IIII[BJ)V");
        Clock::time_point generate_start;
        sd15::InferenceProgress progress;
        if (update_progress) {
            progress = [&](const std::string& stage, int step, int total) {
                const jlong elapsed = elapsed_nanos(generate_start);
                jstring name = env->NewStringUTF(stage.c_str());
                if (!name) throw PendingJavaException{};
                env->CallVoidMethod(progress_callback, update_progress, name, step, total, elapsed);
                env->DeleteLocalRef(name);
                if (env->ExceptionCheck()) throw PendingJavaException{};
            };
        }
        sd15::InferencePreview preview;
        if (update_preview) {
            preview = [&](int step, int total, const sd15::Image& image) {
                const jlong elapsed = elapsed_nanos(generate_start);
                auto rgb = copy_rgb(env, image);
                env->CallVoidMethod(preview_callback, update_preview, step, total,
                                    image.width, image.height, rgb, elapsed);
                env->DeleteLocalRef(rgb);
                if (env->ExceptionCheck()) throw PendingJavaException{};
            };
        }
        generate_start = Clock::now();
        const auto image = runtime.generate(request, token, progress, preview);
        const jlong generate_nanos = elapsed_nanos(generate_start);
        auto rgb = copy_rgb(env, image);
        jclass result = env->FindClass("com/example/pocketmuseprototype/runtime/GeneratedImage");
        if (!result) return nullptr;
        jmethodID constructor = env->GetMethodID(result, "<init>", "(JII[BJJ)V");
        if (!constructor) return nullptr;
        return env->NewObject(result, constructor, static_cast<jlong>(image.seed),
                              image.width, image.height, rgb, runtime_nanos, generate_nanos);
    } catch (const PendingJavaException&) {
        // Stack unwinding has released the Runtime. JNI propagates the original JVM exception.
    } catch (const sd15::GenerationCancelled& error) {
        throw_java(env, "java/util/concurrent/CancellationException", error.what());
    } catch (const std::bad_alloc& error) {
        throw_java(env, "java/lang/OutOfMemoryError", error.what());
    } catch (const std::exception& error) {
        throw_java(env, failure_type, error.what());
    } catch (...) {
        throw_java(env, failure_type, "Unknown SD1.5 Runtime error");
    }
    return nullptr;
}
