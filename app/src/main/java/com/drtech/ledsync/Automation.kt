package com.drtech.ledsync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.drtech.tuya.TuyaLanClient
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One saved look: everything except the HDMI input, so recalling it can never switch sources. */
data class Preset(
    val brightness: Int,
    val diffusion: Int,
    val tvSync: Boolean,
    val hdrAuto: Boolean,
    val colorEnhance: Int,
    /** First byte of DP 25 (a scene or sound mode), or null when none was active. */
    val modeCode: Int?,
    /** Only meaningful when [modeCode] is the Color scene. */
    val color: CustomColor,
) {
    val modeLabel: String?
        get() = SceneMode.entries.firstOrNull { it.code == modeCode }?.label
            ?: SoundMode.entries.firstOrNull { it.code == modeCode }?.label

    fun encode(): String = listOf(
        brightness, diffusion, if (tvSync) 1 else 0, if (hdrAuto) 1 else 0, colorEnhance,
        modeCode ?: -1, color.hue, color.saturation, color.value,
    ).joinToString(",")

    companion object {
        fun capture(s: ControllerState) = Preset(
            brightness = s.brightnessPercent,
            diffusion = s.diffusionDegreePercent,
            tvSync = s.tvSyncEnabled,
            hdrAuto = s.hdrAutoEnabled,
            colorEnhance = s.colorEnhancePercent,
            modeCode = s.sceneMode?.code ?: s.soundMode?.code,
            color = s.customColor,
        )

        fun decode(raw: String?): Preset? {
            val fields = raw?.split(",") ?: return null
            if (fields.size != 9) return null
            val p = fields.map { it.toIntOrNull() ?: return null }
            if (p[2] !in 0..1 || p[3] !in 0..1) return null
            if (p[5] != -1 && SceneMode.entries.none { it.code == p[5] } && SoundMode.entries.none { it.code == p[5] }) return null
            return Preset(
                brightness = p[0].coerceIn(1, 100), diffusion = p[1].coerceIn(0, 100),
                tvSync = p[2] == 1, hdrAuto = p[3] == 1, colorEnhance = p[4].coerceIn(0, 100),
                modeCode = p[5].takeIf { it >= 0 }, color = CustomColor(p[6].coerceIn(0, 360), p[7].coerceIn(0, 100), p[8].coerceIn(0, 100)),
            )
        }
    }
}

/** Stop at the first failed command and never include the HDMI input. */
internal suspend fun applyLook(provider: SyncBoxProvider, p: Preset): Boolean {
    val commands: List<suspend () -> Unit> = listOf(
        { provider.setPower(true) }, { provider.setBrightness(p.brightness) },
        { provider.setDiffusionDegree(p.diffusion) }, { provider.setTvSync(p.tvSync) },
        { provider.setHdrAuto(p.hdrAuto) }, { provider.setColorEnhance(p.colorEnhance) },
        {
            val scene = SceneMode.entries.firstOrNull { it.code == p.modeCode }
            val sound = SoundMode.entries.firstOrNull { it.code == p.modeCode }
            when {
                scene == SceneMode.COLOR -> provider.setCustomColor(p.color)
                scene != null -> provider.selectScene(scene)
                sound != null -> provider.selectSoundMode(sound)
            }
        },
    )
    for (command in commands) {
        command()
        if (provider.state.value.phase != ConnectionPhase.CONNECTED) return false
    }
    return true
}

/** Four named slots kept on this TV only. */
class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("ledsync_ui", Context.MODE_PRIVATE)

    fun load(): List<Preset?> = List(SLOTS) { Preset.decode(prefs.getString("preset_$it", null)) }

    fun save(slot: Int, preset: Preset) {
        require(slot in 0 until SLOTS)
        prefs.edit().putString("preset_$slot", preset.encode()).apply()
    }

    fun clear(slot: Int) {
        require(slot in 0 until SLOTS)
        prefs.edit().remove("preset_$slot").apply()
    }

    companion object {
        const val SLOTS = 4
        val TITLES = listOf("Movie night", "Gaming", "Late night", "Daytime")
    }
}

/** A daily on/off time. [minutes] is minutes after midnight, in 15-minute steps. */
data class DailySchedule(val enabled: Boolean = false, val minutes: Int = 0) {
    fun label(use24Hour: Boolean = false): String = clockTimeLabel(minutes, use24Hour)
}

internal fun clockTimeLabel(minutes: Int, use24Hour: Boolean): String {
    val value = minutes.coerceIn(0, 1439)
    val hour = value / 60
    val minute = value % 60
    return if (use24Hour) String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
    else String.format(java.util.Locale.US, "%d:%02d %s", if (hour % 12 == 0) 12 else hour % 12, minute, if (hour < 12) "AM" else "PM")
}

data class Schedules(
    val on: DailySchedule = DailySchedule(false, 19 * 60),
    val off: DailySchedule = DailySchedule(false, 23 * 60),
)

/** Persisted deadlines; exact alarms where permitted, approximate alarms otherwise. */
// Synchronous persistence is intentional: an alarm/receiver must never observe
// an older deadline after scheduling or cancellation returns.
@android.annotation.SuppressLint("ApplySharedPref")
object ScheduleManager {
    private const val PREFS = "ledsync_schedule"
    const val ACTION_ON = "com.drtech.ledsync.SCHEDULE_ON"
    const val ACTION_OFF = "com.drtech.ledsync.SCHEDULE_OFF"
    const val ACTION_SLEEP = "com.drtech.ledsync.SLEEP_OFF"
    private const val SLEEP_DEADLINE = "sleep_deadline"

    fun canScheduleExactly(context: Context): Boolean = Build.VERSION.SDK_INT < 31 ||
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    fun requestExactTiming(context: Context) {
        if (Build.VERSION.SDK_INT >= 31) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                android.net.Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }

