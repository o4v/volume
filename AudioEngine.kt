package com.cleanboost

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.DynamicsProcessing.*
import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Signal chain (per audio session), inside Android's DynamicsProcessing effect (32-bit float):
 *
 *   input gain (auto, up to the BOOST slider) → pre-EQ (parametric) → 4-band compressor
 *   (density, exciter, warmth) → safety-net limiter → output
 *
 * WHY A PLAIN LIMITER ISN'T ENOUGH
 * AOSP's DynamicsProcessing limiter (DPFrequency.cpp) measures the RMS energy of 10–20 ms
 * blocks, with no look-ahead. Sample peaks sit 6–18 dB above that RMS, so "limit at −1 dB"
 * still lets peaks reach +12 dBFS and clip. Lowering the limiter enough to stop that
 * squashes loud songs and makes everything QUIETER.
 *
 * CLIP GUARD = MEASURED GAIN CONTROL
 * The Visualizer API reports the real output peak every 50 ms. The boost actually applied is
 *   gain = min(slider, ceiling − 0.5 dB − loudest source peak in the last 30 s)
 * so the music is raised exactly as far as it has room to go, and never below 0 dB
 * (the app never makes anything quieter). The gain drops immediately if a peak gets near the
 * ceiling or the limiter starts working, and rises at 2 dB/s. The limiter is only a safety net
 * for sudden jumps (e.g. a quiet song followed by a loud one): its threshold is
 * ceiling − min(15, gain + 3) dB, so with no boost it's out of the way.
 *
 * ORDERING MATTERS: when two effects both claim "insert last" on the output mix, Android puts
 * the newer one BEFORE the older one (EffectChain::getInsertIndex_l). The Visualizer must
 * therefore be created before the global DynamicsProcessing so it measures the boosted output.
 *
 * Simulated against the AOSP limiter algorithm: never quieter than the original, +4…+12 dB
 * louder where there's headroom, zero clipped samples in every realistic case.
 */
class AudioEngine {

    private val sessions = LinkedHashMap<Int, DynamicsProcessing>()
    private var settings = BoostSettings()
    private var visualizer: Visualizer? = null

    // ---- Clip Guard state (read by the UI)
    @Volatile var gainDb = 0f; private set
    @Volatile var lastPeakDb: Float? = null; private set
    @Volatile var maxPeakDb = -96f; private set
    @Volatile var limiterActive = false; private set
    private val srcPeaks = ArrayDeque<Float>()
    private var pushedGainDb = Float.NaN
    private var retryTicks = 0

    companion object {
        private const val TAG = "CleanBoost"
        const val GLOBAL_SESSION = 0
        private const val CHANNELS = 2
        private const val PRIORITY = Int.MAX_VALUE

        private val MBC_EDGES = floatArrayOf(150f, 1200f, 6000f, 20000f)

        private const val LIMITER_ATTACK_MS = 0.01f
        private const val LIMITER_RELEASE_MS = 80f
        private const val LIMITER_RATIO = 1000f   // effectively ∞:1

        // Clip Guard tuning (validated in simulation, see clip_simulation.py / README)
        const val TICK_MS = 50L
        private const val HEADROOM_DB = 0.5f        // aim this far under the ceiling
        private const val GUARD_DB = 0.3f           // peak this close to ceiling → cut now
        private const val STEP_DOWN_DB = 1.5f
        private const val RISE_DB_PER_S = 2f
        private const val BACKSTOP_DB = 15f
        private const val BACKSTOP_MIN_DB = 3f
        private const val LIMITER_DETECT_DB = 3f
        private const val HISTORY_TICKS = (30_000 / TICK_MS).toInt()
    }

