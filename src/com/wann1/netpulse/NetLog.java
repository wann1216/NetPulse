package com.wann1.netpulse;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Environment;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * WiFi 链路质量日志。
 *
 * 干什么：只在云游戏串流期间，把网络状况落盘成一行 TSV，便于事后回溯
 * 「那天打游戏卡，到底是家门口的网络差，还是运营商出口差，还是云端服务器远」。
 * 平时（不在串流）一行都不写、一个 socket 都不建。
 *
 * 记录三段延迟 + 三段抖动：
 *   1) gw  到路由器（默认网关，局域网那一段）
 *   2) isp 到运营商接口（本机 DHCP 下发的运营商 DNS；没有就退化为公共 DNS 参考点）
 *   3) srv 到串流服务器（按 /proc/net 里串流进程真实连接的公网 IP，多台取最低）
 *
 * 省资源的设计（这是重点，不能拖累游戏）：
 *   - 只在串流时采样；进入游戏立即记一条，之后 30 秒一次
 *   - 每行日志的代价是 3 次 TCP 握手，平时零开销
 *   - 单线程串行探测，不做并发，不占主线程
 *   - 探针用 TCP 握手（不需要 root / 不需要 ICMP 权限），超时即放弃
 *   - 串流服务器列表 30 秒才扫一次 /proc，其余时间读缓存
 *   - 日志追加写 + 每行 flush，不长期持有大缓冲；超过 2MB 自动轮转，磁盘不会无限涨
 *   - 不在 WiFi/以太网上也整轮跳过，一个 socket 都不建
 */
public final class NetLog {

    public static final String TAG = "NetPulseLog";

    /** 根目录日志文件名（需要「所有文件访问」权限才写得进去）。 */
    public static final String FILE_NAME = "NetPulse-wifi.log";

    /**
     * WiFi 名称的来源说明（重要，别再走弯路）。
     *
     * 这台掌机（Odin 2 / Android 13）即便定位权限全授，getSSID() 仍返回 "?"、
     * getBSSID() 返回空 —— 系统级硬脱敏，实测多次。唯一能拿到真名的是
     * `cmd wifi status`，但那需要 root，而 su 会弹 Magisk 授权框
     * （以前就是被这个坑劝退的，不能再用）。
     *
     * 因此改用「连接指纹」：网关网段 + 频段 + 速率 组成稳定标识，
     * 用户在 App 里给指纹起一次名，之后该 WiFi 每次连上都自动用这个名字 ——
     * 效果等同「按 WiFi 名称记录该网络状况」。
     */
    private static final String PREF_ALIAS = "wifi_alias";

    /** 稳态采样周期。串流本身会占 CPU，日志没必要更密。 */
    private static final long PERIOD_MS = 30000L;

    /** 抖动窗口：最近 N 个样本的平均绝对差（RFC3550 口径）。 */
    private static final int WIN = 8;

    /** 单文件上限，超过就轮转成 .1，最多占 4MB。 */
    private static final long MAX_BYTES = 2L * 1024L * 1024L;

    /** 串流服务器列表的重扫间隔。 */
    private static final long SRV_TTL_MS = 30000L;

    /** 公共 DNS，仅当运营商没下发公网 DNS 时用作「ISP 侧」参考点。 */
    private static final String FALLBACK_ISP = "223.5.5.5";

    private static NetLog instance;

    public static synchronized NetLog get(Context ctx) {
        if (instance == null) instance = new NetLog(ctx.getApplicationContext());
        return instance;
    }

    private final Context app;

    private volatile boolean running;
    private volatile boolean wake;
    private final Object lock = new Object();
    private Thread worker;

    // ---- 输出 ----
    private File file;
    private BufferedWriter w;
    private boolean headerDone;

    // ---- 抖动窗口 ----
    private final ArrayDeque<Integer> gwWin = new ArrayDeque<>();
    private final ArrayDeque<Integer> ispWin = new ArrayDeque<>();
    private final ArrayDeque<Integer> srvWin = new ArrayDeque<>();

    // ---- 连接变化检测 ----
    private String lastKey;
    private long connSince;

    /** 是否处于云游戏串流中。只有串流时才采样，平时一行都不写。 */
    private boolean wasStreaming;

