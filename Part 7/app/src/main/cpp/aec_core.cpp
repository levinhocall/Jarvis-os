#include "aec_core.h"
#include <algorithm>
#include <cmath>
#include "api/echo_canceller3_factory.h"

JarvisAec::JarvisAec() { Reset(); }
void JarvisAec::Reset() {
  std::lock_guard<std::mutex> lock(mutex_);
  webrtc::EchoCanceller3Config config;
  echo_ = webrtc::EchoCanceller3Factory(config).Create(environment_,16000,1,1);
  resampler_ = std::make_unique<webrtc::PushSincResampler>(240,160);
}
void JarvisAec::RenderLocked(const int16_t* input, int delay_ms) {
  auto channel = render_.channels()[0];
  for (int i=0;i<160;i++) channel[i]=static_cast<float>(input[i]);
  echo_->SetAudioBufferDelay(std::clamp(delay_ms,0,500));
  echo_->AnalyzeRender(&render_);
}
void JarvisAec::Render24k(const int16_t* input, int delay_ms) {
  std::lock_guard<std::mutex> lock(mutex_);
  resampler_->Resample(input,240,reference_.data(),reference_.size());
  RenderLocked(reference_.data(),delay_ms);
}
void JarvisAec::Render16k(const int16_t* input, int delay_ms) {
  std::lock_guard<std::mutex> lock(mutex_); RenderLocked(input,delay_ms);
}
void JarvisAec::Capture(const int16_t* input,int16_t* output) {
  std::lock_guard<std::mutex> lock(mutex_);
  auto channel=capture_.channels()[0];
  for(int i=0;i<160;i++) channel[i]=static_cast<float>(input[i]);
  echo_->AnalyzeCapture(&capture_); echo_->ProcessCapture(&capture_,false);
  for(int i=0;i<160;i++) output[i]=std::isfinite(channel[i]) ?
      static_cast<int16_t>(std::clamp(channel[i],-32768.f,32767.f)) : 0;
}
