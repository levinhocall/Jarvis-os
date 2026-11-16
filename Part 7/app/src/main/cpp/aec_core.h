#pragma once
#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include "api/environment.h"
#include "api/echo_control.h"
#include "audio_processing/audio_buffer.h"
#include "audio_processing/resampler/push_sinc_resampler.h"

class JarvisAec {
 public:
  JarvisAec();
  void Render24k(const int16_t* input, int delay_ms);
  void Render16k(const int16_t* input, int delay_ms);
  void Capture(const int16_t* input, int16_t* output);
  void Reset();
 private:
  void RenderLocked(const int16_t* input, int delay_ms);
  std::mutex mutex_;
  webrtc::Environment environment_;
  std::unique_ptr<webrtc::EchoControl> echo_;
  webrtc::AudioBuffer render_{16000,1,16000,1,16000,1};
  webrtc::AudioBuffer capture_{16000,1,16000,1,16000,1};
  std::unique_ptr<webrtc::PushSincResampler> resampler_;
  std::array<int16_t,160> reference_{};
};
