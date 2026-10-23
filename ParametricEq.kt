package com.cleanboost

import kotlin.math.*

/**
 * True parametric EQ math (RBJ Audio-EQ-Cookbook biquads).
 *
 * Android's DynamicsProcessing EQ is a multi-band gain stage, not a set of biquads, so we
 * compute the exact magnitude response of the user's parametric filters and sample it onto
 * a dense, log-spaced bank of DynamicsProcessing bands. With 48 bands (~1/5 octave) the
 * applied curve tracks the designed curve closely.
 */
object ParametricEq {
    const val SAMPLE_RATE = 48000.0
    const val ENGINE_BANDS = 48
    private const val F_MIN = 20.0
    private const val F_MAX = 20000.0

    /** Upper edge (cutoff) of each engine band, log spaced, last band ends at 20 kHz. */
    val bandEdges: FloatArray = FloatArray(ENGINE_BANDS) { i ->
        (F_MIN * (F_MAX / F_MIN).pow((i + 1).toDouble() / ENGINE_BANDS)).toFloat()
    }

    /** Geometric centre of each engine band — where we sample the parametric curve. */
    val bandCenters: FloatArray = FloatArray(ENGINE_BANDS) { i ->
        val lo = if (i == 0) F_MIN else bandEdges[i - 1].toDouble()
        sqrt(lo * bandEdges[i]).toFloat()
    }

    private data class Biquad(val b0: Double, val b1: Double, val b2: Double,
                              val a0: Double, val a1: Double, val a2: Double)

    private fun design(b: PeqBand): Biquad {
        val w0 = 2.0 * PI * b.freq.coerceIn(10f, 22000f) / SAMPLE_RATE
        val cosw = cos(w0); val sinw = sin(w0)
        val q = b.q.coerceIn(0.1f, 20f).toDouble()
        val alpha = sinw / (2.0 * q)
        val A = 10.0.pow(b.gain / 40.0)
        return when (b.type) {
            FilterType.PEAK -> Biquad(
                1 + alpha * A, -2 * cosw, 1 - alpha * A,
                1 + alpha / A, -2 * cosw, 1 - alpha / A)
            FilterType.LOW_SHELF -> {
                val s = 2 * sqrt(A) * alpha
                Biquad(
                    A * ((A + 1) - (A - 1) * cosw + s), 2 * A * ((A - 1) - (A + 1) * cosw),
                    A * ((A + 1) - (A - 1) * cosw - s),
                    (A + 1) + (A - 1) * cosw + s, -2 * ((A - 1) + (A + 1) * cosw),
                    (A + 1) + (A - 1) * cosw - s)
            }
            FilterType.HIGH_SHELF -> {
                val s = 2 * sqrt(A) * alpha
                Biquad(
                    A * ((A + 1) + (A - 1) * cosw + s), -2 * A * ((A - 1) + (A + 1) * cosw),
                    A * ((A + 1) + (A - 1) * cosw - s),
                    (A + 1) - (A - 1) * cosw + s, 2 * ((A - 1) - (A + 1) * cosw),
                    (A + 1) - (A - 1) * cosw - s)
            }
            FilterType.HIGH_PASS -> Biquad(
                (1 + cosw) / 2, -(1 + cosw), (1 + cosw) / 2,
                1 + alpha, -2 * cosw, 1 - alpha)
            FilterType.LOW_PASS -> Biquad(
                (1 - cosw) / 2, 1 - cosw, (1 - cosw) / 2,
                1 + alpha, -2 * cosw, 1 - alpha)
        }
    }

    /** |H(f)| in dB of one biquad. */
    private fun magnitudeDb(bq: Biquad, f: Double): Double {
        val w = 2.0 * PI * f / SAMPLE_RATE
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = bq.b0 + bq.b1 * c1 + bq.b2 * c2; val ni = -(bq.b1 * s1 + bq.b2 * s2)
        val dr = bq.a0 + bq.a1 * c1 + bq.a2 * c2; val di = -(bq.a1 * s1 + bq.a2 * s2)
        val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
        return 10.0 * log10(max(mag2, 1e-12))
    }

    /** Total response (dB) of all enabled bands at frequency f. */
    fun responseDb(bands: List<PeqBand>, f: Double): Double =
        bands.filter { it.enabled }.sumOf { magnitudeDb(design(it), f) }

    /** Gains (dB) to program into the engine bands. Clamped to a sane range. */
    fun engineGains(bands: List<PeqBand>): FloatArray =
        FloatArray(ENGINE_BANDS) { i ->
            responseDb(bands, bandCenters[i].toDouble()).coerceIn(-48.0, 24.0).toFloat()
        }
}
