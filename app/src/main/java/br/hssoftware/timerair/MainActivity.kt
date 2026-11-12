package br.hssoftware.timerair

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import br.hssoftware.timerair.databinding.ActivityMainBinding
import java.util.Calendar
import kotlin.math.ceil

/**
 * TimerAir – calculates how many hours to set on the A/C sleep timer
 * so the unit shuts off exactly 30 minutes before the user wakes up.
 *
 * Logic summary:
 *   shutdownTime  = wakeTime − 30 min
 *   diffMinutes   = shutdownTime − now            (adjusted to next occurrence if past)
 *   rawHours      = diffMinutes / 60.0
 *   roundedHours  = ceil(rawHours / 0.5) * 0.5   (nearest 0.5 upward, min 0.5)
 */
class MainActivity : AppCompatActivity() {

    // ─── View binding ───────────────────────────────────────────────────────
    private lateinit var binding: ActivityMainBinding

    // ─── SharedPreferences ──────────────────────────────────────────────────
    private lateinit var prefs: SharedPreferences

    // ─── State ──────────────────────────────────────────────────────────────
    private var wakeHour: Int = DEFAULT_WAKE_HOUR
    private var wakeMinute: Int = DEFAULT_WAKE_MINUTE

    // ─── Ticker (1-second clock) ─────────────────────────────────────────────
    private val handler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateClock()
            updateResult()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge + keep screen on while app is open
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Apply window insets to the ScrollView (root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.main) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, systemBars.top, 0, systemBars.bottom)
            insets
        }

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadPrefs()

        binding.btnPickTime.setOnClickListener { showTimePicker() }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tickRunnable)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Clock display
    // ────────────────────────────────────────────────────────────────────────
    private fun updateClock() {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)

        binding.tvCurrentTime.text = "%02d:%02d".format(h, m)
    }

    // ────────────────────────────────────────────────────────────────────────
    // Result calculation & display
    // ────────────────────────────────────────────────────────────────────────
    private fun updateResult() {
        val now = Calendar.getInstance()

        // Desired shutdown time = wake time − 30 minutes
        val shutdown = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, wakeHour)
            set(Calendar.MINUTE, wakeMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.MINUTE, -SHUTDOWN_OFFSET_MINUTES)
        }

        // If the shutdown time is in the past (or less than 1 minute away),
        // push it to the same time tomorrow.
        if (shutdown.timeInMillis <= now.timeInMillis) {
            shutdown.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Difference in minutes (always positive now)
        val diffMinutes = ((shutdown.timeInMillis - now.timeInMillis) / 60_000.0)

        // Raw hours
        val rawHours = diffMinutes / 60.0

        // Round UP to nearest 0.5; ensure minimum is 0.5
        val rounded = roundToHalfHour(rawHours)

        // Format result: show integer when .0, otherwise one decimal
        val formatted = if (rounded % 1.0 == 0.0) {
            rounded.toInt().toString()
        } else {
            "%.1f".format(rounded).replace('.', ',')
        }
        binding.tvResultHours.text = formatted

        // Update wake info row
        binding.tvWakeTime.text = "%02d:%02d".format(wakeHour, wakeMinute)
        binding.tvWakeInfo.text = "30 min antes → desliga às %02d:%02d".format(
            shutdown.get(Calendar.HOUR_OF_DAY),
            shutdown.get(Calendar.MINUTE)
        )

        // Secondary "AC off at" label
        binding.tvAcOffTime.text = "O ar vai desligar por volta das %02d:%02d".format(
            shutdown.get(Calendar.HOUR_OF_DAY),
            shutdown.get(Calendar.MINUTE)
        )
    }

    /**
     * Rounds [hours] up to the nearest 0.5, with a minimum of 0.5.
     * Examples: 0.1 → 0.5 | 0.6 → 1.0 | 7.3 → 7.5 | 8.0 → 8.0
     */
    private fun roundToHalfHour(hours: Double): Double {
        val raw = ceil(hours / 0.5) * 0.5
        return if (raw < 0.5) 0.5 else raw
    }

    // ────────────────────────────────────────────────────────────────────────
    // Time picker
    // ────────────────────────────────────────────────────────────────────────
    private fun showTimePicker() {
        MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_24H)
            .setHour(wakeHour)
            .setMinute(wakeMinute)
            .setTitleText("Horário de acordar")
            .setInputMode(MaterialTimePicker.INPUT_MODE_CLOCK)
            .build()
            .also { picker ->
                picker.addOnPositiveButtonClickListener {
                    wakeHour = picker.hour
                    wakeMinute = picker.minute
                    savePrefs()
                    updateResult()
                }
                picker.show(supportFragmentManager, "wake_time_picker")
            }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Persistence (SharedPreferences)
    // ────────────────────────────────────────────────────────────────────────
    private fun loadPrefs() {
        wakeHour = prefs.getInt(PREF_WAKE_HOUR, DEFAULT_WAKE_HOUR)
        wakeMinute = prefs.getInt(PREF_WAKE_MINUTE, DEFAULT_WAKE_MINUTE)
    }

    private fun savePrefs() {
        prefs.edit()
            .putInt(PREF_WAKE_HOUR, wakeHour)
            .putInt(PREF_WAKE_MINUTE, wakeMinute)
            .apply()
    }

    // ────────────────────────────────────────────────────────────────────────
    companion object {
        private const val PREFS_NAME = "timer_air_prefs"
        private const val PREF_WAKE_HOUR = "wake_hour"
        private const val PREF_WAKE_MINUTE = "wake_minute"
        private const val DEFAULT_WAKE_HOUR = 6
        private const val DEFAULT_WAKE_MINUTE = 0
        private const val SHUTDOWN_OFFSET_MINUTES = 30
        private const val TICK_INTERVAL_MS = 1_000L
    }
}
