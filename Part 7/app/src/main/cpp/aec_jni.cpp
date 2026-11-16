#include <jni.h>
#include "aec_core.h"

static void Failure(JNIEnv* env) {
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),"AEC3 native processing failed");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_jarvis_assistant_audio_Aec3Native_nativeCreate(JNIEnv*,jobject) {
  try { return reinterpret_cast<jlong>(new JarvisAec()); } catch (...) { return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_assistant_audio_Aec3Native_nativeRender(JNIEnv* env,jobject,jlong handle,jshortArray input,jint delay) {
  if(!handle || env->GetArrayLength(input)!=240) return;
  std::array<int16_t,240> block{};env->GetShortArrayRegion(input,0,240,block.data());
  if(!env->ExceptionCheck()) try { reinterpret_cast<JarvisAec*>(handle)->Render24k(block.data(),delay); }
  catch (...) { Failure(env); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_assistant_audio_Aec3Native_nativeCapture(JNIEnv* env,jobject,jlong handle,jshortArray input,jshortArray output) {
  if(!handle || env->GetArrayLength(input)!=160 || env->GetArrayLength(output)!=160) return;
  std::array<int16_t,160> in{},out{};env->GetShortArrayRegion(input,0,160,in.data());
  if(env->ExceptionCheck()) return;
  try { reinterpret_cast<JarvisAec*>(handle)->Capture(in.data(),out.data()); }
  catch (...) { Failure(env);return; }
  env->SetShortArrayRegion(output,0,160,out.data());
}
extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_assistant_audio_Aec3Native_nativeReset(JNIEnv* env,jobject,jlong handle) {
  if(handle) try { reinterpret_cast<JarvisAec*>(handle)->Reset(); } catch (...) { Failure(env); }
}
extern "C" JNIEXPORT void JNICALL
Java_com_jarvis_assistant_audio_Aec3Native_nativeClose(JNIEnv*,jobject,jlong handle) {
  delete reinterpret_cast<JarvisAec*>(handle);
}
