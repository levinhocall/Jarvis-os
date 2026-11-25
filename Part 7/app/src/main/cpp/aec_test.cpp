#include "aec_core.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <random>
#include <vector>

namespace {

double GoertzelPower(const std::vector<int16_t>& samples, double sample_rate, double freq_hz) {
  const int n = static_cast<int>(samples.size());
  const int k = static_cast<int>(0.5 + (n * freq_hz) / sample_rate);
  const double omega = (2.0 * M_PI * k) / n;
  const double coeff = 2.0 * std::cos(omega);
  double q0 = 0.0, q1 = 0.0, q2 = 0.0;
  for (int16_t s : samples) {
    q0 = coeff * q1 - q2 + static_cast<double>(s);
    q2 = q1;
    q1 = q0;
  }
  return q1 * q1 + q2 * q2 - q1 * q2 * coeff;
}

}  // namespace

int main() {
  // 1. Delayed far-end echo attenuation + metrics verification
  constexpr int frames = 1200;
  std::mt19937 generator(17);
  std::uniform_int_distribution<int> noise(-10000, 10000);
  std::vector<int16_t> far(frames * 160);
  for (auto& sample : far) sample = static_cast<int16_t>(noise(generator));

  JarvisAec aec;
  std::array<int16_t, 160> mic{}, out{};
  double before = 0, after = 0;
  for (int frame = 0; frame < frames; frame++) {
    for (int i = 0; i < 160; i++) {
      mic[i] = frame >= 8 ? static_cast<int16_t>(far[(frame - 8) * 160 + i] * 0.65f) : 0;
    }
    aec.Render16k(far.data() + frame * 160, 80);
    aec.Capture(mic.data(), out.data());
    if (frame > 400) {
      for (int i = 0; i < 160; i++) {
        before += double(mic[i]) * mic[i];
        after += double(out[i]) * out[i];
      }
    }
  }
  const double erle_db = 10.0 * std::log10((before + 1.0) / (after + 1.0));
  const auto metrics = aec.Metrics();
  std::cout << "1. Delayed echo attenuation: " << erle_db
            << " dB (metrics ERLE=" << metrics.echo_return_loss_enhancement
            << " ERL=" << metrics.echo_return_loss
            << " delay=" << metrics.delay_ms
            << "ms render=" << metrics.render_frames
            << " cap=" << metrics.capture_frames << ")\n";
  if (!std::isfinite(erle_db) || erle_db < 12.0 || !metrics.active_processing ||
      metrics.render_frames != frames || metrics.capture_frames != frames) {
    std::cerr << "FAIL Test 1: insufficient delayed echo attenuation or metrics mismatch\n";
    return 1;
  }

  // 2. Double-talk preservation: simultaneous echo + near-end 440Hz speech
  std::vector<int16_t> near_ref, double_talk_out;
  for (int frame = 0; frame < 600; frame++) {
    for (int i = 0; i < 160; i++) {
      const int idx = frame * 160 + i;
      const double echo = (frame >= 8) ? (far[(frame - 8) * 160 + i] * 0.55) : 0.0;
      const int16_t near_sample = (frame >= 200)
          ? static_cast<int16_t>(5500.0 * std::sin(2.0 * M_PI * 440.0 * idx / 16000.0))
          : 0;
      mic[i] = static_cast<int16_t>(std::clamp(echo + near_sample, -32767.0, 32767.0));
      if (frame >= 300) near_ref.push_back(near_sample);
    }
    aec.Render16k(far.data() + frame * 160, 80);
    aec.Capture(mic.data(), out.data());
    if (frame >= 300) {
      double_talk_out.insert(double_talk_out.end(), out.begin(), out.end());
    }
  }
  const double ref_tone = GoertzelPower(near_ref, 16000.0, 440.0);
  const double out_tone = GoertzelPower(double_talk_out, 16000.0, 440.0);
  std::cout << "2. Double-talk 440Hz preservation ratio: " << (out_tone / (ref_tone + 1.0)) << "\n";
  if (out_tone < ref_tone * 0.2) {
    std::cerr << "FAIL Test 2: double-talk over-suppressed near-end speech\n";
    return 2;
  }

  // 3. Dynamic route delay shift adaptation (40ms -> 150ms mid-stream)
  aec.Reset();
  double shift_before = 0, shift_after = 0;
  for (int frame = 0; frame < frames; frame++) {
    const int delay_frames = (frame < 400) ? 4 : 15;
    const int delay_hint_ms = (frame < 400) ? 40 : 150;
    for (int i = 0; i < 160; i++) {
      mic[i] = (frame >= delay_frames) ? static_cast<int16_t>(far[(frame - delay_frames) * 160 + i] * 0.6f) : 0;
    }
    aec.Render16k(far.data() + frame * 160, delay_hint_ms);
    aec.Capture(mic.data(), out.data());
    if (frame >= 750) {
      for (int i = 0; i < 160; i++) {
        shift_before += double(mic[i]) * mic[i];
        shift_after += double(out[i]) * out[i];
      }
    }
  }
  const double shift_erle_db = 10.0 * std::log10((shift_before + 1.0) / (shift_after + 1.0));
  std::cout << "3. Post-route-jump attenuation (40ms->150ms): " << shift_erle_db << " dB\n";
  if (!std::isfinite(shift_erle_db) || shift_erle_db < 10.0) {
    std::cerr << "FAIL Test 3: failed to re-converge after route delay step\n";
    return 3;
  }

  // 4. Non-linear speaker clipping distortion + saturation telemetry
  aec.Reset();
  double nl_before = 0, nl_after = 0;
  for (int frame = 0; frame < 800; frame++) {
    for (int i = 0; i < 160; i++) {
      const double x = (frame >= 6) ? (far[(frame - 6) * 160 + i] / 5000.0) : 0.0;
      mic[i] = static_cast<int16_t>(std::tanh(x) * 6500.0);
    }
    aec.Render16k(far.data() + frame * 160, 60);
    aec.Capture(mic.data(), out.data());
    if (frame > 350) {
      for (int i = 0; i < 160; i++) {
        nl_before += double(mic[i]) * mic[i];
        nl_after += double(out[i]) * out[i];
      }
    }
  }
  // Verify mic saturation counter triggers accurately
  mic.fill(32767);
  aec.Capture(mic.data(), out.data());
  const auto sat_metrics = aec.Metrics();
  const double nl_erle_db = 10.0 * std::log10((nl_before + 1.0) / (nl_after + 1.0));
  std::cout << "4. Non-linear echo attenuation: " << nl_erle_db
            << " dB, sat_frames=" << sat_metrics.saturated_capture_frames << "\n";
  if (!std::isfinite(nl_erle_db) || nl_erle_db < 6.0 || sat_metrics.saturated_capture_frames == 0) {
    std::cerr << "FAIL Test 4: non-linear clipping or saturation telemetry failed\n";
    return 4;
  }

  // 5. Reset + 24-to-16kHz render resampling + near-end speech preservation
  aec.Reset();
  std::array<int16_t, 240> silence{};
  before = 0;
  after = 0;
  for (int frame = 0; frame < 500; frame++) {
    for (int i = 0; i < 160; i++) {
      mic[i] = static_cast<int16_t>(6000.0 * std::sin((frame * 160 + i) * 0.13));
    }
    aec.Render24k(silence.data(), 0);
    aec.Capture(mic.data(), out.data());
    if (frame > 100) {
      for (int i = 0; i < 160; i++) {
        before += double(mic[i]) * mic[i];
        after += double(out[i]) * out[i];
      }
    }
  }
  if (after < before * 0.25) {
    std::cerr << "FAIL Test 5: Near-end signal was suppressed excessively\n";
    return 5;
  }
  std::cout << "5. PASS reset, 24-to-16k render resampling and near-end preservation\n";
  return 0;
}
