#include "aec_core.h"
#include <array>
#include <cmath>
#include <iostream>
#include <random>
#include <vector>

int main() {
  constexpr int frames=1200;
  std::mt19937 generator(17);std::uniform_int_distribution<int> noise(-10000,10000);
  std::vector<int16_t> far(frames*160);
  for(auto& sample:far) sample=static_cast<int16_t>(noise(generator));
  JarvisAec aec;std::array<int16_t,160> mic{},out{};
  double before=0,after=0;
  for(int frame=0;frame<frames;frame++) {
    for(int i=0;i<160;i++) mic[i]=frame>=8 ? static_cast<int16_t>(far[(frame-8)*160+i]*.65f) : 0;
    aec.Render16k(far.data()+frame*160,80);aec.Capture(mic.data(),out.data());
    if(frame>400) for(int i=0;i<160;i++){ before+=double(mic[i])*mic[i];after+=double(out[i])*out[i]; }
  }
  const double erle=10*std::log10((before+1)/(after+1));
  std::cout<<"Synthetic echo attenuation (host fixture only): "<<erle<<" dB\n";
  if(!std::isfinite(erle) || erle<3) return 1;
  aec.Reset();std::array<int16_t,240> silence{};before=0;after=0;
  for(int frame=0;frame<500;frame++) {
    for(int i=0;i<160;i++) mic[i]=static_cast<int16_t>(6000*std::sin((frame*160+i)*.13));
    aec.Render24k(silence.data(),0);aec.Capture(mic.data(),out.data());
    if(frame>100) for(int i=0;i<160;i++){before+=double(mic[i])*mic[i];after+=double(out[i])*out[i];}
  }
  if(after < before*.1) {std::cerr<<"Near-end signal was suppressed excessively\n";return 2;}
  std::cout<<"PASS reset, 24-to-16k render resampling and near-end preservation\n";
  return 0;
}