    fun sleepDeadline(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(SLEEP_DEADLINE, 0)

    fun startSleep(context: Context, minutes: Int) {
        require(minutes in 1..24 * 60)
        setSleepDeadline(context, System.currentTimeMillis() + minutes * 60_000L)
    }

    internal fun setSleepDeadline(context: Context, at: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(SLEEP_DEADLINE, at).commit()
        armSleep(context)
    }

    fun cancelSleep(context: Context) {
        completeSleep(context)
        WorkManager.getInstance(context).cancelUniqueWork(ACTION_SLEEP)
    }

    @Synchronized
    internal fun completeSleep(context: Context, expectedDeadline: Long? = null) {
        if (expectedDeadline != null && sleepDeadline(context) != expectedDeadline) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SLEEP_DEADLINE).commit()
        alarmManager(context).cancel(pending(context, ACTION_SLEEP))
    }

    fun load(context: Context): Schedules {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Schedules(
            on = DailySchedule(p.getBoolean("on_enabled", false), p.getInt("on_minutes", 19 * 60).coerceIn(0, 1439)),
            off = DailySchedule(p.getBoolean("off_enabled", false), p.getInt("off_minutes", 23 * 60).coerceIn(0, 1439)),
        )
    }

    fun save(context: Context, schedules: Schedules) {
        require(schedules.on.minutes in 0..1439 && schedules.off.minutes in 0..1439)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("on_enabled", schedules.on.enabled).putInt("on_minutes", schedules.on.minutes)
            .putBoolean("off_enabled", schedules.off.enabled).putInt("off_minutes", schedules.off.minutes)
            .commit()
        rearm(context)
    }

    /** Cancels and re-creates both alarms from the stored schedule. */
    fun rearm(context: Context) {
        val s = load(context)
        arm(context, ACTION_ON, s.on)
        arm(context, ACTION_OFF, s.off)
        armSleep(context)
    }

    internal fun nextOccurrence(minutes: Int, now: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): Long {
        require(minutes in 0..1439)
        return Calendar.getInstance(zone).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }

    private fun alarmManager(context: Context) = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(context: Context, action: String, at: Long = 0): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode(), Intent(context, ScheduleReceiver::class.java).setAction(action).putExtra("at", at),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun arm(context: Context, action: String, schedule: DailySchedule) {
        alarmManager(context).cancel(pending(context, action))
        if (!schedule.enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(action)
            return
        }
        scheduleAt(context, action, nextOccurrence(schedule.minutes, System.currentTimeMillis()))
    }

    private fun armSleep(context: Context) {
        val at = sleepDeadline(context)
        if (at > 0) scheduleAt(context, ACTION_SLEEP, at.coerceAtLeast(System.currentTimeMillis() + 100))
    }

    private fun scheduleAt(context: Context, action: String, at: Long) {
        val alarms = alarmManager(context)
        val pending = pending(context, action, if (action == ACTION_SLEEP) sleepDeadline(context) else at)
        val show = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        // Permission can be revoked between the capability check and scheduling.
        if (canScheduleExactly(context)) {
            try {
                alarms.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending)
                return
            } catch (_: SecurityException) { /* Keep a working approximate alarm. */ }
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    internal fun enqueue(context: Context, action: String, at: Long) {
        WorkManager.getInstance(context).enqueueUniqueWork(action, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ScheduledPowerWorker>()
                .setInputData(workDataOf("action" to action, "at" to at)).build())
    }
}

/** Fires the daily power schedule (and re-arms alarms after a reboot). */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED)) {
            ScheduleManager.rearm(context)
            return
        }
        if (action in setOf(ScheduleManager.ACTION_ON, ScheduleManager.ACTION_OFF, ScheduleManager.ACTION_SLEEP)) {
            ScheduleManager.enqueue(context, requireNotNull(action), intent.getLongExtra("at", 0))
        }
    }
}

/** WorkManager owns the process/wake lock while a local scheduled command runs. */
class ScheduledPowerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val action = inputData.getString("action") ?: return@withContext Result.failure()
        val at = inputData.getLong("at", 0)
        val schedules = ScheduleManager.load(applicationContext)
        when (action) {
            ScheduleManager.ACTION_ON -> if (!schedules.on.enabled) return@withContext Result.success()
            ScheduleManager.ACTION_OFF -> if (!schedules.off.enabled) return@withContext Result.success()
            ScheduleManager.ACTION_SLEEP -> if (at == 0L || at != ScheduleManager.sleepDeadline(applicationContext)) return@withContext Result.success()
            else -> return@withContext Result.failure()
        }
        val config = LocalDeviceConfigStore(applicationContext).load()
        if (config == null) {
            if (action == ScheduleManager.ACTION_SLEEP) ScheduleManager.completeSleep(applicationContext, at)
            ScheduleManager.rearm(applicationContext)
            return@withContext Result.failure()
        }
        try {
            TuyaLanClient(config.host, config.deviceId, config.localKey).use { client ->
                client.connect()
                check(client.setDps(mapOf("20" to (action == ScheduleManager.ACTION_ON))) == 0)
                check(client.queryDps()["20"] == (action == ScheduleManager.ACTION_ON))
            }
            currentCoroutineContext().ensureActive()
            if (action == ScheduleManager.ACTION_SLEEP) ScheduleManager.completeSleep(applicationContext, at)
            ScheduleManager.rearm(applicationContext)
            Result.success()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (runAttemptCount < 2) Result.retry() else {
                if (action == ScheduleManager.ACTION_SLEEP) ScheduleManager.completeSleep(applicationContext, at)
                ScheduleManager.rearm(applicationContext)
                Result.failure()
            }
        }
    }
}
