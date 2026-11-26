package com.wann1.netpulse;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 供 shell / 脚本通过 am broadcast 控制显隐。 */
public class ControlReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent == null ? null : intent.getAction();

        if (HudService.ACTION_STOP.equals(a)) {
            // 直接 stopService 才可靠：服务已在运行时 startService 不会触发 onStartCommand
            try { ctx.stopService(new Intent(ctx, HudService.class)); } catch (Throwable ignored) {}
            return;
        }

        if (HudService.ACTION_START.equals(a)) {
            Intent svc = new Intent(ctx, HudService.class);
            svc.setAction(HudService.ACTION_START);
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(svc);
                else ctx.startService(svc);
            } catch (Throwable t) {
                try { ctx.startService(svc); } catch (Throwable ignored) {}
            }
        }
    }
}
