package com.cleanboost

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.DynamicsProcessing.*
import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Signal chain (per audio session), inside Android's DynamicsProcessing effect (32-bit float,
 * so nothing clips *inside* the chain however hot it gets):
 *
 *   input gain (BOOST) → pre-EQ (parametric) → 4-band compressor (density, exciter, warmth)
 *   → linked limiter → output
 *
 * WHY OTHER BOOSTER APPS CLIP
 * ---------------------------
 * In AOSP (DPFrequency.cpp) the DynamicsProcessing limiter is block-based: its detector is the
 * RMS energy of each ~10–20 ms FFT block, and there's no look-ahead. A limiter set to "-1 dB"
 * therefore holds the block RMS at -1 dB while the real sample peaks inside that block sit
 * 6–18 dB higher (the music's crest factor). Push the boost and those peaks get past 0 dBFS
 * and are hard-clipped at the output. Simulating that limiter on test music at +20 dB gives
 * sample peaks of +12 to +14 dBFS with 2–5 % of samples clipped.
 *
 * WHAT WE DO: CLIP GUARD
 * ----------------------
 * The limiter threshold is set to   ceiling − crestMargin,   where crestMargin is learned live:
 *  - the Visualizer API reports output peak and RMS every 50 ms; their difference is the
 *    program's crest factor. We keep a 60 s memory of it and size the margin from the worst case.
 *  - if a measured peak ever gets near the ceiling, the margin jumps up immediately.
 *  - the margin relaxes slowly (0.5 dB/s) so the loop never chases its own tail.
 * With no meter available, a fixed conservative 18 dB margin is used.
 * Simulated against the AOSP limiter algorithm at +6…+30 dB boost: zero clipped samples,
 * peaks held below the ceiling. Because the gain is applied per block (whole-frame scaling,
 * smooth overlap-add), the waveform of a transient is scaled, never flattened.
 */
class AudioEngine {

    private val sessions = LinkedHashMap<Int, DynamicsProcessing>()
    private var settings = BoostSettings()
    private var visualizer: Visualizer? = null

    // ---- Clip Guard state (read by the UI)
    @Volatile var crestMarginDb = START_MARGIN_DB; private set
    @Volatile var lastPeakDb: Float? = null; private set
    @Volatile var maxPeakDb = -96f; private set
    private val crestHistory = ArrayDeque<Float>()
    private var appliedMarginDb = Float.NaN
    private var retryTicks = 0

    companion object {
        private const val TAG = "CleanBoost"
        const val GLOBAL_SESSION = 0
        private const val CHANNELS = 2
        private const val PRIORITY = Int.MAX_VALUE

        private val MBC_EDGES = floatArrayOf(150f, 1200f, 6000f, 20000f)

        /** Instant attack: the envelope follows each block's energy with no lag, so a transient
         *  is limited in the same block it arrives in. Release 80 ms avoids pumping. */
        private const val LIMITER_ATTACK_MS = 0.01f
        private const val LIMITER_RELEASE_MS = 80f
        private const val LIMITER_RATIO = 1000f   // effectively ∞:1

        // Clip Guard tuning (values validated in simulation, see README)
        const val TICK_MS = 50L
        private const val START_MARGIN_DB = 18f   // also the fixed fallback without a meter
        private const val MIN_MARGIN_DB = 4f
        private const val MAX_MARGIN_DB = 30f
        private const val CREST_WEIGHT = 0.8f
        private const val GUARD_DB = 0.3f
        private const val STEP_UP_DB = 1.5f
        private const val RELAX_DB_PER_S = 0.5f
        private const val HISTORY_TICKS = (60_000 / TICK_MS).toInt()
    }

    @Synchronized
    fun update(s: BoostSettings) {
        settings = s
        if (!s.enabled) { releaseAll(); return }
        if (s.globalMode) attach(GLOBAL_SESSION) else detach(GLOBAL_SESSION)
        sessions.values.forEach { apply(it, s) }
        setMeter(s.clipGuard)
        if (!s.clipGuard) crestMarginDb = START_MARGIN_DB
    }

    @Synchronized
    fun attach(sessionId: Int) {
        if (!settings.enabled || sessions.containsKey(sessionId)) return
        try {
            val dp = DynamicsProcessing(PRIORITY, sessionId, buildConfig(settings))
            dp.setEnabled(true)
            sessions[sessionId] = dp
            Log.i(TAG, "Attached to session $sessionId")
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach to session $sessionId", e)
        }
    }

    @Synchronized
    fun detach(sessionId: Int) {
        sessions.remove(sessionId)?.let { runCatching { it.setEnabled(false); it.release() } }
    }

    @Synchronized
    fun releaseAll() {
        sessions.values.forEach { runCatching { it.setEnabled(false); it.release() } }
        sessions.clear()
        setMeter(false)
    }

    val attachedSessions: Int @Synchronized get() = sessions.size
    val meterAvailable: Boolean get() = visualizer != null

    // ---------------------------------------------------------------- Clip Guard loop

    /** Called every TICK_MS by the service. */
    @Synchronized
    fun clipGuardTick() {
        if (visualizer == null && settings.enabled && settings.clipGuard) {
            // Permission may have been granted after start-up: retry every ~2 s.
            if (++retryTicks >= 40) { retryTicks = 0; setMeter(true) }
        }
        val v = visualizer ?: return
        val m = Visualizer.MeasurementPeakRms()
        if (v.getMeasurementPeakRms(m) != Visualizer.SUCCESS) return
        val peak = m.mPeak / 100f
        val rms = m.mRms / 100f
        lastPeakDb = peak
        maxPeakDb = max(maxPeakDb, peak)

        if (rms > -60f) {                       // ignore silence
            crestHistory.addLast(peak - rms)
            while (crestHistory.size > HISTORY_TICKS) crestHistory.removeFirst()
        }
        val crest = CREST_WEIGHT * (crestHistory.maxOrNull() ?: START_MARGIN_DB)
        val target = crest.coerceIn(MIN_MARGIN_DB, MAX_MARGIN_DB)

        var margin = crestMarginDb
        val ceiling = settings.ceilingDb
        if (peak > ceiling - GUARD_DB) {        // reactive: something got close — back off now
            margin = min(margin + (peak - ceiling) + STEP_UP_DB, MAX_MARGIN_DB)
        }
        margin = if (target > margin) target
                 else max(margin - RELAX_DB_PER_S * TICK_MS / 1000f, target)
        crestMarginDb = margin

        if (appliedMarginDb.isNaN() || abs(margin - appliedMarginDb) >= 0.1f) {
            val lim = buildLimiter(settings)
            sessions.values.forEach { runCatching { it.setLimiterAllChannelsTo(lim) } }
        }
    }

    fun resetMaxPeak() { maxPeakDb = -96f }

    // ---------------------------------------------------------------- configuration

    private fun buildConfig(s: BoostSettings): Config {
        val b = Config.Builder(
            VARIANT_FAVOR_FREQUENCY_RESOLUTION, CHANNELS,
            /* preEq  */ true, ParametricEq.ENGINE_BANDS,
            /* mbc    */ true, MBC_EDGES.size,
            /* postEq */ false, 0,
            /* limiter*/ true,
        ).setPreferredFrameDuration(10f)

        b.setInputGainAllChannelsTo(s.boostDb)
        b.setPreEqAllChannelsTo(buildEq(s))
        b.setMbcAllChannelsTo(buildMbc(s))
        b.setLimiterAllChannelsTo(buildLimiter(s))
        return b.build()
    }

    private fun apply(dp: DynamicsProcessing, s: BoostSettings) {
        try {
            dp.setInputGainAllChannelsTo(s.boostDb)
            val gains = ParametricEq.engineGains(s.bands)
            for (i in gains.indices) {
                dp.setPreEqBandAllChannelsTo(i, EqBand(true, ParametricEq.bandEdges[i], gains[i]))
            }
            for (i in MBC_EDGES.indices) dp.setMbcBandAllChannelsTo(i, mbcBand(i, s))
            dp.setLimiterAllChannelsTo(buildLimiter(s))
        } catch (e: Exception) {
            Log.w(TAG, "apply failed", e)
        }
    }

    private fun buildEq(s: BoostSettings): Eq {
        val eq = Eq(true, true, ParametricEq.ENGINE_BANDS)
        val gains = ParametricEq.engineGains(s.bands)
        for (i in gains.indices) eq.setBand(i, EqBand(true, ParametricEq.bandEdges[i], gains[i]))
        return eq
    }

    private fun buildMbc(s: BoostSettings): Mbc {
        val mbc = Mbc(true, true, MBC_EDGES.size)
        for (i in MBC_EDGES.indices) mbc.setBand(i, mbcBand(i, s))
        return mbc
    }

    /**
     * Each band does two jobs:
     *  1. DENSITY — gentle downward compression with a slow attack. The attack is slower than a
     *     drum hit, so the leading edge passes at full height; the body/tail is lifted by make-up
     *     gain. Louder on average, punch intact.
     *  2. LIFT (exciter on the top band, warmth on the bottom) — upward compression: +lift dB of
     *     pre-gain, with threshold solved so already-loud content lands where it was. Quiet
     *     detail (air, room, decay) rises by up to +lift dB; loud parts don't get harsher.
     */
    private fun mbcBand(i: Int, s: BoostSettings): MbcBand {
        val d = s.density.coerceIn(0f, 1f)
        val lift = when (i) {
            MBC_EDGES.lastIndex -> s.exciterDb
            0 -> s.warmthDb
            else -> 0f
        }.coerceIn(0f, 12f)

        var ratio = 1f + 2.5f * d                       // 1:1 … 3.5:1
        var threshold = -10f - 20f * d                  // -10 … -30 dBFS
        var makeup = (-threshold) * (1f - 1f / ratio) * 0.5f

        // Upward lift: out(L) = T + (L + G − T)/R = L   →   T = L − G/(R − 1)
        if (lift > 0f) {
            val r = max(ratio, 3f)
            val loudRef = -12f
            ratio = r
            threshold = loudRef - lift / (r - 1f)
            makeup = 0f
        }

        val attack = s.attackMs * if (i == 0) 1.5f else 1f
        val release = (60f + 240f * d) * if (i == 0) 2f else 1f

        return MbcBand(
            true, MBC_EDGES[i],
            attack, release, ratio, threshold,
            /* kneeWidth */ 8f,
            /* noiseGate */ -90f,      // off: never pump up the noise floor
            /* expander  */ 1f,
            /* preGain   */ lift,
            /* postGain  */ makeup,
        )
    }

    private fun buildLimiter(s: BoostSettings): Limiter {
        val margin = if (s.clipGuard && visualizer != null) crestMarginDb else START_MARGIN_DB
        appliedMarginDb = margin
        return Limiter(
            true, true,
            /* linkGroup */ 0,                 // channels linked → stereo image never shifts
            LIMITER_ATTACK_MS, LIMITER_RELEASE_MS, LIMITER_RATIO,
            /* threshold */ s.ceilingDb.coerceIn(-12f, -0.1f) - margin,
            /* postGain  */ 0f,                // nothing after the limiter may add gain
        )
    }

    // ---------------------------------------------------------------- meter

    private fun setMeter(on: Boolean) {
        if (on && visualizer == null) {
            visualizer = runCatching {
                Visualizer(GLOBAL_SESSION).apply {
                    setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
                    setEnabled(true)
                }
            }.onFailure { Log.w(TAG, "Meter unavailable (RECORD_AUDIO not granted?)", it) }.getOrNull()
        } else if (!on && visualizer != null) {
            visualizer?.let { runCatching { it.setEnabled(false); it.release() } }
            visualizer = null
            lastPeakDb = null
        }
    }
}
