package com.wann1.netpulse;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.Process;

/**
 * 判断当前是否在云游戏串流画面。
 *
 * 策略：
 *   1. 息屏 → 直接判否，零查询
 *   2. 查询之间强制限流（5 秒），避免无意义重复
 *   3. 优先 UsageStatsManager（binder 调用，不 fork 进程）
 *   4. root 只做兜底，间隔 ≥20 秒
 */
public final class Detector {

    public static final String STREAM_PKG = "com.gold.streaming";

    private Detector() {}

    /** 当前检测通道：0 未知 1 usage 2 root 3 都不可用 */
    public static int channel = 0;

    /** 是否有「使用情况访问」权限（系统设置里可手动开）。 */
    public static boolean hasUsageAccess(Context ctx) {
        try {
            AppOpsManager aom = (AppOpsManager) ctx.getSystemService(Context.APP_OPS_SERVICE);
            if (aom == null) return false;
            int mode;
            if (Build.VERSION.SDK_INT >= 29) {
                mode = aom.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), ctx.getPackageName());
            } else {
                mode = aom.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(), ctx.getPackageName());
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 是否拿到 root。只探测一次永久缓存 —— 反复探测本身就是干扰源。 */
    private static Boolean rootOk = null;

    public static boolean hasRoot() {
        if (rootOk == null) rootOk = probeRoot();
        return rootOk;
    }

    private static boolean probeRoot() {
        java.lang.Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
            java.io.BufferedReader r =
                    new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            p.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Throwable t) {
            return false;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    // ---- 限流状态 ----
    private static long lastQueryAt = 0;
    private static long lastRootAt = 0;
    private static boolean lastResult = false;
    /** 串流包 uid，查一次就缓存（包名不会变）。 */
    private static int streamUid = -1;
    private static boolean hasQueriedUid = false;

    /** 两次查询最小间隔。5 秒足够跟随进出游戏。 */
    private static final long MIN_INTERVAL_MS = 5000L;

    /** root 兜底间隔，比 usage 保守得多。 */
    private static final long ROOT_INTERVAL_MS = 20000L;

    /**
     * 是否在串流画面。
     *
     * @param force true = 调用方认为状态很可能变了（屏幕刚亮、刚切前台），忽略限流立即查
     */
    public static boolean isStreaming(Context ctx, boolean force) {
        // 息屏一定不在串流，零查询直接返回
        if (!isScreenOn(ctx)) {
            lastResult = false;
            return false;
        }

        long now = System.currentTimeMillis();

        // 限流：非强制且未到间隔，沿用上次结论
        if (!force && now - lastQueryAt < MIN_INTERVAL_MS) {
            return lastResult;
        }
        lastQueryAt = now;

        // 1) UsageStats：binder 调用，不 fork
        if (hasUsageAccess(ctx)) {
            Boolean r = byUsage(ctx);
            if (r != null) {
                channel = 1;
                // 修正 UsageStats 的滞后：它说「在串流」时，用连接表实时复核。
                // 原来这里 fork su 跑 dumpsys —— 每 20 秒一次，Magisk 每次
                // 都弹「已获得超级用户权限」，非常扰人。
                // 现在改成读 /proc/net/* 看有没有串流端口(18000-18200)的活动连接：
                // 同样实时、同样准，而且完全不需要 root，零 fork。
                if (r) {
                    Boolean live = Net.hasStreamPortConnection();
                    if (live != null) {
                        lastResult = live;
                        return live;
                    }
                }
                lastResult = r;
                return r;
            }
        }

        // 2) 没有 UsageStats 权限时的免 root 兜底：看串流端口连接。
        //    这同样不需要 root —— 过去这里 fork su，导致 Magisk 反复弹窗。
        Boolean live = Net.hasStreamPortConnection();
        if (live != null) {
            channel = 4;
            lastResult = live;
            return live;
        }

        // 没有新数据就沿用上次结论，避免 HUD 闪烁
        channel = channel == 0 ? 3 : channel;
        return lastResult;
    }

    /** 按限流策略判断。 */
    public static boolean isStreaming(Context ctx) {
        return isStreaming(ctx, false);
    }

    /** 屏幕是否亮着（进程内 API，零开销）。 */
    private static boolean isScreenOn(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            if (pm == null) return true;
            if (Build.VERSION.SDK_INT >= 20) return pm.isInteractive();
            return pm.isScreenOn();
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 用 UsageStats 判断前台。
     *
     * 注意：UsageStats 数据库有 flush 延迟，游戏运行期间可能长时间不更新。
     * 所以这里统计的是窗口内每个包「最后一次 resume / pause 时间」再比先后，
     * 而不是只看最近几条事件。
     */
    private static Boolean byUsage(Context ctx) {
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) return null;

            long now = System.currentTimeMillis();
            // 内存优化：查询窗口从 6 小时收窄到 30 分钟。
            // 6 小时会产生几万条事件、塞满两个 HashMap；而判断"当前是否在串流"
            // 只需要最近的一次 resume/pause，30 分钟足够（游戏一局通常更短，
            // 超出窗口说明肯定早就不在玩了，返回 null 让上层沿用上次结论）。
            UsageEvents ev = usm.queryEvents(now - 30 * 60_000L, now + 60_000L);
            if (ev == null) return null;

            // 内存优化：不再建两个 HashMap 存所有包，只记目标包的两个时间戳。
            // 原来是 O(所有包) 的内存，现在是 O(1)，事件再多也不涨。
            long myResume = -1;
            long myPause = -1;

            UsageEvents.Event e = new UsageEvents.Event();
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                // 先比包名，不匹配直接跳过 —— 省掉大量 getEventType/getTimeStamp 调用
                if (!STREAM_PKG.equals(e.getPackageName())) continue;

                int t = e.getEventType();
                long ts = e.getTimeStamp();

                if (t == UsageEvents.Event.ACTIVITY_RESUMED
                        || t == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    if (ts > myResume) myResume = ts;
                } else if (t == UsageEvents.Event.ACTIVITY_PAUSED
                        || t == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    if (ts > myPause) myPause = ts;
                }
            }

            if (myResume < 0) return null;

            // 最后一次进入前台之后没有再被切出 → 在串流
            return (myPause < 0 || myResume > myPause);
        } catch (Throwable t) {
            return null;
        }
    }

    /** root 兜底：读当前焦点窗口。grep -m1 匹配到就退出，开销很小。 */
    private static Boolean byRootExact() {
        java.lang.Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{
                    "su", "-c",
                    "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus"});
            java.io.BufferedReader r =
                    new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            r.close();
            p.waitFor();
            if (line == null) return null;
            return line.contains(STREAM_PKG);
        } catch (Throwable t) {
            return null;
        } finally {
            if (p != null) try { p.destroy(); } catch (Throwable ignored) {}
        }
    }

    /** 当前可用通道的描述，给界面显示用。 */
    public static String channelName(Context ctx) {
        if (hasUsageAccess(ctx)) return "使用情况访问";
        if (hasRoot()) return "root";
        return "无（需授权）";
    }
}
