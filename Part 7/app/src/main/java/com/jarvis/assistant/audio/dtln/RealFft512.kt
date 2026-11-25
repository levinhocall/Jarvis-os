package com.jarvis.assistant.audio.dtln

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zero-allocation 512-point real FFT / inverse FFT processor (257 complex frequency bins,
 * rectangular analysis window) matching `breizhn/DTLN-aec` `run_aec.py` (`np.fft.rfft` / `irfft`).
 */
class RealFft512 {
    companion object {
        const val FFT_SIZE = 512
        const val SPECTRUM_BINS = FFT_SIZE / 2 + 1 // 257
        private const val LOG2_N = 9
    }

    private val bitRev = IntArray(FFT_SIZE)
    private val stageCos = FloatArray(FFT_SIZE / 2)
    private val stageSin = FloatArray(FFT_SIZE / 2)

    private val workRe = FloatArray(FFT_SIZE)
    private val workIm = FloatArray(FFT_SIZE)

    init {
        for (i in 0 until FFT_SIZE) {
            var rev = 0
            var x = i
            for (b in 0 until LOG2_N) {
                rev = (rev shl 1) or (x and 1)
                x = x ushr 1
            }
            bitRev[i] = rev
        }
        val step = -2.0 * PI / FFT_SIZE
        for (k in 0 until FFT_SIZE / 2) {
            val angle = k * step
            stageCos[k] = cos(angle).toFloat()
            stageSin[k] = sin(angle).toFloat()
        }
    }

    /** Computes forward real-to-complex FFT (`k = 0..256`) without heap allocations. */
    fun rfft(realIn: FloatArray, outRe: FloatArray, outIm: FloatArray, outMag: FloatArray? = null) {
        require(realIn.size >= FFT_SIZE && outRe.size >= SPECTRUM_BINS && outIm.size >= SPECTRUM_BINS)
        if (outMag != null) require(outMag.size >= SPECTRUM_BINS)

        for (i in 0 until FFT_SIZE) {
            workRe[i] = realIn[i]
            workIm[i] = 0f
        }
        fftInPlace(workRe, workIm)

        for (k in 0 until SPECTRUM_BINS) {
            val re = workRe[k]
            val im = workIm[k]
            outRe[k] = re
            outIm[k] = im
            if (outMag != null) {
                outMag[k] = sqrt(re * re + im * im)
            }
        }
    }

    /** Computes conjugate-symmetric inverse real FFT from `257` masked complex bins (`irfft`). */
    fun irfft(inRe: FloatArray, inIm: FloatArray, realOut: FloatArray) {
        require(inRe.size >= SPECTRUM_BINS && inIm.size >= SPECTRUM_BINS && realOut.size >= FFT_SIZE)

        // Conjugating before and after the forward radix-2 FFT yields exact IFFT without duplicating code.
        workRe[0] = inRe[0]
        workIm[0] = 0f
        workRe[FFT_SIZE / 2] = inRe[FFT_SIZE / 2]
        workIm[FFT_SIZE / 2] = 0f
        for (k in 1 until FFT_SIZE / 2) {
            workRe[k] = inRe[k]
            workIm[k] = -inIm[k]
            workRe[FFT_SIZE - k] = inRe[k]
            workIm[FFT_SIZE - k] = inIm[k]
        }
        fftInPlace(workRe, workIm)

        val invN = 1.0f / FFT_SIZE
        for (n in 0 until FFT_SIZE) {
            realOut[n] = workRe[n] * invN
        }
    }

    private fun fftInPlace(re: FloatArray, im: FloatArray) {
        for (i in 0 until FFT_SIZE) {
            val j = bitRev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var halfSize = 1
        var stride = FFT_SIZE / 2
        while (halfSize < FFT_SIZE) {
            val step = halfSize shl 1
            var i = 0
            while (i < FFT_SIZE) {
                var twIdx = 0
                for (j in 0 until halfSize) {
                    val idx1 = i + j
                    val idx2 = idx1 + halfSize
                    val c = stageCos[twIdx]
                    val s = stageSin[twIdx]
                    val r2 = re[idx2]
                    val i2 = im[idx2]
                    val tRe = r2 * c - i2 * s
                    val tIm = r2 * s + i2 * c
                    re[idx2] = re[idx1] - tRe
                    im[idx2] = im[idx1] - tIm
                    re[idx1] = re[idx1] + tRe
                    im[idx1] = im[idx1] + tIm
                    twIdx += stride
                }
                i += step
            }
            halfSize = step
            stride = stride ushr 1
        }
    }
}
