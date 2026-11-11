package com.jarvis.assistant.ui.orb

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class VoiceCoreAudioTest {
    @Test fun initialStateIsReadyForStaticPreviews(){val m=OrbMotion(OrbState.MUTED);assertEquals(1f,m.mix[6],0f);assertEquals(0f,m.mix[0],0f)}
    @Test fun colorsMixInLinearLight(){val linear=OrbMotion.mixLinear(0f,1f,.5f);assertEquals(.5f,linear,.0001f);assertEquals(.735f,OrbMotion.srgb(linear),.002f)}
    @Test fun normalizationGatesAndBounds(){assertEquals(0f,SpectrumAnalyzer.normalize(0f),0f);assertEquals(0f,SpectrumAnalyzer.normalize(.0001f),0f);assertEquals(1f,SpectrumAnalyzer.normalize(1f),0f);assertEquals(0f,SpectrumAnalyzer.normalize(Float.NaN),0f)}
    @Test fun attackIsFasterThanRelease(){val up=OrbMotion.smoothLevel(0f,1f,.05f);val down=OrbMotion.smoothLevel(1f,0f,.05f);assertTrue(up>.6f);assertTrue(down>.8f)}
    @Test fun sineEnergyMapsToTheRightBand(){
        for((hz,band) in listOf(125 to 0,1000 to 1,4000 to 2)){
            val analyzer=SpectrumAnalyzer();val pcm=ShortArray(1024){(sin(2*PI*hz*it/16000)*12000).toInt().toShort()}
            var result=OrbAudioLevels.Zero;repeat(10){result=analyzer.analyze(pcm)}
            val bands=floatArrayOf(result.bass,result.mid,result.treble)
            assertTrue("$hz dominant",bands[band]>.6f)
            for(i in 0..2)if(i!=band)assertTrue("$hz leakage",bands[i]<.2f)
        }
    }
    @Test fun thinkingIgnoresAudioAndWakeSettles(){val m=OrbMotion();repeat(30){m.step(.02f,OrbState.WAKE,OrbAudioLevels.Zero,false)};assertTrue(m.mix[2]>.7f);m.step(.05f,OrbState.THINKING,OrbAudioLevels(1f,1f,1f,1f),false);assertEquals(0f,m.rms,0f)}
}
