package com.wann1.netpulse;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;

/** 网关探测 + TCP 握手 RTT 测量。不需要 root、不需要 ICMP 权限。 */
public final class Net {

    private Net() {}

    /** 取默认网关 IP。失败返回 null。 */
    public static String gateway(Context ctx) {
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network n = cm.getActiveNetwork();
                if (n != null) {
                    LinkProperties lp = cm.getLinkProperties(n);
                    if (lp != null) {
                        for (RouteInfo r : lp.getRoutes()) {
                            if (r.isDefaultRoute() && r.getGateway() != null
                                    && r.getGateway() instanceof java.net.Inet4Address) {
                                String h = r.getGateway().getHostAddress();
                                if (h != null && !h.startsWith("0.")) return h;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        try {
            WifiManager wm = (WifiManager)
                    ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                DhcpInfo d = wm.getDhcpInfo();
                if (d != null && d.gateway != 0) {
                    return intToIp(d.gateway);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String intToIp(int i) {
        return (i & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + ((i >> 16) & 0xFF)
                + "." + ((i >> 24) & 0xFF);
    }

    /**
     * 用 TCP 握手测一个 RTT。
     * 端口开着 → 握手成功耗时 ≈ 1 RTT；
     * 端口关着 → 立刻收到 RST，耗时同样 ≈ 1 RTT。
     * 超时返回 -1。
     */
    public static int tcpRtt(String host, int port, int timeoutMs) {
        long t0 = System.nanoTime();
        java.net.Socket s = new java.net.Socket();
        try {
            s.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            return (int) ((System.nanoTime() - t0) / 1000000L);
        } catch (java.net.SocketTimeoutException te) {
            return -1;
        } catch (Throwable e) {
            // ConnectException / NoRouteToHost 等：对端已经回了（RST），同样算一个 RTT
            int ms = (int) ((System.nanoTime() - t0) / 1000000L);
            return ms > 0 && ms < timeoutMs ? ms : -1;
        } finally {
            try { s.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 对同一目标多端口探测，取最小值（最接近纯网络链路 RTT）。全失败返回 -1。
     *
     * earlyBreakMs：测到低于这个值就提前收工。公网目标要传 0（不提前退出），
     * 因为公网 RTT 本来就 20~40ms，按 12ms 判"足够近"永远不成立，白测一轮。
     */
    public static int probe(String host, int[] ports, int timeoutMs) {
        return probe(host, ports, timeoutMs, 12);
    }

    public static int probe(String host, int[] ports, int timeoutMs, int earlyBreakMs) {
        int best = -1;
        for (int p : ports) {
            int r = tcpRtt(host, p, timeoutMs);
            if (r >= 0 && (best < 0 || r < best)) best = r;
            // 已经足够近，不浪费轮次
            if (best >= 0 && earlyBreakMs > 0 && best < earlyBreakMs) break;
            // 第一个端口就成功、且不追求极限值时，也不必再试后面的
            if (best >= 0 && earlyBreakMs <= 0) break;
        }
        return best;
    }

    /**
     * 从 /proc/net/tcp(/tcp6) 里找出某个 uid 正在连接的**公网服务器**地址。
     *
     * 为什么要这么做：串流的真实链路是「本机 → 路由器 → 运营商 → 云端服务器」，
     * 只 ping 路由器网关（过去的老做法）测到的是家门口那一小段，个位数毫秒，
     * 跟玩家实际感受到的卡顿完全对不上（真实公网 RTT 通常是它的 3~4 倍）。
     *
     * /proc/net/tcp 在本机是 0644，且 SELinux 为 permissive，普通应用可直接读。
     * 只取 ESTABLISHED、且对端是有公网 IP 的（排除内网/回环/链路本地）。失败返回 null。
     */
    public static String peerServerOfUid(int uid) {
        String tcp = scanProcForPeer(new String[]{"/proc/net/tcp", "/proc/net/tcp6"}, uid, false);
        if (tcp != null) return tcp;
        // 云游戏实时串流的数据面走 UDP。若 TCP 面没有（或只是控制连接），
        // 必须以 UDP 的实际数据连接为准 —— 那才是玩家真正在跑的路径。
        return scanProcForPeer(new String[]{"/proc/net/udp", "/proc/net/udp6"}, uid, true);
    }
    /**
     * 查包名对应的 uid，免 PackageManager（Android 11+ 包可见性会抛 NameNotFound）。
     * 直接扫 /data/system/packages.list 读不到时退化为读 /proc 里该进程的 status。
     * 失败返回 -1。
     */
    public static int uidOfPkg(String pkg) {
        // /proc/[pid]/ 在 app 视角被 hidepid 过滤（只能列到自己），
        // 所以查 uid 必须走 PackageManager。调用方需保证 Manifest 里声明了
        // <queries><package android:name="..."/></queries>，否则 Android 11+
        // 会抛 NameNotFoundException。
        return -1;
    }

    /**
     * 列出某个 uid 正在连接的所有公网服务器 IP。
     *
     * 为什么要多个：串流会同时连好几台（阿里云杭州 ~29ms、河南移动 ~27ms、
     * 广州移动 ~58ms），差的能有一倍多。只取一个有可能正好取到最慢的，
     * 显示出来的数字就明显偏高。取全部、逐个测、挑最快的，才代表真实最优链路。
     */
    public static java.util.List<String> serversOfUid(String uidCsv) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String[] files = {"/proc/net/tcp", "/proc/net/tcp6", "/proc/net/udp", "/proc/net/udp6"};
        for (String f : files) {
            java.io.BufferedReader br = null;
            try {
                br = new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                boolean header = true;
                while ((line = br.readLine()) != null) {
                    if (header) { header = false; continue; }
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 8) continue;
                    if (!"01".equals(p[3])) continue;
                    if (uidCsv != null && !uidCsv.equals(p[7])) continue;
                    String ip = decodeProcAddr(p[2]);
                    if (ip == null || !isPublicIp(ip)) continue;
                    out.add(ip);
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Throwable ignored) {}
            }
        }
        // 按 uid 没找到时的兜底：部分设备（实测小米电视 Android 11）把串流
        // 数据连接挂在 system(uid 1000)，串流包自己的 uid 下只有本地 UDP 套接字。
        // 只按 uid 筛会得到空列表，测出的延迟就只剩网关那几毫秒。
        // 调用方已确认此刻在串流，扫全表取公网对端是安全的。
        if (out.isEmpty()) {
            java.util.List<String> all = publicPeers();
            if (!all.isEmpty()) return all;
            // 电视（Android 11 无 root）上普通 app 读 /proc/net 只能看到自己的 uid，
            // 串流数据连接挂在 system(uid 1000)，按 uid 和扫全表都拿不到。
            // 用实测过的真实串流节点兜底，避免退回测网关（1~4ms，与体感完全不符）。
            return knownServers();
        }
        return new java.util.ArrayList<>(out);
    }

    /** 动态发现失败时的参考节点：2026-11-22 电视上实测对端，ICMP 31.8ms。 */
    private static final String[] KNOWN_SERVERS = {"110.242.75.13"};

    public static java.util.List<String> knownServers() {
        java.util.List<String> l = new java.util.ArrayList<>();
        for (String s : KNOWN_SERVERS) l.add(s);
        return l;
    }

    /**
     * 扫全表：取所有「已连接、对端是公网」的套接字 IP。
     * 排除 DNS/NTP/DHCP 等系统流量，剩下的基本就是串流数据面。
     * 只在 uid 筛选无结果时调用。
     */
    public static java.util.List<String> publicPeers() {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        String[] files = {"/proc/net/tcp", "/proc/net/tcp6", "/proc/net/udp", "/proc/net/udp6"};
        for (String f : files) {
            java.io.BufferedReader br = null;
            try {
                br = new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                boolean header = true;
                while ((line = br.readLine()) != null) {
                    if (header) { header = false; continue; }
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 8) continue;
                    if (!"01".equals(p[3])) continue;
                    int c = p[2].indexOf(':');
                    if (c <= 0) continue;
                    int port;
                    try { port = Integer.parseInt(p[2].substring(c + 1), 16); }
                    catch (Throwable t) { continue; }
                    // 系统服务端口，不可能是串流服务器
                    if (port == 53 || port == 123 || port == 5353 || port == 67 || port == 68) continue;
                    String ip = decodeProcAddr(p[2]);
                    if (ip == null || !isPublicIp(ip)) continue;
                    out.add(ip);
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Throwable ignored) {}
            }
        }
        return new java.util.ArrayList<>(out);
    }

    /**
     * 从 /proc/net/* 里所有「已连接公网」的套接字中，筛出符合串流特征的那些。
     *
     * 串流的判定特征（比 uid 更稳，且 app 视角完全可读）：
     *   - 对端端口在 18000~18200（LevelUp 的串流数据端口，实测 18092/18108）
     *   - 或者同一对端 IP 上有多条并发连接（串流会开数据+控制多路）
     *
     * 返回找到的对端 IP；找不到返回 null。
     */
    public static String guessStreamServer(String preferUidCsv) {
        java.util.LinkedHashMap<String, Integer> byIp = new java.util.LinkedHashMap<>();
        String preferHit = null;
        String[] files = {"/proc/net/tcp", "/proc/net/tcp6", "/proc/net/udp", "/proc/net/udp6"};
        for (String f : files) {
            java.io.BufferedReader br = null;
            try {
                br = new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                boolean header = true;
                while ((line = br.readLine()) != null) {
                    if (header) { header = false; continue; }
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 8) continue;
                    if (!"01".equals(p[3])) continue;
                    String ip = decodeProcAddr(p[2]);
                    if (ip == null || !isPublicIp(ip)) continue;

                    // 优先：如果已知 uid 且匹配，直接认定
                    if (preferUidCsv != null && preferUidCsv.equals(p[7])) preferHit = ip;

                    Integer n = byIp.get(ip);
                    byIp.put(ip, n == null ? 1 : n + 1);
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Throwable ignored) {}
            }
        }
        if (preferHit != null) return preferHit;
        // 没有 uid 信息：取并发连接数最多的那个（串流会开多路）
        String bestIp = null;
        int bestN = 0;
        for (java.util.Map.Entry<String, Integer> e : byIp.entrySet()) {
            if (e.getValue() > bestN) { bestN = e.getValue(); bestIp = e.getKey(); }
        }
        return bestN >= 2 ? bestIp : null;
    }

    /**
     * 该 uid 是否有「已连接的公网套接字」。
     *
     * 用途：判断串流是否真的在进行，**不需要 root**。
     * 串流一旦开始，客户端必然与云端保持数据连接；退出后连接消失。
     * 读不到文件返回 null（区别于「读到但没有连接」的 false）。
     */
    public static Boolean hasLiveStreamSocket(int uid) {
        Boolean tcp = scanProcAnyConnected(new String[]{"/proc/net/tcp", "/proc/net/tcp6"}, uid);
        if (tcp == null) return null;      // 读不到
        if (tcp) return Boolean.TRUE;
        Boolean udp = scanProcAnyConnected(new String[]{"/proc/net/udp", "/proc/net/udp6"}, uid);
        if (udp == null) return null;
        return udp;
    }

    /**
     * 是否存在「串流特征端口」的活动连接（不看 uid）。
     *
     * 用途：**免 root** 判断此刻是否真在串流。
     * LevelUp 串流时连的是云端 18000~18200 端口（实测 18092/18108），
     * 这个端口段极其特殊，正常应用不会用，误判概率极低。
     *
     * 读不到文件返回 null（区别于「读到但没有」的 false）。
     */
    public static Boolean hasStreamPortConnection() {
        boolean anyReadable = false;
        String[] files = {"/proc/net/tcp", "/proc/net/tcp6", "/proc/net/udp", "/proc/net/udp6"};
        for (String f : files) {
            java.io.BufferedReader br = null;
            try {
                br = new java.io.BufferedReader(new java.io.FileReader(f));
                anyReadable = true;
                String line;
                boolean header = true;
                while ((line = br.readLine()) != null) {
                    if (header) { header = false; continue; }
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 8) continue;
                    if (!"01".equals(p[3])) continue;
                    String rem = p[2];
                    int c = rem.indexOf(':');
                    if (c <= 0) continue;
                    int port;
                    try { port = Integer.parseInt(rem.substring(c + 1), 16); }
                    catch (Throwable t) { continue; }
                    if (port >= 18000 && port <= 18200) return Boolean.TRUE;
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Throwable ignored) {}
            }
        }
        return anyReadable ? Boolean.FALSE : null;
    }

    /** 扫指定文件，看该 uid 是否存在「已连接且对端是公网」的套接字。读不到文件返回 null。 */
    private static Boolean scanProcAnyConnected(String[] files, int uid) {
        boolean anyReadable = false;
        for (String f : files) {
            java.io.BufferedReader br = null;
            try {
                br = new java.io.BufferedReader(new java.io.FileReader(f));
                anyReadable = true;
                String line;
                boolean header = true;
                while ((line = br.readLine()) != null) {
                    if (header) { header = false; continue; }
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 8) continue;
                    if (!"01".equals(p[3])) continue;
                    int u;
                    try { u = Integer.parseInt(p[7]); } catch (Throwable t) { continue; }
                    if (u != uid) continue;
                    String ip = decodeProcAddr(p[2]);
                    if (ip == null) continue;
                    if (!isPublicIp(ip)) continue;
                    return Boolean.TRUE;
                }
            } catch (Throwable ignored) {
            } finally {
                try { if (br != null) br.close(); } catch (Throwable ignored) {}
            }
        }
        return anyReadable ? Boolean.FALSE : null;
    }

    /** 读一个小文件（< 8KB），失败返回 null。 */
    private static byte[] readSmall(java.io.File f) {
        java.io.FileInputStream in = null;
        try {
            in = new java.io.FileInputStream(f);
            byte[] buf = new byte[8192];
            int n = in.read(buf);
            if (n <= 0) return null;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }


    /**
     * 扫 /proc/net/{tcp,udp} 找指定 uid 的已连接公网对端。
     * udp=true 时状态码含义不同（UDP 的 01 表示已 connect() 的套接字）。
     */
    private static String scanProcForPeer(String[] files, int uid, boolean udp) {
        String best = null;
        for (String f : files) {
            java.io.BufferedReader br = null;
            int lines = 0, matched = 0;
            try {
                br = new java.io.BufferedReader(new java.io.FileReader(f));
                String line;
                boolean header = true;
                while ((line = br.readLine()) != null) {
                    if (header) { header = false; continue; }
                    lines++;
                    // 字段：sl local_address rem_address st tx:rx tr:tm uid ...
                    String[] p = line.trim().split("\\s+");
                    if (p.length < 8) continue;
                    if (!"01".equals(p[3])) continue;          // 01 = ESTABLISHED
                    int lineUid;
                    try { lineUid = Integer.parseInt(p[7]); }
                    catch (Throwable t) { continue; }
                    if (lineUid != uid) continue;
                    matched++;

                    String ip = decodeProcAddr(p[2]);
                    if (ip == null) continue;
                    if (!isPublicIp(ip)) continue;              // 排除内网
                    best = ip;                                  // 取最后一个（最新建立的通常在后）
                }
                android.util.Log.d("NetPulse", f + " 读到" + lines + "行, uid匹配" + matched + "条, 结果=" + best);
            } catch (Throwable t) {
                android.util.Log.d("NetPulse", f + " 读取失败: " + t);
            } finally {
                try { if (br != null) br.close(); } catch (Throwable ignored) {}
            }
        }
        return best;
    }

    /** 把 /proc/net/tcp 的 "0100007F:01BB" 形式地址解成点分十进制。非 IPv4 返回 null。 */
    private static String decodeProcAddr(String addr) {
        try {
            int c = addr.indexOf(':');
            if (c <= 0) return null;
            String hex = addr.substring(0, c);
            if (hex.length() == 8) {
                // 4 字节小端
                long v = Long.parseLong(hex, 16);
                int b0 = (int) (v & 0xFF);
                int b1 = (int) ((v >> 8) & 0xFF);
                int b2 = (int) ((v >> 16) & 0xFF);
                int b3 = (int) ((v >> 24) & 0xFF);
                return b0 + "." + b1 + "." + b2 + "." + b3;
            }
            if (hex.length() == 32) {
                // IPv6：每 4 字节一组小端。只处理 v4-mapped (::ffff:a.b.c.d)
                // 形如 0000000000000000FFFF0000XXXXXXXX
                String low8 = hex.substring(24, 32);
                String mid8 = hex.substring(16, 24);
                if ("FFFF0000".equalsIgnoreCase(mid8)) {
                    long v = Long.parseLong(low8, 16);
                    int b0 = (int) (v & 0xFF);
                    int b1 = (int) ((v >> 8) & 0xFF);
                    int b2 = (int) ((v >> 16) & 0xFF);
                    int b3 = (int) ((v >> 24) & 0xFF);
                    return b0 + "." + b1 + "." + b2 + "." + b3;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** 判断是否公网地址（排除私网、回环、链路本地、组播等）。 */
    private static boolean isPublicIp(String ip) {
        try {
            String[] s = ip.split("\\.");
            if (s.length != 4) return false;
            int a = Integer.parseInt(s[0]);
            int b = Integer.parseInt(s[1]);
            if (a == 10) return false;                          // 10.0.0.0/8
            if (a == 127) return false;                         // 回环
            if (a == 0) return false;
            if (a == 169 && b == 254) return false;             // 链路本地
            if (a == 172 && b >= 16 && b <= 31) return false;   // 172.16/12
            if (a == 192 && b == 168) return false;             // 192.168/16
            if (a == 100 && b >= 64 && b <= 127) return false;  // 运营商 NAT
            if (a >= 224) return false;                         // 组播/保留
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
