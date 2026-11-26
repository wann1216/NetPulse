package com.wann1.netpulse;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * 云游戏网络 HUD：一个细长的半透明悬浮条，只显示「RTT ms ±抖动」。
 * 固定在左上角，由外部广播启动/停止，天然随游戏进出。
 */
public class HudService extends Service {

    public static final String ACTION_START = "com.wann1.netpulse.START";
    public static final String ACTION_STOP = "com.wann1.netpulse.STOP";

    private static final int TICK_MS = 1000;      // 每秒采一次
    private static final int WINDOW = 15;         // 抖动按最近 15 个样本算
    private static final int WATCH_MS = 5000;     // 每 5 秒判一次是否在串流（放宽以减轻系统负载）
    private static final int PORT_GW = 53;
    private static final String FALLBACK_HOST = "223.5.5.5";
    private static final int PORT_DNS = 53;

    // 贴死左上角。HUD 只在串流画面出现，而串流是全屏（状态栏隐藏），
    // 所以这里就是画面真正的左上角，不会压到任何东西。
    private static final float DEF_X_DP = 6f;
    private static final float DEF_Y_DP = 6f;

    // 悬浮条尺寸（dp）：细 + 长
    private static final float W_DP = 176f;
    private static final float H_DP = 38f;

    // ---- 包豪斯配色（白底）----
    // 包豪斯三原色：红 #E03C31 / 黄 #F2C230 / 蓝 #1B4B8F，配纯白底与近黑字
    private static final int BAUHAUS_RED = 0xE03C31;
    private static final int BAUHAUS_YELLOW = 0xF2C230;
    private static final int BAUHAUS_BLUE = 0x1B4B8F;
    private static final int BAUHAUS_INK = 0x141414;
    private static final int BAUHAUS_GRAY = 0x8A8A8A;
    private static final int BAUHAUS_WHITE = 0xFFFFFF;

    // 深色半透明底：0x9E ≈ 62% 不透明，既有通透感又保证文字清晰
    private static final int BG_ALPHA = 0x9E;
    private static final int BORDER_ALPHA = 0x66;
    private static final int BAR_ALPHA = 0xCC;

    // 三档状态色（优=绿 / 一般=黄 / 差=红）—— 必须带 alpha=FF，否则绘制时完全透明
    private static final int Q_GOOD = 0xFF4ADE80;
    private static final int Q_WARN = 0xFFFFC24B;
    private static final int Q_BAD = 0xFFFF5C5C;

    private WindowManager wm;
    private HudView view;
    private WindowManager.LayoutParams lp;
    /** 悬浮窗当前是否真的挂在 WindowManager 上（view 非空不代表在显示）。 */
    private boolean attached = false;
    private Handler handler;
    private volatile boolean running;

    private final ArrayDeque<Integer> samples = new ArrayDeque<>();
    private String gateway;
    /** 云游戏进程正在连的所有公网服务器（查不到为 null，退回测网关）。 */
    private java.util.List<String> streamServers;
    private String cachedUid = null;
    private long lastServerLookup = 0;
    private int lastRtt = -1;
    private long lastGoodAt = 0;
    private int stateKind = 0;   // 0 正常 1 警告 2 断流

    private Runnable ticker;
    private Runnable watcher;

    /** 日志开关偏好。默认开；关掉后 NetLog 完全不启动，零开销。 */
    public static final String PREF = "netpulse";
    public static final String KEY_LOG = "wifi_log";

    /** WiFi 链路日志。随服务一起启停，独立线程，独立开关。 */
    private NetLog netLog;

    private boolean logEnabled() {
        try {
            return getSharedPreferences(PREF, MODE_PRIVATE).getBoolean(KEY_LOG, true);
        } catch (Throwable t) {
            return true;
        }
    }

    private void startLogIfNeeded() {
        if (!logEnabled()) {
            stopLog();
            return;
        }
        try {
            if (netLog == null) netLog = NetLog.get(this);
            netLog.start();
        } catch (Throwable t) {
            android.util.Log.d("NetPulse", "启动日志失败: " + t);
        }
    }

    private void stopLog() {
        try {
            if (netLog != null) netLog.stop();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String act = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(act)) {
            running = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!running) start();
        // 被系统杀掉后自动重建，保证"授权一次终身有效"
        return START_STICKY;
    }

    private void start() {
        running = true;
        startForegroundSafe();
        gateway = Net.gateway(this);
        startLogIfNeeded();
        scheduleTick();
        scheduleWatch();
    }

