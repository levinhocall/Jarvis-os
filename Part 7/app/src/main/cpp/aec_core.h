#pragma once
#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include "api/environment.h"
#include "api/echo_control.h"
#include "audio_processing/audio_buffer.h"
#include "audio_processing/resampler/push_sinc_resampler.h"

struct JarvisAecMetrics {
  double echo_return_loss = 0.0;
  double echo_return_loss_enhancement = 0.0;
  int32_t delay_ms = 0;
  bool active_processing = false;
  uint64_t render_frames = 0;
  uint64_t capture_frames = 0;
  uint64_t saturated_capture_frames = 0;
};

class JarvisAec {
 public:
  JarvisAec();
  void Render24k(const int16_t* input, int delay_ms);
  void Render16k(const int16_t* input, int delay_ms);
  void Capture(const int16_t* input, int16_t* output);
  void Reset();
  JarvisAecMetrics Metrics() const;
 private:
  void RenderLocked(const int16_t* input, int delay_ms);
  mutable std::mutex mutex_;
  webrtc::Environment environment_;
  std::unique_ptr<webrtc::EchoControl> echo_;
  webrtc::AudioBuffer render_{16000,1,16000,1,16000,1};
  webrtc::AudioBuffer capture_{16000,1,16000,1,16000,1};
  std::unique_ptr<webrtc::PushSincResampler> resampler_;
  std::array<int16_t,160> reference_{};
  int pending_delay_ms_ = 0;
  bool has_pending_delay_ = false;
  uint64_t render_frames_ = 0;
  uint64_t capture_frames_ = 0;
  uint64_t saturated_capture_frames_ = 0;
};
