package dev.torcharchive.game;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.util.Log;

import java.util.List;

/**
 * Manages the isolated :nx game child process inside the unified launcher APK.
 */
public final class NxProcessControl {
    private static final String TAG = "TorchArchive";
    public static final String NX_ACTIVITY = "ru.darkest.port.NxActivity";
    public static final String LEGACY_MAIN_ACTIVITY = "ru.darkest.port.MainActivity";

    private NxProcessControl() {}

    public static boolean isGameRunning(Context context) {
        return findNxPid(context) > 0;
    }

    public static boolean stopGameProcess(Context context) {
        int pid = findNxPid(context);
        if (pid <= 0) return false;
        Log.i(TAG, "Stopping :nx process pid=" + pid);
        Process.killProcess(pid);
        long deadline = System.currentTimeMillis() + 650;
        while (System.currentTimeMillis() < deadline) {
            if (findNxPid(context) <= 0) return true;
            try {
                Thread.sleep(40);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return findNxPid(context) <= 0;
    }

    public static void launchGame(Context context) {
        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), NX_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }

    public static void restartAndLaunchGame(Context context) {
        stopGameProcess(context);
        launchGame(context);
    }

    public static void launchLegacyTools(Context context) {
        Intent intent = new Intent();
        intent.setClassName(context.getPackageName(), LEGACY_MAIN_ACTIVITY);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static int findNxPid(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return -1;
            String target = context.getPackageName() + ":nx";
            List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
            if (procs == null) return -1;
            for (ActivityManager.RunningAppProcessInfo proc : procs) {
                if (target.equals(proc.processName)) {
                    return proc.pid;
                }
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }
}
