package com.wann1.netpulse;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 包豪斯风格设置页。
 * 一件正事：让用户选一条授权路径（root 或 adb），授权一次终身有效。
 *
 * 操作方式：触摸 / 手柄 / 遥控器 都能用。
 * 所有可操作元素都可获得焦点，并带明显的焦点框。
 */
public class MainActivity extends Activity {

    // 包豪斯三原色
    private static final int RED = 0xFFE03C31;
    private static final int YELLOW = 0xFFF2C230;
    private static final int BLUE = 0xFF1B4B8F;
    private static final int INK = 0xFF141414;
    private static final int GRAY = 0xFF8A8A8A;
    private static final int PAPER = 0xFFF7F5F0;

    // 内存优化：Typeface 缓存成静态常量，避免 8 处重复 Typeface.create()
    // （每次 create 都会分配新对象，且不会随 Activity 销毁释放）
    private static Typeface TF_BLACK;
    private static Typeface TF_MEDIUM;

    private static Typeface tfBlack() {
        if (TF_BLACK == null) {
            TF_BLACK = Typeface.create("sans-serif-black", Typeface.BOLD);
        }
        return TF_BLACK;
    }

    private static Typeface tfMedium() {
        if (TF_MEDIUM == null) {
            TF_MEDIUM = Typeface.create("sans-serif-medium", Typeface.BOLD);
        }
        return TF_MEDIUM;
    }

    private float d;
    private TextView statusText;
    private TextView overlayState;
    private TextView channelText;
    private TextView logInfo;
    private View firstFocus;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        d = getResources().getDisplayMetrics().density;

