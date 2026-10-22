package com.peyton.tvlauncher

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * Recently used apps from the system, available only after usage access is granted once over adb:
 *   adb shell appops set com.peyton.tvlauncher GET_USAGE_STATS allow
 * Returns null when access isn't granted (the card then uses apps opened from the launcher).
 */
object Recents {
  fun granted(ctx: Context): Boolean = try {
    val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = if (Build.VERSION.SDK_INT >= 29) ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
      else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
    mode == AppOpsManager.MODE_ALLOWED
  } catch (_: Throwable) { false }

  fun query(ctx: Context): List<String>? {
    if (!granted(ctx)) return null
    return try {
      val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
      val end = System.currentTimeMillis(); val ev = um.queryEvents(end - 3L * 86_400_000L, end)
      val e = UsageEvents.Event(); val last = LinkedHashMap<String, Long>()
      val skip = setOf(ctx.packageName, "com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher", "com.android.systemui")
      while (ev.hasNextEvent()) { ev.getNextEvent(e)
        @Suppress("DEPRECATION") if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && e.packageName !in skip) last[e.packageName] = e.timeStamp }
      last.entries.sortedByDescending { it.value }.map { it.key }.take(8)
    } catch (_: Throwable) { emptyList() }
  }
}