    // ---- 串流服务器缓存 ----
    private List<String> srvList;
    private long srvAt;

    // ---- 给 UI 的最近一次结果 ----
    private volatile String lastLine = "";
    private volatile String lastPath = "";
    private volatile String lastFp = "";
    private volatile int lastFreq;
    private volatile long lastAt;

    private NetLog(Context c) {
        this.app = c;
    }

    // =====================================================================
    // 生命周期
    // =====================================================================

    public synchronized void start() {
        if (running) return;
        running = true;
        wake = false;
        registerNetWatch();
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "netpulse-log");
        worker.setPriority(Thread.MIN_PRIORITY);   // 绝不和游戏抢 CPU
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        unregisterNetWatch();
        synchronized (lock) {
            lock.notifyAll();
        }
        worker = null;
        closeWriter();
    }

    /**
     * 监听 WiFi 连接变化。
     *
     * 为什么必须有：靠 30 秒轮询会在「刚连上就断开」这种情况下整段漏掉。
     * 挂 NetworkCallback 后，连上/断开瞬间就触发一次采样，
     * 保证「每个连接」都真正留下一条记录 —— 这才是这个功能的意义。
     * 系统回调本身几乎零开销，只在事件发生时唤醒一次 worker。
     */
    private ConnectivityManager.NetworkCallback netWatch;

    private void registerNetWatch() {
        if (netWatch != null) return;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            netWatch = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    poke();
                }

                @Override
                public void onLost(Network n) {
                    poke();
                }

                @Override
                public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                    // 换 AP / 换频段 / 插拔网线时 capabilities 会变
                    if (nc != null && (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) {
                        poke();
                    }
                }
            };
            cm.registerDefaultNetworkCallback(netWatch);
        } catch (Throwable t) {
            netWatch = null;
        }
    }

    private void unregisterNetWatch() {
        if (netWatch == null) return;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) cm.unregisterNetworkCallback(netWatch);
        } catch (Throwable ignored) {}
        netWatch = null;
    }

    /** 请求插一次立即采样（WiFi 变化、用户手动触发时用）。 */
    public void poke() {
        // 防抖：NetworkCallback 在连接建立瞬间可能连发好几个回调
        // （onAvailable + onCapabilitiesChanged + onLinkPropertiesChanged），
        // 每个都探一轮就是白白多花好几次握手。2 秒内只认一次。
        long now = System.currentTimeMillis();
        if (now - pokeAt < 2000L) {
            return;
        }
        pokeAt = now;
        wake = true;
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private volatile long pokeAt;

    public boolean isRunning() {
        return running;
    }

    public String path() {
        return lastPath;
    }

    public String lastLine() {
        return lastLine;
    }

    public long lastAt() {
        return lastAt;
    }

    // =====================================================================
    // 主循环
    // =====================================================================

    private void loop() {
        while (running) {
            long t0 = System.currentTimeMillis();
            try {
                sampleOnce();
            } catch (Throwable t) {
                android.util.Log.d(TAG, "采样异常: " + t);
            }

            long spent = System.currentTimeMillis() - t0;
            long wait = PERIOD_MS - spent;
            if (wait < 2000L) wait = 2000L;

            synchronized (lock) {
                // 关键：清唤醒标记必须在**采样之后**、进入等待之前。
                // 若放在采样之前，采样期间来的 poke() 会把标记重新置上，
                // 于是等待被立刻跳过、紧接着又采一轮（实测出现两条同秒记录）。
                wake = false;
                long deadline = System.currentTimeMillis() + wait;
                while (running && !wake) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0L) break;
                    try {
                        lock.wait(Math.min(left, 1000L));
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }
    }

    // =====================================================================
    // 一次采样
    // =====================================================================

    private void sampleOnce() {
        // 只在云游戏串流期间记录：平时连一个 socket 都不建，也不碰日志文件。
        // Detector 内部有 5 秒限流 + 息屏短路，这里调用几乎零开销。
        boolean streaming;
        try {
            streaming = Detector.isStreaming(app);
        } catch (Throwable t) {
            streaming = false;
        }
        if (!streaming) {
            if (wasStreaming) {
                wasStreaming = false;
                lastKey = null;
                connSince = 0L;
                gwWin.clear();
                ispWin.clear();
                srvWin.clear();
                srvAt = 0L;   // 下一局重新扫串流服务器
            }
            return;
        }
        if (!wasStreaming) {
            wasStreaming = true;
            lastKey = null;   // 新一局第一条记 start，抖动窗口从头算
            gwWin.clear();
            ispWin.clear();
            srvWin.clear();
            srvAt = 0L;
        }

        WifiInfo wi = wifiInfo();   // 以太网时为 null，但网络本身是有效的

        // 没有 WiFi/以太网：只在「刚刚掉线」时记一条，之后整轮跳过，一个 socket 都不建。
        if (!hasActiveNet()) {
            if (lastKey != null) {
                write(System.currentTimeMillis(), "", "", "", 0, 0, 0,
                        -1, -1, -1, -1, -1, -1, "disconnect");
                lastKey = null;
                connSince = 0L;
            }
            return;
        }

        String realSsid = wi == null ? null : ssidOf(wi);
        int rssi = wi == null ? 0 : safeRssi(wi);
        int freq = wi == null ? 0 : safeFreq(wi);
        int link = wi == null ? 0 : safeLink(wi);
        String iface = ifaceOf();

        // 连接指纹（用于区分不同 WiFi）与最终写进日志的「名称」。
        // 有真名用真名；没有就用用户起的别名；再没有就写成 [指纹] 让人一眼能认。
        final String gwHost0 = Net.gateway(app);
        String fp = fingerprint(gwHost0, freq);
        String name;
        if (realSsid != null) {
            name = realSsid;
        } else {
            String alias = aliasOf(fp);
            name = alias != null ? alias : "[" + fp + "]";
        }

        // 连接变化 → 立即记录，否则按周期记 steady
        String key = fp + "|" + name;
        String note;
        long now = System.currentTimeMillis();
        if (lastKey == null) {
            note = "start";   // 本局串流第一条
            connSince = now;
        } else if (!key.equals(lastKey)) {
            note = "switch";
            connSince = now;
        } else {
            note = "steady";
        }
        lastKey = key;

        String gwHost = gwHost0;
        String ispHost = ispTarget();

        int gw = gwHost == null ? -1 : Net.probe(gwHost, new int[]{53, 80}, 300, 0);
        int isp = Net.probe(ispHost, new int[]{53}, 400, 0);
        // 运营商下发的公网 DNS 未必回 TCP SYN（实测电视下发的 180.76.76.76
        // 的 53/443 都不通）。锁死它只会让 isp 永远是 -1、抖动也跟着废掉。
        // 失败就退回一个国内稳定可达的公共 DNS 作参考点。
        if (isp < 0 && !FALLBACK_ISP.equals(ispHost)) {
            isp = Net.probe(FALLBACK_ISP, new int[]{53}, 500, 0);
        }
        int srv = probeStream();

        double gwJ = push(gwWin, gw);
        double ispJ = push(ispWin, isp);
        double srvJ = push(srvWin, srv);

        write(now, iface, name, fp, rssi, freq, link,
                gw, isp, srv, gwJ, ispJ, srvJ, note);
    }

    /** 抖动：把样本塞进窗口，返回窗口内相邻差值的平均绝对值。 */
    private static double push(ArrayDeque<Integer> win, int v) {
        if (v >= 0) {
            win.addLast(v);
            while (win.size() > WIN) win.removeFirst();
        }
        int n = win.size();
        if (n < 2) return -1;
        int prev = Integer.MIN_VALUE;
        double acc = 0;
        int cnt = 0;
        for (int x : win) {
            if (prev != Integer.MIN_VALUE) {
                acc += Math.abs(x - prev);
                cnt++;
            }
            prev = x;
        }
        return cnt == 0 ? -1 : acc / cnt;
    }

    /** 测串流服务器：多台取最低。不在串流返回 -1。 */
    private int probeStream() {
        long now = System.currentTimeMillis();
        if (now - srvAt > SRV_TTL_MS) {
            srvAt = now;
            srvList = lookupStreamServers();
        }
        List<String> list = srvList;
        if (list == null || list.isEmpty()) return -1;

        int best = -1;
        int tried = 0;
        for (String ip : list) {
            if (tried++ >= 3) break;        // 最多测 3 台，控制开销
            // 多服务器择优必须测完再比：实测三台差一倍（27/31/58ms），
            // 早期退出会正好撞上慢的那台，把延迟写高。
            int r = Net.probe(ip, new int[]{443, 53}, 300, 0);
            if (r >= 0 && (best < 0 || r < best)) best = r;
        }
        return best;
    }

    private List<String> lookupStreamServers() {
        try {
            String uid = null;
            try {
                uid = String.valueOf(app.getPackageManager()
                        .getPackageUid(Detector.STREAM_PKG, 0));
            } catch (Throwable ignored) {}
            List<String> list = Net.serversOfUid(uid);
            if (list == null || list.isEmpty()) {
                String g = Net.guessStreamServer(null);
                if (g != null) {
                    list = new ArrayList<>();
                    list.add(g);
                }
            }
            return list;
        } catch (Throwable t) {
            return null;
        }
    }

    // =====================================================================
    // WiFi 信息
    // =====================================================================

    /** 活跃网络是 WiFi 或以太网（电视多用有线，不能只认 WiFi）。 */
    private boolean hasActiveNet() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc == null) return false;
            return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        } catch (Throwable t) {
            return false;
        }
    }

    private WifiInfo wifiInfo() {
        boolean isWifi = false;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network n = cm.getActiveNetwork();
                if (n != null) {
                    NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                    if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        isWifi = true;
                    } else if (nc != null) {
                        return null;    // 蜂窝 / 以太网，不是 WiFi
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 优先 WifiManager.getConnectionInfo()：Android 12+ 对 TransportInfo 里的
        // SSID 做了脱敏（返回 <unknown ssid>），而 getConnectionInfo() 在有定位权限
        // 时仍能拿到真实 SSID，实测这是唯一稳定拿到名字的口子。
        try {
            WifiManager wm = (WifiManager) app.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                WifiInfo wi = wm.getConnectionInfo();
                if (wi != null && wi.getNetworkId() != -1) {
                    String s = wi.getSSID();
                    if (s != null && !"<unknown ssid>".equals(s)) return wi;
                    // 名字还没脱敏成功，但确实是连着的：仍返回它，
                    // 让 RSSI/频段/速率照常记录，SSID 退化成 ?
                    if (!isWifi) isWifi = true;
                    return wi;
                }
            }
        } catch (Throwable ignored) {}

        // 兜底：TransportInfo（部分机型这里不脱敏）
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && isWifi) {
                Network n = cm.getActiveNetwork();
                NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
                if (nc != null && Build.VERSION.SDK_INT >= 29) {
                    Object ti = nc.getTransportInfo();
                    if (ti instanceof WifiInfo) return (WifiInfo) ti;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private String ssidOf(WifiInfo wi) {
        // 先试正规 API（万一哪天系统不脱敏了就自动用上真名）
        String real = realSsid(wi);
        return real;
    }

    /** 正规途径拿真名；拿不到返回 null。 */
    private String realSsid(WifiInfo wi) {
        try {
            String s = wi.getSSID();
            if (s != null && s.length() > 1 && s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length() - 1);
            }
            if (s != null && s.length() > 0
                    && !"<unknown ssid>".equals(s) && !"0x".equals(s) && !"?".equals(s)) {
                return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * 连接指纹：用来在 SSID 被系统脱敏时区分「不同的 WiFi」。
     *
     * 组成：网关网段（前 3 段，同一路由器稳定）+ 频段（2.4G/5G/6G）。
     * 不含 RSSI/速率 —— 那两个随信号波动，当指纹会使同一 WiFi 反复变身份。
     * 结果形如 "192.168.31.x/5G"，用户给它起个名就是日志里的 WiFi 名称。
     */
    static String fingerprint(String gateway, int freqMhz) {
        String net = "?";
        if (gateway != null) {
            String[] p = gateway.split("\\.");
            if (p.length == 4) net = p[0] + "." + p[1] + "." + p[2] + ".x";
            else net = gateway;
        }
        String band;
        if (freqMhz >= 5925) band = "6G";
        else if (freqMhz >= 4900) band = "5G";
        else if (freqMhz >= 2400) band = "2.4G";
        else if (freqMhz == 0) band = "ETH";   // 有线连接（电视常见）
        else band = "?";
        return net + "/" + band;
    }

    /** 读用户给某个指纹起的名字；没有就返回 null。 */
    public String aliasOf(String fp) {
        try {
            String s = app.getSharedPreferences(PREF_ALIAS, Context.MODE_PRIVATE)
                    .getString(fp, null);
            if (s != null && s.length() > 0) return s;
        } catch (Throwable ignored) {}
        return null;
    }

    /** 给某个指纹起名。起过之后该 WiFi 每次连上都自动用这个名字。 */
    public static void setAlias(Context c, String fp, String name) {
        try {
            c.getApplicationContext()
                    .getSharedPreferences(PREF_ALIAS, Context.MODE_PRIVATE)
                    .edit().putString(fp, name).apply();
        } catch (Throwable ignored) {}
    }

    /** 当前连接的指纹（给 UI 用）。 */
    public String currentFingerprint() {
        try {
            if (!hasActiveNet()) return null;
            WifiInfo wi = wifiInfo();
            return fingerprint(Net.gateway(app), wi == null ? 0 : safeFreq(wi));
        } catch (Throwable t) {
            return null;
        }
    }


    private int safeRssi(WifiInfo wi) {
        try { return wi.getRssi(); } catch (Throwable t) { return 0; }
    }

    private int safeFreq(WifiInfo wi) {
        try {
            if (Build.VERSION.SDK_INT >= 21) return wi.getFrequency();
        } catch (Throwable ignored) {}
        return 0;
    }

    private int safeLink(WifiInfo wi) {
        try {
            if (Build.VERSION.SDK_INT >= 21) return wi.getLinkSpeed();
        } catch (Throwable ignored) {}
        return 0;
    }

    private boolean hasLocationPerm() {
        try {
            return app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 无线接口名，一般是 wlan0。 */
    private String ifaceOf() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network n = cm.getActiveNetwork();
                LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
                if (lp != null && lp.getInterfaceName() != null) return lp.getInterfaceName();
            }
        } catch (Throwable ignored) {}
        return "wlan0";
    }

    /**
     * 「供应商接口」目标：优先用 DHCP 下发、且是公网地址的 DNS
     * （运营商自己的 DNS，正好坐落在运营商网络里，测它最贴近「到运营商」）。
     * 下发的都是内网地址（路由器兼做 DNS）时，用公共 DNS 当参考点。
     */
    private String ispTarget() {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network n = cm.getActiveNetwork();
                LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
                if (lp != null) {
                    for (InetAddress a : lp.getDnsServers()) {
                        String h = a.getHostAddress();
                        if (h != null && isPublic(h)) return h;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return FALLBACK_ISP;
    }

    private static boolean isPublic(String ip) {
        try {
            String[] s = ip.split("\\.");
            if (s.length != 4) return false;
            int a = Integer.parseInt(s[0]);
            int b = Integer.parseInt(s[1]);
            if (a == 10 || a == 127 || a == 0) return false;
            if (a == 169 && b == 254) return false;
            if (a == 172 && b >= 16 && b <= 31) return false;
            if (a == 192 && b == 168) return false;
            if (a == 100 && b >= 64 && b <= 127) return false;
            if (a >= 224) return false;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // =====================================================================
    // 落盘
    // =====================================================================

    /**
     * 决定日志落哪。优先根目录（用户要的），没有「所有文件访问」权限时
     * 退到 app 专属外部目录，再退到内部目录 —— 保证任何情况下都能记下来。
     */
    private File resolveFile() {
        // 1) 根目录 /storage/emulated/0/NetPulse-wifi.log
        try {
            File root = new File(Environment.getExternalStorageDirectory(), FILE_NAME);
            if (canAppend(root)) return root;
        } catch (Throwable ignored) {}

        // 2) app 专属外部目录（无需任何权限）
        try {
            File dir = app.getExternalFilesDir(null);
            if (dir != null) {
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, FILE_NAME);
                if (canAppend(f)) return f;
            }
        } catch (Throwable ignored) {}

        // 3) 内部目录兜底
        File f = new File(app.getFilesDir(), FILE_NAME);
        return f;
    }

    private static boolean canAppend(File f) {
        FileOutputStream o = null;
        try {
            o = new FileOutputStream(f, true);
            o.write(new byte[0]);
            o.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (o != null) o.close(); } catch (Throwable ignored) {}
        }
    }

    private boolean ensureWriter() {
        if (w != null) return true;
        try {
            file = resolveFile();
            lastPath = file.getAbsolutePath();
            w = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, true), "UTF-8"), 512);
            headerDone = file.length() > 0L;
            if (!headerDone) {
                w.write("# NetPulse WiFi log v2  (每行一次采样; -1 = 不可达/无数据)\n");
                w.write("# wifi = 该 WiFi 的名称（系统能给出真名时用真名，"
                        + "否则用你在 App 里起的别名；还没起名显示为 [网段/频段]）\n");
                w.write("# 列: time→时间  epoch→秒级时间戳  iface→网卡  wifi→WiFi名称"
                        + "  fp→连接指纹  rssi→信号dBm  freq→频率MHz  link→协商速率Mbps\n");
                w.write("# 延迟: gw_ms→到路由器  isp_ms→到运营商出口  srv_ms→到串流服务器\n");
                w.write("# 抖动: gw_jit / isp_jit / srv_jit → 最近 8 个样本的平均波动(ms)\n");
                w.write("# note: connect→刚连上  switch→换网  steady→周期采样  disconnect→断开\n");
                w.write("time\tepoch\tiface\twifi\tfp\trssi_dbm\tfreq_mhz\tlink_mbps"
                        + "\tgw_ms\tisp_ms\tsrv_ms\tgw_jit\tisp_jit\tsrv_jit\tnote\n");
                w.flush();
                headerDone = true;
            }
            return true;
        } catch (Throwable t) {
            android.util.Log.d(TAG, "打开日志失败: " + t);
            w = null;
            return false;
        }
    }

    private void rotateIfNeeded() {
        try {
            if (file == null || file.length() < MAX_BYTES) return;
            closeWriter();
            File old = new File(file.getAbsolutePath() + ".1");
            if (old.exists()) old.delete();
            file.renameTo(old);
            file = null;
            ensureWriter();
        } catch (Throwable ignored) {}
    }

    private void write(long now, String iface, String name, String fp,
                       int rssi, int freq, int link,
                       int gw, int isp, int srv,
                       double gwJ, double ispJ, double srvJ, String note) {
        if (!ensureWriter()) return;
        try {
            StringBuilder sb = new StringBuilder(160);
            sb.append(fmtTime(now)).append('\t')
              .append(now / 1000L).append('\t')
              .append(nz(iface)).append('\t')
              .append(nz(name)).append('\t')
              .append(nz(fp)).append('\t')
              .append(rssi).append('\t')
              .append(freq).append('\t')
              .append(link).append('\t')
              .append(gw).append('\t')
              .append(isp).append('\t')
              .append(srv).append('\t')
              .append(fmtJ(gwJ)).append('\t')
              .append(fmtJ(ispJ)).append('\t')
              .append(fmtJ(srvJ)).append('\t')
              .append(note == null ? "" : note);

            String line = sb.toString();
            w.write(line);
            w.write('\n');
            w.flush();          // 每行刷一次，进程被杀也不丢数据；一行 30 秒，开销可忽略

            lastLine = line;
            lastAt = now;
            lastFp = fp;
            lastFreq = freq;
            rotateIfNeeded();
        } catch (Throwable t) {
            android.util.Log.d(TAG, "写日志失败: " + t);
            closeWriter();
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String fmtJ(double v) {
        if (v < 0) return "-1";
        return String.format(Locale.US, "%.1f", v);
    }

    private static final ThreadLocal<SimpleDateFormat> SDF =
            new ThreadLocal<SimpleDateFormat>() {
                @Override
                protected SimpleDateFormat initialValue() {
                    return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
                }
            };

    private static String fmtTime(long ms) {
        try {
            return SDF.get().format(new Date(ms));
        } catch (Throwable t) {
            return String.valueOf(ms / 1000L);
        }
    }

    private void closeWriter() {
        try { if (w != null) w.close(); } catch (Throwable ignored) {}
        w = null;
    }
}