    /**
     * 判断是否在串流，据此显隐悬浮窗 —— 不需要任何外部脚本。
     *
     * Detector 内部有 5 秒限流 + 息屏短路 + UsageStats 优先，
     * 轮询本身只做几十微秒的进程内判断，几乎零开销。
     */
    private void scheduleWatch() {
        watcher = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                boolean stream = Detector.isStreaming(HudService.this);
                if (stream && !attached) {
                    attachOverlay();
                    // 刚进云游戏：立刻记一条，不用等下个 30 秒周期
                    if (netLog != null) netLog.poke();
                } else if (!stream && attached) {
                    detachOverlay();
                }
                handler.postDelayed(this, WATCH_MS);
            }
        };
        handler.postDelayed(watcher, 300);
    }

    /** 内存优化：通知渠道只需创建一次，重复 create 会走系统调用并分配对象。 */
    private static boolean channelReady = false;

    private void startForegroundSafe() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            String ch = "netpulse";
            if (!channelReady && android.os.Build.VERSION.SDK_INT >= 26) {
                NotificationChannel c = new NotificationChannel(
                        ch, "NetPulse", NotificationManager.IMPORTANCE_MIN);
                c.setShowBadge(false);
                nm.createNotificationChannel(c);
                channelReady = true;
            }
            Notification.Builder b = android.os.Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, ch)
                    : new Notification.Builder(this);
            b.setContentTitle("NetPulse")
                    .setContentText("网络延迟 HUD 运行中")
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setOngoing(true);
            startForeground(0x4e50, b.build());
        } catch (Throwable ignored) {}
    }

    /** 挂上悬浮窗。已在显示则跳过。 */
    private void attachOverlay() {
        if (attached) return;
        float dens = getResources().getDisplayMetrics().density;

        // 内存优化（关键）：HudView 与 LayoutParams 跨进出串流复用。
        // 原来每次 attachOverlay 都 new 一套，玩家反复进出游戏时对象不断堆积，
        // 每次还要重建 6 个 Paint 和字体。现在只在第一次创建，之后一直复用。
        if (view == null) view = new HudView(this);

        if (lp == null) {
            int type = android.os.Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    // NOT_TOUCHABLE：完全穿透，不抢点击也不挡视线
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.x = (int) (DEF_X_DP * dens);
            lp.y = (int) (DEF_Y_DP * dens);
        }

        try {
            wm.addView(view, lp);
            attached = true;
        } catch (Throwable t) {
            view = null;
            attached = false;
        }
    }

    /**
     * 摘掉悬浮窗。没有则跳过。
     *
     * 内存优化：只从 WindowManager 移除，不置空 view/lp —— 保留实例供下次复用。
     * 是否显示用 attached 标记判断，避免"已移除但变量还在"被误当成在显示。
     */
    private void detachOverlay() {
        if (view == null || !attached) return;
        try { wm.removeView(view); } catch (Throwable ignored) {}
        attached = false;
    }

    private void scheduleTick() {
        ticker = new Runnable() {
            @Override
            public void run() {
                if (!running) return;
                // 只在悬浮窗显示（即真在串流）时才测网络。
                // 不在游戏里就完全停掉测量，平时零 socket 零负载。
                if (attached) sampleOnce();
                handler.postDelayed(this, TICK_MS);
            }
        };
        handler.post(ticker);
    }

    private void sampleOnce() {
        // 目标优先级（关键：决定这个数字对玩家有没有意义）
        //   1) 云游戏进程正在连的真实公网服务器 —— 这才是玩家感受到的延迟
        //      串流通常同时连 2~3 台，延迟能差一倍（实测 27/31/58ms）。
        //      必须**全部测一遍取最低**，否则可能正好取到最慢那台，数字虚高。
        //   2) 网关 —— 只反映家门口那一小段，作为兜底
        //   3) 公共 DNS —— 最后兜底
        final java.util.List<String> servers = findStreamServers();

        final String host;
        final int[] ports;
        if (servers != null && !servers.isEmpty()) {
            // 多服务器择优：传空 host 表示"自己遍历"，在后台线程里测
            host = null;
            ports = new int[]{443, PORT_GW};
        } else if (gateway != null) {
            host = gateway;
            ports = new int[]{PORT_GW};
        } else {
            host = FALLBACK_HOST;
            ports = new int[]{PORT_DNS};
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                int rtt;
                if (host == null) {
                    // 逐个测，取最低。串流要的是"最优可达链路"，不是被某台慢机拖累。
                    // earlyBreak=0：公网 RTT 本就 20~40ms，不按局域网阈值提前退出。
                    rtt = -1;
                    StringBuilder dbg = new StringBuilder();
                    for (String ip : servers) {
                        int r = Net.probe(ip, ports, 300, 0);
                        dbg.append(ip).append("=").append(r).append(" ");
                        if (r >= 0 && (rtt < 0 || r < rtt)) rtt = r;
                    }
                    android.util.Log.d("NetPulse", "逐台 " + dbg + "→ 取 " + rtt);
                } else {
                    rtt = Net.probe(host, ports, 400);
                }
                final int result = rtt;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        applySample(result);
                    }
                });
            }
        }, "netpulse-probe").start();
    }

    /**
     * 找出串流进程正在连的真实公网服务器。
     *
     * 每 30 秒才重新查一次（连接建立后地址基本不变），避免每秒读 /proc 造成负担。
     * 查不到就返回 null，调用方自动退回测网关。
     */
    private java.util.List<String> findStreamServers() {
        long now = System.currentTimeMillis();
        if (now - lastServerLookup < 30000L) return streamServers;
        lastServerLookup = now;
        try {
            // 不再依赖 uid 扫描：/proc/[pid]/ 在 app 视角被 hidepid 过滤，
            // 但 /proc/net/* 的 uid 列照常可读。直接从连接表按 uid 筛。
            String uid = knownStreamUid();
            java.util.List<String> list = Net.serversOfUid(uid);
            if (list == null || list.isEmpty()) {
                // uid 拿不到时按"串流端口/多路连接"特征兜底
                String guess = Net.guessStreamServer(null);
                if (guess != null) {
                    list = new java.util.ArrayList<>();
                    list.add(guess);
                }
            }
            streamServers = list;
            android.util.Log.d("NetPulse", "探测 uid=" + uid + " -> " + list);
        } catch (Throwable t) {
            android.util.Log.d("NetPulse", "探测异常: " + t);
            streamServers = null;
        }
        return streamServers;
    }

    /** 已知串流 uid 的字符串形式；拿不到返回 null。 */
    private String knownStreamUid() {
        try {
            if (cachedUid == null) {
                // PackageManager 需要 Manifest 的 <queries> 才不抛异常（已声明）
                cachedUid = String.valueOf(
                        getPackageManager().getPackageUid(Detector.STREAM_PKG, 0));
            }
            return cachedUid;
        } catch (Throwable t) {
            return null;
        }
    }

    private void applySample(int rtt) {
        if (!running) return;
        if (rtt >= 0) {
            lastRtt = rtt;
            lastGoodAt = System.currentTimeMillis();
            samples.addLast(rtt);
            while (samples.size() > WINDOW) samples.removeFirst();
            stateKind = 0;
        } else {
            stateKind = 2;
        }

        // 断流判定：超过 3 秒没有有效样本
        if (lastGoodAt > 0 && System.currentTimeMillis() - lastGoodAt > 3000) {
            stateKind = 2;
        }

        if (attached && view != null) view.invalidate();
    }

    /** 抖动 = 相邻样本差值的平均绝对值（RFC3550 口径）。 */
    private double jitter() {
        int n = samples.size();
        if (n < 2) return 0;
        int prev = Integer.MIN_VALUE;
        double acc = 0;
        int cnt = 0;
        for (int v : samples) {
            if (prev != Integer.MIN_VALUE) {
                acc += Math.abs(v - prev);
                cnt++;
            }
            prev = v;
        }
        return cnt == 0 ? 0 : acc / cnt;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (handler != null) {
            if (ticker != null) handler.removeCallbacks(ticker);
            if (watcher != null) handler.removeCallbacks(watcher);
        }
        stopLog();
        detachOverlay();
        // 服务真正销毁时才释放 View 与 LayoutParams，让 GC 能回收
        view = null;
        lp = null;
        attached = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** 悬浮条本体：深色半透明细长条，绿/黄/红三档状态色。 */
    private class HudView extends View {
        // 内存优化：去掉从未用于绘制的 accent（死代码），
        // 几何图形（底、边、柱条、圆点）不需要抗锯齿，只给文字留抗锯齿。
        private final Paint bg = new Paint();
        private final Paint border = new Paint();
        private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint unit = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint();
        private final Paint dot = new Paint();
        private final RectF rect = new RectF();
        private final float d;

        HudView(Context c) {
            super(c);
            d = getResources().getDisplayMetrics().density;

            // 内存优化（关键）：强制软件渲染。
            // 悬浮条只是一个矩形 + 数字 + 几根竖条，硬件加速在这里毫无收益，
            // 却要加载整套 OpenGL：实测 GL mtrack 2.1MB + EGL mtrack 0.9MB +
            // native heap 涨 4MB + 多个图形 .so 约 5MB，合计约 13MB。
            // 改用软件渲染后这部分几乎全部消失，肉眼观感完全一致。
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);

            // 深色半透明底
            bg.setColor(withAlpha(0x101418, BG_ALPHA));
            bg.setStyle(Paint.Style.FILL);

            // 极淡边框，压住边缘
            border.setColor(withAlpha(0xFFFFFF, BORDER_ALPHA));
            border.setStyle(Paint.Style.STROKE);
            border.setStrokeWidth(dp(1f));

            txt.setColor(Color.WHITE);
            txt.setTextSize(dp(17));
            txt.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

            unit.setColor(withAlpha(0xFFFFFF, 0xB0));
            unit.setTextSize(dp(10));
            unit.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));

            bar.setStyle(Paint.Style.FILL);

            dot.setStyle(Paint.Style.FILL);
        }

        private int withAlpha(int rgb, int a) {
            return (a << 24) | (rgb & 0x00FFFFFF);
        }

        private float dp(float v) { return v * d; }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            setMeasuredDimension((int) dp(W_DP), (int) dp(H_DP));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float r = dp(8);

            rect.set(0, 0, w, h);
            cv.drawRoundRect(rect, r, r, bg);
            rect.set(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f));
            cv.drawRoundRect(rect, r, r, border);

            // 三档配色：优=绿、一般=黄、差=红
            //   优   <= 30ms
            //   一般 31 ~ 50ms
            //   差   >= 51ms 或断流
            int quality;
            if (stateKind == 2 || lastRtt < 0) quality = 2;
            else if (lastRtt > 50) quality = 2;
            else if (lastRtt > 30) quality = 1;
            else quality = 0;

            int qColor;
            if (quality == 2) qColor = Q_BAD;        // 差 · 红
            else if (quality == 1) qColor = Q_WARN;  // 一般 · 黄
            else qColor = Q_GOOD;                    // 优 · 绿

            // 状态灯
            dot.setColor(qColor);
            cv.drawCircle(dp(11), h / 2f, dp(3f), dot);

            // 主数值（同一套三档色）
            txt.setColor(qColor);

            String main;
            if (stateKind == 2 && lastRtt < 0) main = "--";
            else main = String.valueOf(lastRtt < 0 ? 0 : lastRtt);

            float baseY = h / 2f + dp(6f);
            float x = dp(20);
            cv.drawText(main, x, baseY, txt);
            float mw = txt.measureText(main);

            cv.drawText("ms", x + mw + dp(2), baseY, unit);
            float uw = unit.measureText("ms");

            // 抖动
            String jt;
            if (samples.size() < 2) jt = "±--";
            else jt = String.format(Locale.US, "±%.1f", jitter());
            cv.drawText(jt, x + mw + dp(2) + uw + dp(6), baseY, unit);

            // 右侧迷你趋势条（竖向排列的细柱，越靠右越新）
            int n = samples.size();
            if (n >= 2) {
                float barRight = w - dp(9);
                float barTop = h / 2f - dp(9);
                float barH = dp(18);
                float cw = dp(2.4f);
                float gap = dp(1.2f);
                int cap = WINDOW;

                int max = 1;
                for (int v : samples) if (v > max) max = v;

                // 只画最近若干个，从右往左排
                int idx = 0;
                Integer[] arr = samples.toArray(new Integer[0]);
                for (int i = arr.length - 1; i >= 0 && idx < cap; i--, idx++) {
                    int v = arr[i];
                    float frac = Math.min(1f, (float) v / max);
                    float hh = Math.max(dp(1.5f), barH * frac);
                    float right = barRight - idx * (cw + gap);
                    float left = right - cw;
                    if (left < dp(96)) break;   // 不侵入数字区
                    // 柱条跟随三档色（深底上用同色系稍暗一点，不抢主数值）
                    int barRgb;
                    if (quality == 2) barRgb = Q_BAD;
                    else if (quality == 1) barRgb = Q_WARN;
                    else barRgb = Q_GOOD;
                    bar.setColor(withAlpha(barRgb, BAR_ALPHA));
                    cv.drawRect(left, barTop + (barH - hh), right, barTop + barH, bar);
                }
            }
        }
    }
}
