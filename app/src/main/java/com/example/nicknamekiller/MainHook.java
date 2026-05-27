package com.example.nicknamekiller;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TARGET_PACKAGE = "com.yunzhi.tiyu";
    private static final String TARGET_NICK_NAME = "王志超";
    private static final String PREFS_PATH = "/data/user/0/com.yunzhi.tiyu/shared_prefs/Campus.xml";
    private static final long CHECK_INTERVAL = 1000;

    private final AtomicBoolean monitoring = new AtomicBoolean(false);
    private String lastNickName = null;
    private Activity currentActivity = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(TARGET_PACKAGE)) {
            return;
        }

        XposedBridge.log("NickNameKiller: 检测到云运动应用启动");

        // Hook Activity.onCreate() 保存当前Activity并启动持续检测
        XposedHelpers.findAndHookMethod(
                Activity.class,
                "onCreate",
                Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        currentActivity = (Activity) param.thisObject;
                        if (monitoring.compareAndSet(false, true)) {
                            XposedBridge.log("NickNameKiller: 开始持续检测 NICK_NAME");
                            startMonitoring();
                        }
                    }
                }
        );

        XposedBridge.log("NickNameKiller: Hook 已安装");
    }

    private void startMonitoring() {
        Thread monitorThread = new Thread(() -> {
            try {
                while (monitoring.get()) {
                    String currentNickName = readNickName();

                    if (currentNickName != null) {
                        if (!currentNickName.equals(lastNickName)) {
                            XposedBridge.log("NickNameKiller: NICK_NAME 变化: " + lastNickName + " -> " + currentNickName);
                            lastNickName = currentNickName;

                            if (TARGET_NICK_NAME.equals(currentNickName)) {
                                XposedBridge.log("NickNameKiller: 检测到 " + TARGET_NICK_NAME + "，踢出登录");
                                monitoring.set(false);
                                kickUser();
                                return;
                            } else {
                                XposedBridge.log("NickNameKiller: NICK_NAME 不是目标值，停止检测");
                                monitoring.set(false);
                                return;
                            }
                        }
                    }

                    Thread.sleep(CHECK_INTERVAL);
                }
            } catch (InterruptedException e) {
                XposedBridge.log("NickNameKiller: 检测线程被中断");
            } catch (Exception e) {
                XposedBridge.log("NickNameKiller: 检测异常: " + e.getMessage());
            }
        }, "NickNameKiller-Monitor");

        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private void kickUser() {
        try {
            File file = new File(PREFS_PATH);
            if (!file.exists()) {
                XposedBridge.log("NickNameKiller: 文件不存在");
                return;
            }

            // 读取原文件内容
            FileInputStream fis = new FileInputStream(file);
            InputStreamReader reader = new InputStreamReader(fis, "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int len;
            while ((len = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
            }
            reader.close();
            fis.close();

            String content = sb.toString();

            // 修改字段为非登录态
            content = content.replace(
                    "<boolean name=\"ISLOGIN\" value=\"true\" />",
                    "<boolean name=\"ISLOGIN\" value=\"false\" />"
            );
            content = replaceTagValue(content, "NICK_NAME", "");
            content = replaceTagValue(content, "AD_INITED", "");
            content = replaceTagValue(content, "ACCESS_TOKEN", "");
            content = replaceTagValue(content, "login_school_id", "");
            content = replaceTagValue(content, "TYPE", "");
            content = replaceTagValue(content, "SCHOOL_ID", "");
            content = replaceTagValue(content, "REAL_NAME", "");

            // 写回文件
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(content.getBytes("UTF-8"));
            fos.close();

            XposedBridge.log("NickNameKiller: 已踢出登录，回到桌面");

            // 回到桌面
            try {
                if (currentActivity != null) {
                    Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                    homeIntent.addCategory(Intent.CATEGORY_HOME);
                    homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    currentActivity.startActivity(homeIntent);
                }
            } catch (Exception e) {
                XposedBridge.log("NickNameKiller: 回到桌面失败: " + e.getMessage());
            }

            // 延迟杀掉进程
            Thread.sleep(500);
            Process.killProcess(Process.myPid());

        } catch (Exception e) {
            XposedBridge.log("NickNameKiller: 踢出登录失败: " + e.getMessage());
        }
    }

    private String replaceTagValue(String content, String tagName, String newValue) {
        String searchTag = "name=\"" + tagName + "\"";
        int index = content.indexOf(searchTag);
        if (index != -1) {
            int valueStart = content.indexOf(">", index) + 1;
            int valueEnd = content.indexOf("<", valueStart);
            if (valueStart > 0 && valueEnd > valueStart) {
                return content.substring(0, valueStart) + newValue + content.substring(valueEnd);
            }
        }
        return content;
    }

    private String readNickName() {
        try {
            File file = new File(PREFS_PATH);
            if (!file.exists()) {
                return null;
            }

            FileInputStream fis = new FileInputStream(file);
            InputStreamReader reader = new InputStreamReader(fis, "UTF-8");
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[1024];
            int len;
            while ((len = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
            }
            reader.close();
            fis.close();

            String content = sb.toString();

            String searchTag = "name=\"NICK_NAME\"";
            int index = content.indexOf(searchTag);
            if (index != -1) {
                int valueStart = content.indexOf(">", index) + 1;
                int valueEnd = content.indexOf("<", valueStart);
                if (valueStart > 0 && valueEnd > valueStart) {
                    return content.substring(valueStart, valueEnd).trim();
                }
            }

            return null;
        } catch (Exception e) {
            XposedBridge.log("NickNameKiller: 读取文件异常: " + e.getMessage());
            return null;
        }
    }
}
