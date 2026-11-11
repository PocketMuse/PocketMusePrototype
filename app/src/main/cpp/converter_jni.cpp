#include <jni.h>
#include <sd15/converter.hpp>

#include <new>

namespace {

using sd15::ConvertStage;
using sd15::ConvertStatus;
using sd15::ErrorCode;

static_assert(static_cast<int>(ConvertStage::Validating) == 0 &&
              static_cast<int>(ConvertStage::Converting) == 1 &&
              static_cast<int>(ConvertStage::Finalizing) == 2 &&
              static_cast<int>(ConvertStage::Completed) == 3);
static_assert(static_cast<int>(ConvertStatus::Succeeded) == 0 &&
              static_cast<int>(ConvertStatus::Cancelled) == 1 &&
              static_cast<int>(ConvertStatus::Failed) == 2);

thread_local bool converting = false;

// A callback can reenter JNI on the same thread, where upstream's mutex cannot be tried again.
struct ConversionScope {
    ConversionScope() { converting = true; }
    ~ConversionScope() { converting = false; }
};

struct ProgressDeliveryFailed {};

std::string read_path(JNIEnv* env, jbyteArray bytes) {
    const auto size = env->GetArrayLength(bytes);
    std::string path(size, '\0');
    env->GetByteArrayRegion(bytes, 0, size, reinterpret_cast<jbyte*>(path.data()));
    return path;
}

void deliver_progress(JNIEnv* env, jobject callbacks, jmethodID method,
                      const sd15::ConvertProgress& progress) {
    // Converter component and output names use its ASCII filename allowlist.
    const auto component = env->NewStringUTF(progress.component.c_str());
    if (component != nullptr) {
        env->CallVoidMethod(callbacks, method, static_cast<jint>(progress.stage), component,
                            static_cast<jlong>(progress.completed_bytes),
                            static_cast<jlong>(progress.total_bytes), progress.fraction,
                            static_cast<jlong>(progress.elapsed_ms),
                            progress.remaining_ms ? static_cast<jlong>(*progress.remaining_ms) : jlong{-1});
        env->DeleteLocalRef(component);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        throw ProgressDeliveryFailed{};
    }
    if (component == nullptr) throw ProgressDeliveryFailed{};
}

jobject deliver_result(JNIEnv* env, jobject callbacks, jclass callback_class,
                       const sd15::ConvertResult& result) {
    const auto add_file = env->GetMethodID(callback_class, "addFile", "(Ljava/lang/String;J)V");
    if (add_file == nullptr) return nullptr;
    const auto finish = env->GetMethodID(callback_class, "finish",
        "(ILjava/lang/String;JID)Lcom/example/pocketmuseprototype/conversion/ConversionResult;");
    if (finish == nullptr) return nullptr;

    for (const auto& file : result.files) {
        const auto name = env->NewStringUTF(file.name.c_str());
        if (name == nullptr) return nullptr;
        env->CallVoidMethod(callbacks, add_file, name, static_cast<jlong>(file.size_bytes));
        env->DeleteLocalRef(name);
        if (env->ExceptionCheck()) return nullptr;
    }
    const auto error = env->NewStringUTF(sd15::error_code_name(result.error.code));
    if (error == nullptr) return nullptr;
    return env->CallObjectMethod(callbacks, finish, static_cast<jint>(result.status), error,
                                 static_cast<jlong>(result.elapsed_ms),
                                 static_cast<jint>(result.hqq_iterations.value_or(-1)),
                                 result.hqq_compute_ms);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_example_pocketmuseprototype_conversion_NativeConverter_createToken(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new (std::nothrow) sd15::CancellationToken);
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_pocketmuseprototype_conversion_NativeConverter_requestCancel(JNIEnv*, jobject, jlong token) {
    reinterpret_cast<sd15::CancellationToken*>(token)->request_cancel();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_pocketmuseprototype_conversion_NativeConverter_destroyToken(JNIEnv*, jobject, jlong token) {
    delete reinterpret_cast<sd15::CancellationToken*>(token);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_example_pocketmuseprototype_conversion_NativeConverter_convert(
    JNIEnv* env, jobject, jlong token, jbyteArray checkpoint, jbyteArray template_dir,
    jbyteArray output_dir, jint chunk_bytes, jint hqq_iterations, jint thread_count,
    jboolean observe_progress, jobject callbacks) {
    const auto callback_class = env->GetObjectClass(callbacks);
    if (callback_class == nullptr) return nullptr;
    sd15::ConvertResult result;
    if (converting) {
        result.error.code = ErrorCode::Busy;
        return deliver_result(env, callbacks, callback_class, result);
    }

    try {
        sd15::ConvertOptions options;
        options.checkpoint = read_path(env, checkpoint);
        if (env->ExceptionCheck()) return nullptr;
        options.template_dir = read_path(env, template_dir);
        if (env->ExceptionCheck()) return nullptr;
        options.output_dir = read_path(env, output_dir);
        if (env->ExceptionCheck()) return nullptr;
        options.chunk_bytes = static_cast<std::size_t>(chunk_bytes);
        options.hqq_iterations = hqq_iterations;
        options.thread_count = static_cast<unsigned>(thread_count);

        sd15::ProgressCallback progress;
        if (observe_progress) {
            const auto method = env->GetMethodID(callback_class, "progress", "(ILjava/lang/String;JJDJJ)V");
            if (method == nullptr) return nullptr;
            progress = [env, callbacks, method](const sd15::ConvertProgress& event) {
                deliver_progress(env, callbacks, method, event);
            };
        }
        const ConversionScope scope;
        result = sd15::convert(options, progress, *reinterpret_cast<sd15::CancellationToken*>(token));
    } catch (const std::bad_alloc&) {
        result.error.code = ErrorCode::OutOfMemory;
    } catch (...) {
        result.error.code = ErrorCode::InternalError;
    }
    return deliver_result(env, callbacks, callback_class, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_pocketmuseprototype_conversion_NativeConverter_cleanupPartial(
    JNIEnv* env, jobject, jbyteArray output_dir) {
    auto error = ErrorCode::Busy;
    if (!converting) {
        try {
            sd15::ConvertOptions options;
            options.output_dir = read_path(env, output_dir);
            if (env->ExceptionCheck()) return nullptr;
            error = sd15::cleanup_partial(options).error.code;
        } catch (const std::bad_alloc&) {
            error = ErrorCode::OutOfMemory;
        } catch (...) {
            error = ErrorCode::InternalError;
        }
    }
    return env->NewStringUTF(sd15::error_code_name(error));
}
