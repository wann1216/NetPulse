# NetPulse

**只针对 LevelUp 前端的云游戏网络延迟悬浮窗 + 串流网络日志。**

一个极简 Android App：进入 LevelUp 云游戏串流画面时，在屏幕左上角显示一条半透明悬浮条，实时显示到服务器的延迟和抖动；退出串流自动消失。串流期间还会把三段网络质量写入根目录日志文件，方便事后回看某次WiFi连接到底卡在哪里。

---

## 适用范围（请先读这一段）

- **只适配 LevelUp**：只识别 LevelUp 的串流包 `com.gold.streaming`（大厅包 `com.gold.launcher` 不算串流）。不是通用测速工具，不适配其他云游戏平台、其他启动器、Steam Link 或其他 App。
- **只做观测，不做修改**：不改游戏画面、不解锁帧率、不改网络路由、不加速、不改任何游戏数据，只是把网络状况显示并记录下来。
- **设备实测**：Odin 2 掌机（Android 13）和 Xiaomi TV S75（Android 11）。最低 Android 8.0（API 26）。
- 如果你用的不是 LevelUp，这个 App 不会显示悬浮窗，也不会写网络日志——这是预期行为，不是故障。

## 它能干什么

1. **进游戏自动出、退游戏自动消失**：独立进程 `:hud` 前台服务监测 `com.gold.streaming` 是否在前台，串口画面一出现就显示悬浮条，回到大厅/桌面就隐藏。无需每次手动开关。
2. **显示延迟和抖动**：悬浮条固定在左上角 6dp × 6dp，176 × 38dp，半透明、点击完全穿透，不挡按键。显示 `RTT ms ± 抖动`，旁边有竖向迷你趋势柱和状态灯。
3. **三段延迟 + 抖动日志**：只在串流期间记录，写 `/sdcard/NetPulse-wifi.log`。30 秒一条，进游戏立刻补采一条。2 MB 自动轮转，最多保留两个文件（约 4 MB）。

### 三档颜色（阈值）

| 延迟 | 档位 | 颜色 |
|---|---|---|
| ≤ 30 ms | 优 | 绿 |
| 31 ~ 50 ms | 一般 | 黄 |
| ≥ 51 ms 或断流 | 差 | 红 |

抖动按 RFC 3550 口径计算：相邻采样差值绝对值的平均值，比标准差更贴近“画面会不会抖”。HUD 每秒采一次，滚动窗口 15 个样本。

### 日志里的三段延迟是什么

| 字段 | 含义 |
|---|---|
| `gw_ms` | 到路由器（家里这一段） |
| `isp_ms` | 到运营商出口（DHCP 下发的公网 DNS，不通时退回公共 DNS） |
| `srv_ms` | 到 LevelUp 串流服务器（真实公网连接，多台取最低） |
| `gw_jit` / `isp_jit` / `srv_jit` | 对应三段的最近样本平均波动 |
| `rssi_dbm` / `freq_mhz` / `link_mbps` | WiFi 信号、频段、协商速率（有线网卡留空） |
| `iface` | `wlan0` 或 `eth0`（电视走有线） |
| `note` | `stream_start` / `steady` 等 |

测量方式是 **TCP 握手 RTT**，不用 ICMP，所以不需要 root、不需要 `CAP_NET_RAW`；端口没开时对方回 RST 也能算出一次 RTT。一次采样最多三次握手，独立低优先级线程，不和游戏抢 CPU。

关于 WiFi 名称：LevelUp 掌机这套 ROM 对 `getSSID()` 做了硬脱敏，定位权限全给也只返回 `?`，BSSID 为空；用 `su` 读真名又会导致 Magisk 每次弹授权框。所以 App 不使用定位权限，改为**连接指纹 + 手动命名**：在 App 主界面点「给当前 WiFi 起个名字」，起一次，之后这个 WiFi 每次连上都会自动用这个名字写日志。

## 怎么用

1. 下载 [`releases/NetPulse-v2.7.apk`](releases/NetPulse-v2.7.apk)，安装到掌机或电视：

   ```bash
   adb install -r NetPulse-v2.7.apk
   ```

2. 打开一次 App，按提示允许「显示在其他应用上层」（悬浮窗）。这是唯一必需权限。
3. 可选：允许「所有文件访问」，让日志直接写到根目录 `/sdcard/`。不授权也没关系，会自动退化到 App 专属目录，照常记录。
4. 启动 LevelUp，点「开始云游戏」进入串流 → 左上角出现悬浮条。
5. 按 HOME 回大厅或退出串流 → 悬浮条消失，日志停止写入。
6. 看日志：

   ```bash
   adb pull /sdcard/NetPulse-wifi.log
   ```

   文件是 TSV，第一行是表头，可以直接用 Excel / WPS / 表格软件打开。

App 有开机自启（监听开机和自身包更新），授权过之后不用每次手动打开。主界面可以一键关闭网络日志；关掉后日志组件完全不启动。

## 需要什么权限

| 权限 | 用途 | 是否必需 |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | 悬浮窗 | **必需** |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | 测延迟、读网卡信息 | 必需 |
| `FOREGROUND_SERVICE` / `POST_NOTIFICATIONS` | 保活服务 | 必需 |
| `MANAGE_EXTERNAL_STORAGE` | 把日志写根目录 | 可选，不授权则写 App 专属目录 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 | 可选 |
| `PACKAGE_USAGE_STATS` | 判断前台是不是串流包 | 需要在设置里授予“使用情况访问” |

**不需要 root**。掌机上有 Magisk 时，App 能按串流进程 uid 从 `/proc/net` 精确找到服务器；电视（Android 11 无 root）上串流连接挂在系统进程名下，app 看不到，v2.7 会在动态发现失败时兜底到实测串流节点，避免延迟掉回只剩网关值。

## 自己编译

仓库只包含源码和构建脚本，**不含工具链**。需要准备：JDK 17、Android API 33 的 `android.jar`、aapt2、D8（r8.jar）、uber-apk-signer，按 `build.sh` 里的相对路径放到 `../andro/` 和上一级目录，然后：

```bash
cd netpulse && bash build.sh
# 产物：build/signed/app-aligned-debugSigned.apk
```

图标可用 `python make_icon.py` 重新生成。

## 目录

```
AndroidManifest.xml          清单与权限
src/com/wann1/netpulse/
  HudService.java            悬浮窗、采样、状态机
  Detector.java              判断 LevelUp 串流是否在前台
  Net.java                   TCP 握探测、服务器发现
  NetLog.java                串流期间的网络日志
  MainActivity.java          设置界面、WiFi 手动命名
  BootReceiver.java          开机 / 更新后自启
  ControlReceiver.java       外部广播控制
res/mipmap-*/                各密度应用图标
releases/NetPulse-v2.7.apk   已签名安装包
```

## License

MIT