    @Synchronized
    fun update(s: BoostSettings) {
        settings = s
        if (!s.enabled) { releaseAll(); return }
        setMeter(s.clipGuard)                        // meter FIRST (see ORDERING above)
        if (!meterAvailable) gainDb = s.boostDb      // no meter: plain boost, not guarded
        gainDb = min(gainDb, s.boostDb)
        if (s.globalMode) attach(GLOBAL_SESSION) else detach(GLOBAL_SESSION)
        sessions.values.forEach { apply(it, s) }
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
        if (peak < -70f) return                        // silence: hold everything
        maxPeakDb = max(maxPeakDb, peak)

        val ceiling = settings.ceilingDb
        var g = gainDb

        // Loudest source peak recently = measured output peak minus the gain we applied.
        pushPeak(peak - g)
        limiterActive = g > 0f && rms > limiterThreshold(g) - LIMITER_DETECT_DB
        if (limiterActive) pushPeak(ceiling - HEADROOM_DB - (g - 1f))   // pull gain down

        val target = (ceiling - HEADROOM_DB - (srcPeaks.maxOrNull() ?: 0f))
            .coerceIn(0f, settings.boostDb)

        if (peak > ceiling - GUARD_DB) g = max(0f, g - (peak - ceiling) - STEP_DOWN_DB)
        g = if (target < g) target else min(target, g + RISE_DB_PER_S * TICK_MS / 1000f)
        gainDb = g

        if (pushedGainDb.isNaN() || abs(g - pushedGainDb) >= 0.05f) pushGain()
    }

    private fun pushPeak(v: Float) {
        srcPeaks.addLast(v)
        while (srcPeaks.size > HISTORY_TICKS) srcPeaks.removeFirst()
    }

    private fun pushGain() {
        pushedGainDb = gainDb
        val lim = buildLimiter(settings)
        sessions.values.forEach {
            runCatching {
                it.setInputGainAllChannelsTo(gainDb)
                it.setLimiterAllChannelsTo(lim)
            }
        }
    }

    private fun limiterThreshold(g: Float): Float {
        val ceiling = settings.ceilingDb.coerceIn(-12f, -0.1f)
        // Without the meter there's no gain loop, so keep the limiter high (plain booster mode).
        return if (visualizer == null) ceiling - BACKSTOP_MIN_DB
               else ceiling - min(BACKSTOP_DB, g + BACKSTOP_MIN_DB)
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

        b.setInputGainAllChannelsTo(gainDb)
        b.setPreEqAllChannelsTo(buildEq(s))
        b.setMbcAllChannelsTo(buildMbc(s))
        b.setLimiterAllChannelsTo(buildLimiter(s))
        return b.build()
    }

    private fun apply(dp: DynamicsProcessing, s: BoostSettings) {
        try {
            dp.setInputGainAllChannelsTo(gainDb)
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

    private fun buildLimiter(s: BoostSettings) = Limiter(
        true, true,
        /* linkGroup */ 0,                 // channels linked → stereo image never shifts
        LIMITER_ATTACK_MS, LIMITER_RELEASE_MS, LIMITER_RATIO,
        /* threshold */ limiterThreshold(gainDb),
        /* postGain  */ 0f,                // nothing after the limiter may add gain
    )

    // ---------------------------------------------------------------- meter

    private fun setMeter(on: Boolean) {
        if (on && visualizer == null) {
            visualizer = runCatching {
                Visualizer(GLOBAL_SESSION).apply {
                    setMeasurementMode(Visualizer.MEASUREMENT_MODE_PEAK_RMS)
                    setEnabled(true)
                }
            }.onFailure { Log.w(TAG, "Meter unavailable (RECORD_AUDIO not granted?)", it) }.getOrNull()
            if (visualizer != null) {
                // Start guarded from 0 dB and let the loop raise it safely.
                gainDb = 0f; srcPeaks.clear()
                // Re-create the global effect so it sits BEFORE the meter in the chain.
                if (sessions.containsKey(GLOBAL_SESSION)) { detach(GLOBAL_SESSION); attach(GLOBAL_SESSION) }
                pushGain()
            }
        } else if (!on && visualizer != null) {
            visualizer?.let { runCatching { it.setEnabled(false); it.release() } }
            visualizer = null
            lastPeakDb = null
        }
    }
}