        // 打开 App 就自动拉起服务。
        // 原设计只在开机/安装时由 BootReceiver 启动，导致「点开 App 又直接进游戏」
        // 或服务被停掉后完全没有出口，用户必须手动点按钮才行（实际就是打不开）。
        // 服务常驻无副作用：不在串流时它不测量、不建 socket、不显示悬浮窗。
        try {
            Intent svc = new Intent(this, HudService.class);
            svc.setAction(HudService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc);
            else startService(svc);
        } catch (Throwable ignored) {}

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAPER);
        scroll.setFocusable(false);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(22), px(28), px(22), px(34));
        root.setFocusable(false);
        root.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("NetPulse");
        title.setTextSize(34);
        title.setTextColor(INK);
        title.setTypeface(tfBlack());
        title.setPadding(0, 0, 0, px(2));
        title.setFocusable(false);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("云游戏延迟监测   ·   RTT / 抖动");
        sub.setTextSize(12);
        sub.setTextColor(GRAY);
        sub.setPadding(0, 0, 0, px(18));
        sub.setFocusable(false);
        root.addView(sub);

        root.addView(statusCard());

        // ===== 01 检测方式 =====
        root.addView(sectionLabel("01", "检测方式（二选一）"));

        View rootBtn = optionCard(
                "root",
                "Magisk 弹一次，永久记住",
                "系统弹一次授权框，勾选「记住」即终身有效。之后完全自动，不再打扰。",
                RED,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent i = new Intent(MainActivity.this, HudService.class);
                        i.setAction(HudService.ACTION_START);
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                        else startService(i);
                        refresh();
                    }
                },
                "用 root 启动");
        root.addView(rootBtn);
        firstFocus = rootBtn;

        root.addView(optionCard(
                "使用情况访问",
                "设置里点一次开关，不用电脑",
                "跳到「使用情况访问」页，把 NetPulse 的开关打开。开一次永久生效，"
                        + "之后既不需要电脑，也不会再弹框。",
                BLUE,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        try {
                            startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
                        } catch (Throwable t) {
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                        }
                    }
                },
                "去开启"));

        // ===== 02 悬浮窗 =====
        root.addView(sectionLabel("02", "悬浮窗权限（必需）"));

        root.addView(optionCard(
                "显示在其他应用上层",
                "唯一必须要的权限",
                "没有它就无法显示悬浮条。在设置里打开一次，永久有效。",
                YELLOW,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startActivity(new Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    }
                },
                "去授权"));

        // ===== 03 WiFi 日志 =====
        root.addView(sectionLabel("03", "WiFi 网络日志"));

        root.addView(optionCard(
                "记录每段 WiFi 的连接质量",
                "路由器 / 运营商 / 服务器 三段延迟 + 抖动",
                "每 30 秒记一行，WiFi 连上或换网时立即补记一条。"
                        + "写到根目录 NetPulse-wifi.log，事后可回溯「那天卡是哪一段的问题」。"
                        + "不在 WiFi 上就完全停测，不占资源。",
                BLUE,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean on = !logOn();
                        setLogOn(on);
                        if (on) {
                            // 打开时顺手把服务拉起来，并让日志立刻补一条
                            try {
                                Intent i = new Intent(MainActivity.this, HudService.class);
                                i.setAction(HudService.ACTION_START);
                                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
                                else startService(i);
                            } catch (Throwable ignored) {}
                        }
                        refresh();
                    }
                },
                logOn() ? "已开启 · 点此关闭" : "已关闭 · 点此开启"));

        root.addView(optionCard(
                "日志写入根目录",
                "需要「所有文件访问」权限",
                "授予后日志落在 /sdcard/NetPulse-wifi.log，用文件管理器就能直接打开。"
                        + "不授权也能记，只是会放到 App 专属目录里。",
                YELLOW,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openAllFilesAccess();
                    }
                },
                "去授权"));

        root.addView(optionCard(
                "给当前 WiFi 起个名字",
                "起一次就够，之后自动按名字记录",
                "系统不把这个 WiFi 的真实名字告诉 App（这台机器做了脱敏），"
                        + "所以先给当前连接起个名，比如「家里主路由」。"
                        + "以后每次连上它，日志里都用这个名字，方便你对号入座。",
                BLUE,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        askWifiName();
                    }
                },
                "起名 / 改名"));

        logInfo = new TextView(this);
        logInfo.setTextSize(11);
        logInfo.setTextColor(0xFF555555);
        logInfo.setLineSpacing(px(3), 1f);
        logInfo.setBackgroundColor(0xFFFDFDFD);
        logInfo.setPadding(px(14), px(11), px(14), px(11));
        logInfo.setFocusable(false);
        LinearLayout.LayoutParams lip = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lip.bottomMargin = px(10);
        logInfo.setLayoutParams(lip);
        root.addView(logInfo);

        // ===== 04 说明 =====
        root.addView(sectionLabel("04", "说明"));

        TextView note = new TextView(this);
        note.setText("悬浮条只在进入云游戏串流画面时出现，退出立即消失。"
                + "全程不遮挡操作、不接管触摸。\n\n"
                + "第一次用需要授权，之后终身有效 —— 不会再反复问你。"
                + "\n\n手柄 / 遥控器：用方向键移动，按 A 键或 OK 键确认。");
        note.setTextSize(13);
        note.setTextColor(INK);
        note.setLineSpacing(px(5), 1f);
        note.setBackgroundColor(0xFFFDFDFD);
        note.setPadding(px(14), px(13), px(14), px(13));
        note.setFocusable(false);
        root.addView(note);

        root.addView(stopRow());

        setContentView(scroll);

        // 状态栏文字转深色，配合白底界面
        try {
            android.view.Window w = getWindow();
            if (Build.VERSION.SDK_INT >= 30) {
                w.setStatusBarColor(PAPER);
                w.getDecorView().getWindowInsetsController()
                        .setSystemBarsAppearance(
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            } else if (Build.VERSION.SDK_INT >= 23) {
                w.setStatusBarColor(PAPER);
                w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        } catch (Throwable ignored) {}
        refresh();

        // 默认把焦点给第一个按钮，手柄/遥控器一进来就能直接按 A/OK
        if (firstFocus != null) {
            firstFocus.post(new Runnable() {
                @Override
                public void run() {
                    firstFocus.requestFocus();
                }
            });
        }
    }

    /**
     * 兜底：如果方向键按下时还没有焦点（某些遥控器首次事件不带焦点），
     * 主动把焦点送到第一个可操作控件上。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();
            boolean nav = k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                    || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT
                    || k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                    || k == KeyEvent.KEYCODE_BUTTON_A;
            if (nav && getCurrentFocus() == null && firstFocus != null) {
                firstFocus.requestFocus();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    /** 手柄 B / 遥控器返回键：正常返回上一级。 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            onBackPressed();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** 状态卡：白底、无边框、方角。 */
    private View statusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.WHITE);
        bg.setCornerRadius(0);
        card.setBackground(bg);
        card.setPadding(px(15), px(13), px(15), px(13));
        card.setFocusable(false);

        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.bottomMargin = px(4);
        card.setLayoutParams(cp);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        statusText.setTypeface(tfMedium());
        statusText.setTextColor(INK);
        card.addView(statusText);

        overlayState = new TextView(this);
        overlayState.setTextSize(12);
        overlayState.setTextColor(INK);
        overlayState.setPadding(0, px(7), 0, 0);
        card.addView(overlayState);

        channelText = new TextView(this);
        channelText.setTextSize(11);
        channelText.setTextColor(GRAY);
        channelText.setPadding(0, px(5), 0, 0);
        card.addView(channelText);

        return card;
    }

    /** 章节标题：黑底白字编号 + 标题。 */
    private View sectionLabel(String num, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(false);

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = px(20);
        rp.bottomMargin = px(9);
        row.setLayoutParams(rp);

        TextView n = new TextView(this);
        n.setText(num);
        n.setTextSize(11);
        n.setTextColor(Color.WHITE);
        n.setTypeface(tfBlack());
        n.setBackgroundColor(INK);
        n.setPadding(px(6), px(2), px(6), px(2));
        row.addView(n);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(INK);
        t.setTypeface(tfMedium());
        t.setPadding(px(9), 0, 0, 0);
        row.addView(t);

        return row;
    }

    /**
     * 授权选项卡。
     * 只有内部按钮承接焦点（避免遥控器按方向键就误触发整卡），
     * 但整卡仍保留触摸点击，方便手指点。
     */
    private View optionCard(final String title, String badge, String desc,
                            final int accent, final View.OnClickListener onClick,
                            String btnLabel) {
        final LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        // 卡片本身不可聚焦：焦点只在按钮上，方向键才不会误触
        outer.setFocusable(false);
        outer.setClickable(true);
        // 卡片固定浅灰白底、无边框、任何状态都不变（不闪）
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFFFDFDFD);
        cardBg.setCornerRadius(0);
        outer.setBackground(cardBg);
        if (Build.VERSION.SDK_INT >= 21) outer.setElevation(0f);
        outer.setOnClickListener(onClick);

        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        op.bottomMargin = px(10);
        outer.setLayoutParams(op);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(14), px(13), px(14), px(13));
        body.setFocusable(false);
        body.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        outer.addView(body, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(15);
        t.setTextColor(INK);
        t.setTypeface(tfMedium());
        t.setFocusable(false);
        body.addView(t);

        TextView bd = new TextView(this);
        bd.setText(badge);
        bd.setTextSize(11);
        bd.setTextColor(0xFF666666);
        bd.setTypeface(tfMedium());
        bd.setPadding(0, px(3), 0, px(6));
        bd.setFocusable(false);
        body.addView(bd);

        TextView ds = new TextView(this);
        ds.setText(desc);
        ds.setTextSize(12);
        ds.setTextColor(0xFF555555);
        ds.setLineSpacing(px(3.5f), 1f);
        ds.setPadding(0, 0, 0, px(11));
        ds.setFocusable(false);
        body.addView(ds);

        Button btn = new Button(this);
        btn.setText(btnLabel);
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setTypeface(tfMedium());
        btn.setBackground(buttonDrawable(accent));
        btn.setStateListAnimator(null);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(false);
        btn.setTextColor(buttonTextColor());
        // 关掉系统默认的涟漪
        if (Build.VERSION.SDK_INT >= 21) btn.setElevation(0f);
        btn.setPadding(px(10), 0, px(10), 0);
        btn.setOnClickListener(onClick);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(48));
        bp.topMargin = px(6);
        bp.bottomMargin = px(3);
        body.addView(btn, bp);

        return outer;
    }

    private View stopRow() {
        Button btn = new Button(this);
        btn.setText("退出监测");
        btn.setAllCaps(false);
        btn.setTextSize(14);
        btn.setTypeface(tfMedium());
        btn.setBackground(buttonOutline());
        btn.setStateListAnimator(null);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(false);
        btn.setTextColor(buttonTextColor());
        if (Build.VERSION.SDK_INT >= 21) btn.setElevation(0f);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, HudService.class);
                i.setAction(HudService.ACTION_STOP);
                startService(i);
                refresh();
            }
        });

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(48));
        p.topMargin = px(18);
        btn.setLayoutParams(p);
        return btn;
    }

    // ---------- 焦点可视化 ----------

    /** 按钮统一外观：黑边 + 白底 + 黑字，任何状态都不变色。 */
    private StateListDrawable buttonDrawable(int accent) {
        return uniformButton();
    }

    /**
     * 按钮统一外观：黑边 + 白底 + 黑字。
     * 聚焦/按下时只把黑边加粗（不变颜色），保证手柄和遥控器能看出选中项。
     */
    private StateListDrawable uniformButton() {
        // 白底 + 2.5dp 黑边；卡片也是白底，所以靠粗黑边区分边界
        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.WHITE);
        normal.setStroke(px(2.5f), INK);
        normal.setCornerRadius(0);

        // 聚焦/按下：黑边加粗到 5dp（不换颜色，只加粗）
        GradientDrawable active = new GradientDrawable();
        active.setColor(Color.WHITE);
        active.setStroke(px(5f), INK);
        active.setCornerRadius(0);

        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, active);
        s.addState(new int[]{android.R.attr.state_pressed}, active);
        s.addState(new int[]{}, normal);
        return s;
    }

    /** 文字恒定黑色，不随状态变化。 */
    private android.content.res.ColorStateList buttonTextColor() {
        return android.content.res.ColorStateList.valueOf(INK);
    }

    /** 描边按钮：与实心按钮统一为黑边白底黑字。 */
    private StateListDrawable buttonOutline() {
        return uniformButton();
    }

    private int px(float v) {
        return (int) (v * d + 0.5f);
    }

    // ---------- WiFi 日志 ----------

    private boolean logOn() {
        return getSharedPreferences(HudService.PREF, MODE_PRIVATE)
                .getBoolean(HudService.KEY_LOG, true);
    }

    private void setLogOn(boolean on) {
        getSharedPreferences(HudService.PREF, MODE_PRIVATE)
                .edit().putBoolean(HudService.KEY_LOG, on).apply();
    }

    /** 是否有「所有文件访问」权限（决定日志能不能落在根目录）。 */
    private boolean hasAllFiles() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                return android.os.Environment.isExternalStorageManager();
            }
            return true;    // Android 10 及以下直接可写根目录
        } catch (Throwable t) {
            return false;
        }
    }

    private void openAllFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                Intent i = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
                return;
            }
        } catch (Throwable ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                return;
            }
        } catch (Throwable ignored) {}
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Throwable ignored) {}
    }

    /** 弹出输入框给当前 WiFi 起名。用系统对话框，手柄也能操作。 */
    private void askWifiName() {
        final NetLog nl;
        final String fp;
        try {
            nl = NetLog.get(getApplicationContext());
            fp = nl.currentFingerprint();
        } catch (Throwable t) {
            toast("暂时取不到当前连接，先连上 WiFi 再试");
            return;
        }
        if (fp == null) {
            toast("当前没连 WiFi");
            return;
        }

        final android.widget.EditText et = new android.widget.EditText(this);
        et.setHint("例如：家里主路由");
        et.setTextSize(16);
        String cur = null;
        try { cur = nl.aliasOf(fp); } catch (Throwable ignored) {}
        if (cur != null) et.setText(cur);
        et.setSelectAllOnFocus(true);

        new android.app.AlertDialog.Builder(this)
                .setTitle("给这个 WiFi 起名")
                .setMessage("当前连接：" + fp)
                .setView(et)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        String name = et.getText() == null ? "" : et.getText().toString().trim();
                        if (name.length() == 0) {
                            toast("名字不能为空");
                            return;
                        }
                        NetLog.setAlias(MainActivity.this, fp, name);
                        // 立刻补一条，让日志里马上出现新名字
                        try { nl.poke(); } catch (Throwable ignored) {}
                        refresh();
                        toast("已保存：" + name);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void toast(String s) {
        try {
            android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {}
    }

    private void refreshLogInfo() {
        if (logInfo == null) return;
        StringBuilder sb = new StringBuilder();

        NetLog nl = null;
        try {
            nl = NetLog.get(getApplicationContext());
        } catch (Throwable ignored) {}

        if (!logOn()) {
            sb.append("状态：已关闭（不采集、不建连接）\n");
        } else if (nl != null && nl.isRunning()) {
            sb.append("状态：运行中 · 每 30 秒一行，换网立即补记\n");
        } else {
            sb.append("状态：已开启，等待服务启动\n");
        }

        String root = "/sdcard/" + NetLog.FILE_NAME;
        sb.append("根目录：").append(hasAllFiles()
                ? "已授权（写到 " + root + "）"
                : "未授权（暂存 App 专属目录）");

        // 当前这一路 WiFi 叫什么、日志里会怎么记
        try {
            String fp = nl == null ? null : nl.currentFingerprint();
            if (fp != null) {
                String alias = nl.aliasOf(fp);
                sb.append("\n当前 WiFi：").append(alias != null ? alias : "未命名")
                  .append("（").append(fp).append("）");
            } else {
                sb.append("\n当前 WiFi：未连接");
            }
        } catch (Throwable ignored) {}

        if (nl != null) {
            String p = nl.path();
            if (p != null && p.length() > 0) {
                sb.append("\n实际文件：").append(p);
            }
            String last = nl.lastLine();
            long at = nl.lastAt();
            if (last != null && last.length() > 0 && at > 0) {
                sb.append("\n最近一条：").append(last);
            }
        }

        logInfo.setText(sb.toString());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean usage = Detector.hasUsageAccess(this);
        boolean root = Detector.hasRoot();

        if (!overlay) {
            statusText.setText("待授权：悬浮窗");
            statusText.setTextColor(RED);
        } else if (!usage && !root) {
            statusText.setText("待授权：检测方式");
            statusText.setTextColor(0xFFB8860B);
        } else {
            statusText.setText("已就绪");
            statusText.setTextColor(0xFF1E7B3E);
        }

        overlayState.setText("悬浮窗：" + (overlay ? "已授权" : "未授权"));

        String ch;
        if (usage && root) ch = "检测通道：使用情况访问（root 可作备份）";
        else if (usage) ch = "检测通道：使用情况访问";
        else if (root) ch = "检测通道：root";
        else ch = "检测通道：未授权";
        channelText.setText(ch + " · 授权一次终身有效");
        refreshLogInfo();
    }
}
