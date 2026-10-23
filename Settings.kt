package com.cleanboost

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class FilterType(val label: String) {
    PEAK("Bell"), LOW_SHELF("Low shelf"), HIGH_SHELF("High shelf"),
    HIGH_PASS("High-pass"), LOW_PASS("Low-pass")
}

data class PeqBand(
    val type: FilterType = FilterType.PEAK,
    val freq: Float = 1000f,   // Hz
    val gain: Float = 0f,      // dB (ignored for HP/LP)
    val q: Float = 1.0f,
    val enabled: Boolean = true,
)

/**
 * Everything the user controls. All loudness work happens BEFORE the final limiter,
 * whose ceiling is the one number the output can never exceed.
 */
data class BoostSettings(
    val enabled: Boolean = true,
    val boostDb: Float = 6f,          // main volume-boost slider
    val maxBoostDb: Float = 30f,      // how far the slider is allowed to go
    val ceilingDb: Float = -1.0f,     // limiter ceiling (dBFS). Output peak never exceeds this.
    val density: Float = 0.5f,        // 0..1 multiband compression amount (raises average loudness)
    val attackMs: Float = 30f,        // compressor attack; longer = transients pass untouched
    val exciterDb: Float = 3f,        // "air"/presence: upward lift of quiet high-frequency detail
    val warmthDb: Float = 0f,         // same idea for the low end
    val globalMode: Boolean = true,   // attach to the global output mix (session 0)
    val clipGuard: Boolean = true,     // closed-loop crest-factor tracking (see AudioEngine)
    val bands: List<PeqBand> = DEFAULT_BANDS,
) {
    companion object {
        val DEFAULT_BANDS = listOf(
            PeqBand(FilterType.HIGH_PASS, 25f, 0f, 0.707f),
            PeqBand(FilterType.LOW_SHELF, 105f, 0f, 0.7f),
            PeqBand(FilterType.PEAK, 400f, 0f, 1.0f),
            PeqBand(FilterType.PEAK, 2500f, 0f, 1.0f),
            PeqBand(FilterType.HIGH_SHELF, 9000f, 0f, 0.7f),
        )
    }
}

object SettingsStore {
    private const val PREFS = "cleanboost"
    private const val KEY = "settings_json"

    fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): BoostSettings {
        val raw = prefs(ctx).getString(KEY, null) ?: return BoostSettings()
        return try { fromJson(JSONObject(raw)) } catch (e: Exception) { BoostSettings() }
    }

    fun save(ctx: Context, s: BoostSettings) {
        prefs(ctx).edit().putString(KEY, toJson(s).toString()).apply()
    }

    private fun toJson(s: BoostSettings) = JSONObject().apply {
        put("enabled", s.enabled); put("boostDb", s.boostDb.toDouble())
        put("maxBoostDb", s.maxBoostDb.toDouble()); put("ceilingDb", s.ceilingDb.toDouble())
        put("density", s.density.toDouble()); put("attackMs", s.attackMs.toDouble())
        put("exciterDb", s.exciterDb.toDouble()); put("warmthDb", s.warmthDb.toDouble())
        put("globalMode", s.globalMode); put("clipGuard", s.clipGuard)
        put("bands", JSONArray().apply {
            s.bands.forEach { b ->
                put(JSONObject().apply {
                    put("type", b.type.name); put("freq", b.freq.toDouble())
                    put("gain", b.gain.toDouble()); put("q", b.q.toDouble())
                    put("enabled", b.enabled)
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): BoostSettings {
        val d = BoostSettings()
        val arr = o.optJSONArray("bands")
        val bands = if (arr == null) d.bands else (0 until arr.length()).map { i ->
            val b = arr.getJSONObject(i)
            PeqBand(
                FilterType.valueOf(b.optString("type", "PEAK")),
                b.optDouble("freq", 1000.0).toFloat(),
                b.optDouble("gain", 0.0).toFloat(),
                b.optDouble("q", 1.0).toFloat(),
                b.optBoolean("enabled", true),
            )
        }
        return BoostSettings(
            enabled = o.optBoolean("enabled", d.enabled),
            boostDb = o.optDouble("boostDb", d.boostDb.toDouble()).toFloat(),
            maxBoostDb = o.optDouble("maxBoostDb", d.maxBoostDb.toDouble()).toFloat(),
            ceilingDb = o.optDouble("ceilingDb", d.ceilingDb.toDouble()).toFloat(),
            density = o.optDouble("density", d.density.toDouble()).toFloat(),
            attackMs = o.optDouble("attackMs", d.attackMs.toDouble()).toFloat(),
            exciterDb = o.optDouble("exciterDb", d.exciterDb.toDouble()).toFloat(),
            warmthDb = o.optDouble("warmthDb", d.warmthDb.toDouble()).toFloat(),
            globalMode = o.optBoolean("globalMode", d.globalMode),
            clipGuard = o.optBoolean("clipGuard", d.clipGuard),
            bands = bands,
        )
    }
}
