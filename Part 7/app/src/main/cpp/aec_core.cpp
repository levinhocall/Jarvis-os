#include "aec_core.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include "api/echo_canceller3_factory.h"

JarvisAec::JarvisAec() { Reset(); }

void JarvisAec::Reset() {
  std::lock_guard<std::mutex> lock(mutex_);
  webrtc::EchoCanceller3Config config;
  echo_ = webrtc::EchoCanceller3Factory(config).Create(environment_, 16000, 1, 1);
  if (echo_) {
    echo_->SetCaptureOutputUsage(true);
  }
  resampler_ = std::make_unique<webrtc::PushSincResampler>(240, 160);
  pending_delay_ms_ = 0;
  has_pending_delay_ = false;
  render_frames_ = 0;
  capture_frames_ = 0;
  saturated_capture_frames_ = 0;
}

void JarvisAec::RenderLocked(const int16_t* input, int delay_ms) {
  auto channel = render_.channels()[0];
  for (int i = 0; i < 160; i++) channel[i] = static_cast<float>(input[i]);
  pending_delay_ms_ = std::clamp(delay_ms, 0, 500);
  has_pending_delay_ = true;
  render_frames_++;
  if (echo_) {
    echo_->AnalyzeRender(&render_);
  }
}

void JarvisAec::Render24k(const int16_t* input, int delay_ms) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!input || !resampler_) return;
  resampler_->Resample(input, 240, reference_.data(), reference_.size());
  RenderLocked(reference_.data(), delay_ms);
}

void JarvisAec::Render16k(const int16_t* input, int delay_ms) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!input) return;
  RenderLocked(input, delay_ms);
}

void JarvisAec::Capture(const int16_t* input, int16_t* output) {
  std::lock_guard<std::mutex> lock(mutex_);
  if (!input || !output) return;
  auto channel = capture_.channels()[0];
  bool saturated = false;
  for (int i = 0; i < 160; i++) {
    int16_t s = input[i];
    if (s >= 32700 || s <= -32700) saturated = true;
    channel[i] = static_cast<float>(s);
  }
  if (saturated) saturated_capture_frames_++;
  if (echo_) {
    // WebRTC EchoCanceller3::SetAudioBufferDelay checks capture_race_checker_.
    if (has_pending_delay_) {
      echo_->SetAudioBufferDelay(pending_delay_ms_);
    }
    echo_->SetCaptureOutputUsage(true);
    echo_->AnalyzeCapture(&capture_);
    echo_->ProcessCapture(&capture_, false);
  }
  capture_frames_++;
  for (int i = 0; i < 160; i++) {
    output[i] = std::isfinite(channel[i])
        ? static_cast<int16_t>(std::clamp(channel[i], -32768.f, 32767.f))
        : 0;
  }
}

JarvisAecMetrics JarvisAec::Metrics() const {
  std::lock_guard<std::mutex> lock(mutex_);
  JarvisAecMetrics out{};
  if (echo_) {
    auto m = echo_->GetMetrics();
    out.echo_return_loss = std::isfinite(m.echo_return_loss) ? m.echo_return_loss : 0.0;
    out.echo_return_loss_enhancement = std::isfinite(m.echo_return_loss_enhancement) ? m.echo_return_loss_enhancement : 0.0;
    out.delay_ms = m.delay_ms;
    out.active_processing = echo_->ActiveProcessing();
  }
  out.render_frames = render_frames_;
  out.capture_frames = capture_frames_;
  out.saturated_capture_frames = saturated_capture_frames_;
  return out;
}
