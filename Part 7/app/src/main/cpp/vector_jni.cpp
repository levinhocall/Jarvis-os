#include <jni.h>
#include "vector_index.h"

#include <vector>

extern "C" JNIEXPORT jlong JNICALL
Java_com_jarvis_assistant_data_memory_vector_UsearchNative_nativeCreate(
    JNIEnv*, jobject, jint dimensions) {
    if (dimensions <= 0 || dimensions > 4096) return 0;
    try {
        auto* idx = new JarvisVectorIndex(static_cast<std::size_t>(dimensions));
        if (!idx->IsValid()) {
            delete idx;
            return 0;
        }
        return reinterpret_cast<jlong>(idx);
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_assistant_data_memory_vector_UsearchNative_nativeAdd(
    JNIEnv* env, jobject, jlong handle, jlong key, jfloatArray vector) {
    if (!handle || !vector) return JNI_FALSE;
    const jsize len = env->GetArrayLength(vector);
    if (len <= 0) return JNI_FALSE;
    std::vector<float> buf(static_cast<std::size_t>(len));
    env->GetFloatArrayRegion(vector, 0, len, buf.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    try {
        return reinterpret_cast<JarvisVectorIndex*>(handle)->Add(
            static_cast<std::int64_t>(key), buf.data(), static_cast<std::size_t>(len)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_assistant_data_memory_vector_UsearchNative_nativeRemove(
    JNIEnv*, jobject, jlong handle, jlong key) {
    if (!handle) return JNI_FALSE;
    try {
        return reinterpret_cast<JarvisVectorIndex*>(handle)->Remove(
            static_cast<std::int64_t>(key)) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jarvis_assistant_data_memory_vector_UsearchNative_nativeSearch(
    JNIEnv* env, jobject, jlong handle, jfloatArray query, jint limit, jlongArray outKeys, jfloatArray outDistances) {
    if (!handle || !query || !outKeys || !outDistances || limit <= 0) return 0;
    const jsize qlen = env->GetArrayLength(query);
    const jsize klen = env->GetArrayLength(outKeys);
    const jsize dlen = env->GetArrayLength(outDistances);
    if (qlen <= 0 || klen < limit || dlen < limit) return 0;

    std::vector<float> qbuf(static_cast<std::size_t>(qlen));
    env->GetFloatArrayRegion(query, 0, qlen, qbuf.data());
    if (env->ExceptionCheck()) return 0;

    std::vector<std::int64_t> keys(static_cast<std::size_t>(limit));
    std::vector<float> dists(static_cast<std::size_t>(limit));
    std::size_t found = 0;
    try {
        found = reinterpret_cast<JarvisVectorIndex*>(handle)->Search(
            qbuf.data(), static_cast<std::size_t>(qlen), static_cast<std::size_t>(limit),
            keys.data(), dists.data());
    } catch (...) {
        return 0;
    }
    if (found > 0) {
        std::vector<jlong> jkeys(found);
        for (std::size_t i = 0; i < found; ++i) jkeys[i] = static_cast<jlong>(keys[i]);
        env->SetLongArrayRegion(outKeys, 0, static_cast<jsize>(found), jkeys.data());
        env->SetFloatArrayRegion(outDistances, 0, static_cast<jsize>(found), dists.data());
    }
    return static_cast<jint>(found);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jarvis_assistant_data_memory_vector_UsearchNative_nativeSize(JNIEnv*, jobject, jlong handle) {
    if (!handle) return 0;
    return static_cast<jint>(reinterpret_cast<JarvisVectorIndex*>(handle)->Size());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_jarvis_assistant_data_memory_vector_UsearchNative_nativeDimensions(JNIEnv*, jobject, jlong handle) {
    if (!handle) return 0;
    return static_cast<jint>(reinterpret_cast<JarvisVectorIndex*>(handle)->Dimensions());
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_jarvis_assistant_data_memory_vector_UsearchNative_nativeSave(JNIEnv* env, jobject, jlong handle) {
    if (!handle) return nullptr;
    try {
        const auto bytes = reinterpret_cast<JarvisVectorIndex*>(handle)->SaveBytes();
        if (bytes.empty()) return nullptr;
        jbyteArray out = env->NewByteArray(static_cast<jsize>(bytes.size()));
        if (!out) return nullptr;
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(bytes.size()),
                                reinterpret_cast<const jbyte*>(bytes.data()));
        return out;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_jarvis_assistant_data_memory_vector_UsearchNative_nativeLoad(
    JNIEnv* env, jobject, jlong handle, jbyteArray payload) {
    if (!handle || !payload) return JNI_FALSE;
    const jsize len = env->GetArrayLength(payload);
    if (len <= 0) return JNI_FALSE;
    std::vector<std::uint8_t> buf(static_cast<std::size_t>(len));
    env->GetByteArrayRegion(payload, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    if (env->ExceptionCheck()) return JNI_FALSE;
    try {
        return reinterpret_cast<JarvisVectorIndex*>(handle)->LoadBytes(
            buf.data(), buf.size()) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_assistant_data_memory_vector_UsearchNative_nativeClose(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<JarvisVectorIndex*>(handle);
}
