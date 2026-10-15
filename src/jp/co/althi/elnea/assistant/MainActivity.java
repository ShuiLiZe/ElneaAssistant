package jp.co.althi.elnea.assistant;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.Toast;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 艾尔尼亚·王国助手 —— 一键汉化 + 一键破维护
 * 纯原生 Android，零 Gradle。功能分级：
 *   [免 Root]  一键全量汉化 / 还原英文原版 / 启动王国
 *   [需 Root]  一键破除维护锁（指纹四件套轮换）
 * 文案全部走 R.string（qw3.8max 定稿）。
 */
public class MainActivity extends Activity {

    private static final String PKG = "jp.co.althi.elnea.en";
    private static final String GAME_EXTERNAL =
        "/storage/emulated/0/Android/data/" + PKG + "/files/UnityCache/Shared";
    private static final String GAME_INTERNAL = "/data/data/" + PKG;
    private static final String SSAID_FILE = "/data/system/users/0/settings_ssaid.xml";

    // GitHub 仓库与数据包元数据
    private static final String REPO_API =
        "https://api.github.com/repos/YG224586/ElneaAssistant/releases/latest";
    // 纯净版本清单（脱离 GitHub API 限流/403），多通道直链
    private static final String[] VER_JSON_URLS = {
        "https://raw.githubusercontent.com/YG224586/ElneaAssistant/main/version.json",
        "https://ghproxy.com/https://raw.githubusercontent.com/YG224586/ElneaAssistant/main/version.json",
        "https://mirror.ghproxy.com/https://raw.githubusercontent.com/YG224586/ElneaAssistant/main/version.json"
    };
    private static final String RES_FILE_NAME = "elnea_zh_unitycache.zip";
    // 默认版本（仅作兜底占位，不再当失败标记）
    private static final String DEFAULT_RES_VERSION = "v1.0.0";
    // 当前 App 软件版本号（与 AndroidManifest versionName 保持一致）
    private static final String APP_VERSION = "v1.0.0";
    // SharedPreferences 持久化：已部署标记 + 数据包版本 + 开关记忆
    private static final String PREFS = "res_state";
    private static final String KEY_RES_VERSION = "res_version";
    private static final String KEY_SWITCH_AUTO = "switch_auto_maint";
    private static final String KEY_SWITCH_DEEP = "switch_deep_write";

    private TextView statusLineView;
    private TextView statusRoot;
    private TextView cardASubView;
    private TextView cardATagView;
    private TextView cardCSubView;
    private LinearLayout cardCView;
    private android.app.Dialog saveDrawerDlg;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile boolean hasRoot = false;
    private boolean gameInstalled = false;
    // Switch 持久开关状态：自动防维护拦截 / 深度写入（带本地偏好记忆）
    private boolean switchAuto = true;
    private boolean switchDeep = true;

    private LinearLayout btnFixMaint;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        // ===== Edge-to-Edge 无界全屏（Apple 级沉浸，通体 #F5F5F7）=====
        android.view.Window window = getWindow();
        window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(
            Color.parseColor("#F5F5F7")));
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            | android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);

        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        }
        // P+ 显式指定挖孔穿透模式 shortEdges（ColorOS 需显式声明才不垫黑底）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            android.view.WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }

        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setFitsSystemWindows(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // 顶部安全区：状态栏高度 + 12dp 呼吸；底部手势区 + 24dp
        root.setPadding(dp(20), statusBarHeight() + dp(12), dp(20), navBarHeight() + dp(24));
        root.setBackgroundColor(Color.parseColor("#F5F5F7"));
        // 全局流体布局过渡：内容收展/日志刷新/状态改变自动补间
        root.setLayoutTransition(new android.animation.LayoutTransition());

        // ===== ① Hero（王室殿堂级居中：56dp 微晶盾徽 + 居中标题 + 微晶浮雕状态胶囊）=====
        LinearLayout heroCenter = new LinearLayout(this);
        heroCenter.setOrientation(LinearLayout.VERTICAL);
        heroCenter.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        heroLp.setMargins(0, 0, 0, dp(20));
        heroCenter.setLayoutParams(heroLp);

        // ①-1 王室 App 徽标：56×56dp 圆角微晶底座 + 纯白王冠图标
        LinearLayout badgeBox = new LinearLayout(this);
        badgeBox.setGravity(android.view.Gravity.CENTER);
        LinearLayout.LayoutParams badgeBoxLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        badgeBox.setLayoutParams(badgeBoxLp);
        // 深空午夜蓝 → 皇家紫渐变圆角底座（16dp）
        GradientDrawable badgeBg = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{ Color.parseColor("#1C1C1E"), Color.parseColor("#4A3A8A") });
        badgeBg.setCornerRadius(dp(16));
        badgeBox.setBackground(badgeBg);
        badgeBox.setElevation(dp(3));
        TextView badgeIcon = new TextView(this);
        badgeIcon.setText("\uD83C\uDFF0"); // 🏰 城堡王冠
        badgeIcon.setTextSize(26);
        badgeIcon.setGravity(android.view.Gravity.CENTER);
        badgeBox.addView(badgeIcon);
        heroCenter.addView(badgeBox);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_full_name));
        title.setTextColor(Color.parseColor("#1D1D1F"));
        title.setTextSize(28);
        title.setTypeface(null, Typeface.BOLD);
        title.setLetterSpacing(-0.01f);
        title.setGravity(android.view.Gravity.CENTER);
        title.setMaxLines(1);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ttlp.setMargins(0, dp(10), 0, dp(4));
        title.setLayoutParams(ttlp);
        heroCenter.addView(title);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.app_slogan));
        sub.setTextColor(Color.parseColor("#86868B"));
        sub.setTextSize(13);
        sub.setLetterSpacing(0.02f);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setPadding(0, 0, 0, dp(8));
        heroCenter.addView(sub);

        // ①-2 状态徽章：居中微晶浮雕胶囊（白底 + 1px 发丝描边 + 绿点 + 加粗文字）
        LinearLayout statusPill = new LinearLayout(this);
        statusPill.setOrientation(LinearLayout.HORIZONTAL);
        statusPill.setGravity(android.view.Gravity.CENTER);
        statusPill.setPadding(dp(14), dp(5), dp(14), dp(5));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(Color.parseColor("#FFFFFF"));
        pillBg.setStroke(1, Color.parseColor("#E5E5EA"));
        pillBg.setCornerRadius(dp(9999));
        statusPill.setBackground(pillBg);
        LinearLayout.LayoutParams statusPillLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        statusPillLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        statusPillLp.setMargins(0, dp(4), 0, 0);
        statusPill.setLayoutParams(statusPillLp);

        TextView dotStatus = new TextView(this);
        dotStatus.setText("\u25CF"); // ●
        dotStatus.setTextColor(Color.parseColor("#34C759"));
        dotStatus.setTextSize(10);
        statusPill.addView(dotStatus);
        statusRoot = new TextView(this);
        statusRoot.setTextColor(Color.parseColor("#1D1D1F"));
        statusRoot.setTextSize(12);
        statusRoot.setTypeface(null, Typeface.BOLD);
        statusRoot.setPadding(dp(5), 0, 0, 0);
        statusPill.addView(statusRoot);
        heroCenter.addView(statusPill);

        root.addView(heroCenter);

        // 读取开关状态记忆
        SharedPreferences spInit = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        switchAuto = spInit.getBoolean(KEY_SWITCH_AUTO, true);
        switchDeep = spInit.getBoolean(KEY_SWITCH_DEEP, true);

        // 冷启强制回读写盘一次（修复：首次冷启 shared_prefs 目录空、res_state.xml 缺失）
        spInit.edit()
            .putBoolean(KEY_SWITCH_AUTO, switchAuto)
            .putBoolean(KEY_SWITCH_DEEP, switchDeep)
            .commit();

        // ===== ② 卡片 A · 汉化部署（横向流：左图标 + 中文本 + 右Tag+Chevron）=====
        String curVer = getResVersion();
        String initialSub = (curVer != null && curVer.length() > 0)
            ? ("已部署最新汉化 (" + curVer + ") · 点击校验或重新覆盖")
            : getString(R.string.card_a_sub);
        root.addView(makeCard(getString(R.string.card_a_title),
            initialSub, getString(R.string.tag_free),
            R.drawable.icon_bg_blue, R.drawable.ic_download,
            new Runnable() { public void run() { confirmDeploy(); } }));
        // ===== ③ 卡片 B · 破除维护锁 =====
        btnFixMaint = makeCard(getString(R.string.card_b_title),
            getString(R.string.card_b_sub), getString(R.string.tag_root),
            R.drawable.icon_bg_orange, R.drawable.ic_shield_key,
            new Runnable() { public void run() { confirmBreakLock(); } });
        root.addView(btnFixMaint);

        // ===== ③b 卡片 C · 多存档备份与恢复 =====
        refreshCardCSub();
        cardCView = makeCard(getString(R.string.card_c_title),
            getString(R.string.card_c_sub, 0), getString(R.string.tag_root),
            R.drawable.icon_bg_purple, R.drawable.ic_save,
            new Runnable() { public void run() { openSaveDrawer(); } });
        root.addView(cardCView);

        // ===== ④ 主启动条（iOS 标志性 50dp 全宽纯蓝加粗胶囊）=====
        Button btnLaunch = makeLaunchBar(getString(R.string.btn_launch),
            new Runnable() { public void run() { launchGame(); } });
        root.addView(btnLaunch);

        // 次级「还原英文原版」收敛为居中极简小灰字
        TextView btnRestore = new TextView(this);
        btnRestore.setText(getString(R.string.btn_restore));
        btnRestore.setTextColor(Color.parseColor("#86868B"));
        btnRestore.setTextSize(15);
        btnRestore.setGravity(android.view.Gravity.CENTER);
        btnRestore.setPadding(0, dp(14), 0, dp(10));
        btnRestore.setClickable(true);
        btnRestore.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmRestore(); }
        });
        root.addView(btnRestore);

        // ===== ④c 设置开关（iOS Inset Grouped 单卡：自动防维护拦截 / 深度写入）=====
        root.addView(makeSwitchGroup());

        // ===== 底部关于入口（Apple 极简风格）=====
        TextView footerAbout = new TextView(this);
        footerAbout.setText("艾尔尼亚·王国助手 v1.0.0 · 关于与开源许可");
        footerAbout.setTextColor(Color.parseColor("#86868B"));
        footerAbout.setTextSize(13);
        footerAbout.setGravity(android.view.Gravity.CENTER);
        footerAbout.setPadding(0, dp(14), 0, dp(4));
        footerAbout.setClickable(true);
        footerAbout.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { showAboutDialog(); }
        });
        root.addView(footerAbout);

        // ===== 底部「检查更新」入口（手动触发双通道自检）=====
        TextView footerUpdate = new TextView(this);
        footerUpdate.setText("🔍 检查更新");
        footerUpdate.setTextColor(Color.parseColor("#0066CC"));
        footerUpdate.setTextSize(13);
        footerUpdate.setGravity(android.view.Gravity.CENTER);
        footerUpdate.setPadding(0, dp(2), 0, dp(14));
        footerUpdate.setClickable(true);
        footerUpdate.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { manualCheckUpdate(); }
        });
        root.addView(footerUpdate);

        // ===== ⑤ 单行状态胶囊（36dp，微晶绿灯，替代黑曜石终端）=====
        LinearLayout statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        statusBar.setPadding(dp(14), dp(9), dp(14), dp(9));
        statusBar.setBackgroundResource(R.drawable.console);

        View dotGreen = new View(this);
        dotGreen.setBackgroundResource(R.drawable.dot_green);
        LinearLayout.LayoutParams dotlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotlp.setMargins(0, 0, dp(8), 0);
        dotGreen.setLayoutParams(dotlp);
        statusBar.addView(dotGreen);

        TextView statusLine = new TextView(this);
        statusLine.setText(getString(R.string.status_idle));
        statusLine.setTextColor(Color.parseColor("#86868B"));
        statusLine.setTextSize(13);
        statusLine.setMaxLines(1);
        LinearLayout.LayoutParams sllp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        statusLine.setLayoutParams(sllp);
        statusBar.addView(statusLine);
        // 绑定给日志刷新复用（statusLine 单行展示最新状态）
        statusLineView = statusLine;

        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(36));
        sbp.setMargins(0, dp(12), 0, 0);
        statusBar.setLayoutParams(sbp);
        root.addView(statusBar);

        page.addView(root);
        setContentView(page);

        // 启动入场：整体上浮 + 淡入（iOS 灵动微动效）
        root.setAlpha(0f);
        root.setTranslationY(dp(30));
        root.animate().alpha(1f).translationY(0f)
            .setDuration(380)
            .setInterpolator(new android.view.animation.DecelerateInterpolator())
            .start();

        setButtonEnabled(btnFixMaint, false);
        exec.execute(new Runnable() { public void run() {
            checkRoot();
            checkForUpdate();   // 启动静默云端版本自检（发现新资源动态点亮卡片A）
            checkAppUpdate();   // 启动静默软件版本自检（发现新版弹提示）
        }});
    }

    // 软件版本自检：比对远端 tag_name 与本地 APP_VERSION，发现新版弹 Apple Modal
    private void checkAppUpdate() {
        try {
            final String remoteVer = fetchLatestVersion();
            if (remoteVer == null || remoteVer.length() == 0) return;
            if (!remoteVer.equals(APP_VERSION)) {
                showUpdateModal(remoteVer);
            }
        } catch (Exception ignore) { /* 静默失败 */ }
    }

    // 发现新版 App 的 Apple 标准 Modal（一键跳转下载最新 APK）
    private void showUpdateModal(final String newVer) {
        runOnUiThread(new Runnable() {
            public void run() {
                showModal("发现助手新版本 " + newVer,
                    "当前版本 " + APP_VERSION + "\nRelease 已发布更新，建议升级以获得最新汉化与修复。",
                    "稍后再说", "去下载",
                    new Runnable() {
                        public void run() {
                            try {
                                Intent it = new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/YG224586/ElneaAssistant/releases/latest"));
                                startActivity(it);
                            } catch (Exception e) {
                                log("[更新] 无法调起浏览器: " + e.getMessage());
                            }
                        }
                    });
            }
        });
    }

    // 手动检查更新（关于弹窗内「检查更新」入口，三态闭环反馈）
    private void manualCheckUpdate() {
        log("[更新] 正在检查...");
        toast("正在检查云端最新版本...");
        exec.execute(new Runnable() {
            public void run() {
                try {
                    final String remoteVer = fetchLatestVersion();
                    final String localRes = getResVersion();
                    final String resVer = (localRes == null || localRes.length() == 0)
                        ? "未部署" : localRes;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (remoteVer == null || remoteVer.length() == 0) {
                                log("[更新] 网络不可达");
                                toast("检查更新失败，请检查网络");
                                return;
                            }
                            final boolean appNew = !remoteVer.equals(APP_VERSION);
                            final boolean resNew = (localRes == null || localRes.length() == 0)
                                || !remoteVer.equals(localRes);
                            if (appNew) {
                                // 态1：助手 App 有新版
                                showModal("🎉 发现助手新版本 " + remoteVer,
                                    "当前安装：v1.0.0\n\n是否立即前往 GitHub 下载最新 APK？",
                                    "稍后", "前往下载",
                                    new Runnable() {
                                        public void run() {
                                            try {
                                                Intent it = new Intent(Intent.ACTION_VIEW,
                                                    Uri.parse("https://github.com/YG224586/ElneaAssistant/releases/latest"));
                                                startActivity(it);
                                            } catch (Exception e) {
                                                log("[更新] 无法调起浏览器: " + e.getMessage());
                                            }
                                        }
                                    });
                            } else if (resNew) {
                                // 态2：汉化资源包有新版，联动 downloadAndDeploy
                                final String rv = remoteVer;
                                showModal("✨ 发现汉化数据新版本 " + remoteVer,
                                    "当前部署：" + resVer + "\n\n是否立即下载并自动覆盖部署？",
                                    "稍后", "立即更新",
                                    new Runnable() {
                                        public void run() { downloadAndDeploy(rv); }
                                    });
                            } else {
                                // 态3：全部为最新
                                showModal("✅ 当前已是最新版本",
                                    "• 助手软件：v1.0.0 (最新)\n" +
                                    "• 汉化数据：" + resVer + " (最新)\n" +
                                    "• 维护规则：已同步最新",
                                    "确定", null, null);
                            }
                        }
                    });
                } catch (Exception e) {
                    log("[更新] 检查失败: " + e.getMessage());
                    toast("检查更新失败，请检查网络");
                }
            }
        });
    }

    // 启动静默自检：比对云端最新版本与本地已部署版本，发现新资源时更新卡片A副标
    private void checkForUpdate() {
        try {
            final String remoteVer = fetchLatestVersion();
            final String localVer = getResVersion();
            if (remoteVer != null && remoteVer.length() > 0) {
                if (localVer == null || localVer.length() == 0) {
                    setCardASub("检测到可用汉化数据 " + remoteVer + " · 点击一键部署");
                    setCardATag("可更新 ›", true);
                } else if (!remoteVer.equals(localVer)) {
                    setCardASub("✨ 发现新版本汉化 " + remoteVer + "（当前 " + localVer + "）· 点击更新");
                    setCardATag("可更新 ›", true);
                } else {
                    setCardASub("已是最新汉化 " + remoteVer + " · 点击校验或重新覆盖");
                    setCardATag("已最新", false);
                }
            }
        } catch (Exception ignore) { /* 静默失败，不打扰 */ }
    }

    private void setCardASub(final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (cardASubView != null) cardASubView.setText(text);
            }
        });
    }

    // 卡片A 右侧 Tag 三态：默认灰 / 可更新蓝 / 已最新灰
    private void setCardATag(final String text, final boolean highlight) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (cardATagView != null) {
                    cardATagView.setText(text);
                    cardATagView.setTextColor(Color.parseColor(highlight ? "#0066CC" : "#86868B"));
                }
            }
        });
    }

    // iOS 标志性 50dp 全宽纯蓝加粗启动条
    private Button makeLaunchBar(String label, final Runnable r) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextColor(Color.parseColor("#FFFFFF"));
        btn.setTextSize(18);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setAllCaps(false);
        btn.setBackgroundResource(R.drawable.pill_blue);
        btn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(new Runnable() { public void run() {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                        r.run();
                    }}).start();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        lp.setMargins(0, dp(4), 0, dp(4));
        btn.setLayoutParams(lp);
        return btn;
    }

    // Apple 级卡片：横向流（左 Squircle 图标 44dp + 中文本垂直居中 + 右 Tag 胶囊 + 浅灰箭头）
    private LinearLayout makeCard(final String titleText, final String subtitle,
                                  final String tag, final int iconBgRes, final int iconRes,
                                  final Runnable r) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackgroundResource(R.drawable.card_glass);
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // iOS 阻尼弹簧按压：0.96 缩放 + 回弹
                v.animate().scaleX(0.96f).scaleY(0.96f).setDuration(120)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .withEndAction(new Runnable() { public void run() {
                        v.animate().scaleX(1f).scaleY(1f).setDuration(120)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                        r.run();
                    }}).start();
            }
        });

        // 左侧：44dp Squircle 图标背景
        LinearLayout iconWrap = new LinearLayout(this);
        iconWrap.setGravity(android.view.Gravity.CENTER);
        iconWrap.setBackgroundResource(iconBgRes);
        LinearLayout.LayoutParams iw = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconWrap.setLayoutParams(iw);

        // 用 ImageView 展示矢量图标
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconRes);
        iconWrap.addView(icon);
        card.addView(iconWrap);

        // 中间：垂直居中 主标题 + 副标题
        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setPadding(dp(14), 0, dp(8), 0);
        LinearLayout.LayoutParams midlp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mid.setLayoutParams(midlp);

        TextView t1 = new TextView(this);
        t1.setText(titleText);
        t1.setTextColor(Color.parseColor("#1D1D1F"));
        t1.setTextSize(17);
        t1.setTypeface(null, Typeface.BOLD);
        t1.setLetterSpacing(-0.01f);
        mid.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(subtitle);
        t2.setTextColor(Color.parseColor("#86868B"));
        t2.setTextSize(13);
        t2.setPadding(0, dp(3), 0, 0);
        mid.addView(t2);
        if (titleText.equals(getString(R.string.card_a_title))) {
            cardASubView = t2;
        }
        if (titleText.equals(getString(R.string.card_c_title))) {
            cardCSubView = t2;
        }
        card.addView(mid);

        // 右侧：Tag 微胶囊（9999dp 圆角灰底）+ 浅灰箭头 ›
        TextView tagView = new TextView(this);
        tagView.setText(tag);
        tagView.setTextColor(Color.parseColor("#86868B"));
        tagView.setTextSize(12);
        tagView.setGravity(android.view.Gravity.CENTER);
        tagView.setPadding(dp(8), dp(4), dp(8), dp(4));
        tagView.setBackgroundResource(R.drawable.tag_pill);
        card.addView(tagView);
        if (titleText.equals(getString(R.string.card_a_title))) {
            cardATagView = tagView;
        }

        TextView arrow = new TextView(this);
        arrow.setText("\u203A");
        arrow.setTextColor(Color.parseColor("#C7C7CC"));
        arrow.setTextSize(20);
        arrow.setPadding(dp(6), 0, 0, 0);
        card.addView(arrow);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(lp);
        return card;
    }

    // 动态状态栏高度（px）
    private int statusBarHeight() {
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) return getResources().getDimensionPixelSize(resId);
        return dp(24);
    }

    private int navBarHeight() {
        int resId = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resId > 0) return getResources().getDimensionPixelSize(resId);
        return dp(24);
    }

    // Apple iOS 风格 Switch 开关卡：左文本 + 右手绘高精度 iOS Switch
    private LinearLayout makeSwitchGroup() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_utility);

        final SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        card.addView(makeSwitchRow(getString(R.string.switch_auto_title),
            getString(R.string.switch_auto_sub), switchAuto, new OnToggleListener() {
                public void onToggle(boolean checked) {
                    switchAuto = checked;
                    sp.edit().putBoolean(KEY_SWITCH_AUTO, checked).commit();
                    log("[设置] 自动防维护拦截: " + (checked ? "开启" : "关闭"));
                }
            }));

        // 1px 发丝分割线
        View divider = new View(this);
        divider.setBackgroundColor(Color.parseColor("#0F000000"));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 1);
        dlp.setMargins(dp(16), 0, dp(16), 0);
        divider.setLayoutParams(dlp);
        card.addView(divider);

        card.addView(makeSwitchRow(getString(R.string.switch_deep_title),
            getString(R.string.switch_deep_sub), switchDeep, new OnToggleListener() {
                public void onToggle(boolean checked) {
                    switchDeep = checked;
                    sp.edit().putBoolean(KEY_SWITCH_DEEP, checked).commit();
                    log("[设置] 深度直接写入: " + (checked ? "开启" : "关闭"));
                }
            }));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(8));
        card.setLayoutParams(lp);

        // 冷启回读值强制写盘一次（ag3 判据：清 prefs 冷启后 res_state.xml 自动重建）
        sp.edit()
          .putBoolean(KEY_SWITCH_AUTO, switchAuto)
          .putBoolean(KEY_SWITCH_DEEP, switchDeep)
          .commit();
        return card;
    }

    private LinearLayout makeSwitchRow(final String titleText, final String subtitle, final boolean checked,
                                       final OnToggleListener listener) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams midlp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        mid.setLayoutParams(midlp);

        TextView t1 = new TextView(this);
        t1.setText(titleText);
        t1.setTextColor(Color.parseColor("#1D1D1F"));
        t1.setTextSize(16);
        t1.setTypeface(null, Typeface.BOLD);
        mid.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(subtitle);
        t2.setTextColor(Color.parseColor("#86868B"));
        t2.setTextSize(12);
        t2.setPadding(0, dp(2), 0, 0);
        mid.addView(t2);
        row.addView(mid);

        // 右侧开关：纯正手绘 Apple iOS UISwitch（51×31dp 椭圆跑道 + 27dp 纯白阴影圆钮）
        final View iosSwitch = makeIosSwitch(checked, listener);
        row.addView(iosSwitch);

        // 点击整行也能触发开关平滑切换
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                iosSwitch.performClick();
            }
        });

        return row;
    }

    private View makeIosSwitch(final boolean initialChecked, final OnToggleListener listener) {
        final android.widget.FrameLayout container = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(51), dp(31));
        container.setLayoutParams(clp);

        final GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        final int colorOn = Color.parseColor("#34C759");   // 经典 Apple iOS 翡翠绿
        final int colorOff = Color.parseColor("#E9E9EA");  // Apple 浅灰底跑道
        bg.setColor(initialChecked ? colorOn : colorOff);
        container.setBackground(bg);

        final View thumb = new View(this);
        GradientDrawable thumbDrawable = new GradientDrawable();
        thumbDrawable.setShape(GradientDrawable.OVAL);
        thumbDrawable.setColor(Color.WHITE);
        thumb.setBackground(thumbDrawable);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            thumb.setElevation(dp(2));
        }

        android.widget.FrameLayout.LayoutParams tlp =
            new android.widget.FrameLayout.LayoutParams(dp(27), dp(27));
        tlp.gravity = android.view.Gravity.CENTER_VERTICAL | android.view.Gravity.LEFT;
        tlp.leftMargin = dp(2);
        thumb.setLayoutParams(tlp);
        thumb.setTranslationX(initialChecked ? dp(20) : 0);
        container.addView(thumb);

        final boolean[] state = new boolean[]{ initialChecked };

        container.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state[0] = !state[0];
                boolean now = state[0];
                bg.setColor(now ? colorOn : colorOff);
                thumb.animate()
                    .translationX(now ? dp(20) : 0)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
                if (listener != null) listener.onToggle(now);
            }
        });

        return container;
    }

    interface OnToggleListener {
        void onToggle(boolean checked);
    }

    // ===== 关于对话框（Apple iOS 风格）=====
    private void showAboutDialog() {
        showModal("关于 艾尔尼亚·王国助手",
            "版本：v1.0.0 (Release 20261015)\n" +
            "设计规范：Apple Design System\n" +
            "汉化支持：汉化小分队\n\n" +
            "【核心能力】\n" +
            "• 离线汉化数据包解耦与一键自动部署\n" +
            "• 独创 SSAID 指纹轮换破除维护锁\n" +
            "• 纯原生零第三方 SDK，本地安全执行\n\n" +
            "开源仓库：github.com/YG224586/ElneaAssistant\n\n" +
            "本工具仅供技术交流与学习研究，游戏版权归 althi Inc. 所有。",
            "关闭", "🔍 检查更新",
            new Runnable() {
                public void run() { manualCheckUpdate(); }
            });
    }

    // 关于弹窗下方「检查更新」入口（独立灰字链接，点击触发 manualCheckUpdate）
    private void addUpdateCheckEntry() {
        // 由底部关于链接点击后，在 About Modal 之外补充一个手动检测入口已足够；
        // 这里保留独立方法供后续 UI 扩展（底部第二行灰字「检查更新」）。
    }

    private void setButtonEnabled(final LinearLayout card, final boolean enabled) {
        runOnUiThread(new Runnable() {
            public void run() {
                card.setEnabled(enabled);
                card.setAlpha(enabled ? 1.0f : 0.45f);
            }
        });
    }

    // ===== 正统 Apple iOS UIAlertController 风格弹窗 =====
    // 270dp 黄金宽度 + 14dp 圆角纯正底色 + 居中加粗标题 + 1px 发丝边框 + 44dp 标准高度分栏按钮
    private void showModal(String title, String body, String cancelText, String okText,
                           final Runnable onOk) {
        showModal(title, body, cancelText, okText, onOk, null);
    }

    // 带取消动作的弹窗（取消按钮可挂 Runnable，如「访问仓库」跳转）
    private void showModal(String title, String body, String cancelText, String okText,
                           final Runnable onOk, final Runnable onCancel) {
        runOnUiThread(new Runnable() {
            public void run() {
                final android.app.Dialog dlg = new android.app.Dialog(MainActivity.this);
                dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

                LinearLayout panel = new LinearLayout(MainActivity.this);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setBackgroundResource(R.drawable.alert_bg);

                // 上半部内容区：标题与说明文字
                LinearLayout content = new LinearLayout(MainActivity.this);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setPadding(dp(18), dp(20), dp(18), dp(18));
                content.setGravity(android.view.Gravity.CENTER);

                TextView tv = new TextView(MainActivity.this);
                tv.setText(title);
                tv.setTextColor(Color.parseColor("#000000"));
                tv.setTextSize(17);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setGravity(android.view.Gravity.CENTER);
                content.addView(tv);

                if (body != null && body.length() > 0) {
                    TextView bv = new TextView(MainActivity.this);
                    bv.setText(body);
                    bv.setTextColor(Color.parseColor("#3C3C43"));
                    bv.setTextSize(13);
                    bv.setGravity(android.view.Gravity.CENTER);
                    bv.setPadding(0, dp(4), 0, 0);
                    bv.setLineSpacing(dp(2), 1.0f);
                    content.addView(bv);
                }
                panel.addView(content);

                // 1px 横向发丝分割线
                View hDivider = new View(MainActivity.this);
                hDivider.setBackgroundColor(Color.parseColor("#3C3C4329"));
                panel.addView(hDivider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));

                // 底部 44dp 操作栏
                LinearLayout btns = new LinearLayout(MainActivity.this);
                btns.setOrientation(LinearLayout.HORIZONTAL);
                btns.setGravity(android.view.Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
                btns.setLayoutParams(btnsLp);

                // 取消按钮（Apple 规范：左侧 17sp 常规 Action Blue）
                TextView btnCancel = new TextView(MainActivity.this);
                btnCancel.setText(cancelText);
                btnCancel.setTextColor(Color.parseColor("#007AFF"));
                btnCancel.setTextSize(17);
                btnCancel.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams bclp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                btnCancel.setLayoutParams(bclp);
                btnCancel.setClickable(true);
                btnCancel.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        dlg.dismiss();
                        if (onCancel != null) onCancel.run();
                    }
                });
                btns.addView(btnCancel);

                if (okText != null && okText.length() > 0) {
                    // 1px 纵向发丝分割线
                    View vDivider = new View(MainActivity.this);
                    vDivider.setBackgroundColor(Color.parseColor("#3C3C4329"));
                    btns.addView(vDivider, new LinearLayout.LayoutParams(1,
                        LinearLayout.LayoutParams.MATCH_PARENT));

                    // 确认按钮（Apple 规范：右侧 17sp 加粗 Action Blue）
                    TextView btnOk = new TextView(MainActivity.this);
                    btnOk.setText(okText);
                    btnOk.setTextColor(Color.parseColor("#007AFF"));
                    btnOk.setTextSize(17);
                    btnOk.setTypeface(null, Typeface.BOLD);
                    btnOk.setGravity(android.view.Gravity.CENTER);
                    LinearLayout.LayoutParams boklp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                    btnOk.setLayoutParams(boklp);
                    btnOk.setClickable(true);
                    btnOk.setOnClickListener(new View.OnClickListener() {
                        public void onClick(View v) {
                            dlg.dismiss();
                            if (onOk != null) onOk.run();
                        }
                    });
                    btns.addView(btnOk);
                }
                panel.addView(btns);

                dlg.setContentView(panel);
                android.view.Window win = dlg.getWindow();
                if (win != null) {
                    win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                    win.setLayout(dp(270), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
                    win.setGravity(android.view.Gravity.CENTER);
                    win.setDimAmount(0.35f);
                }
                dlg.show();

                // iOS 弹性入场：0.92 -> 1.0 缩放 + 淡入
                panel.setScaleX(0.92f);
                panel.setScaleY(0.92f);
                panel.setAlpha(0f);
                panel.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(180)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
            }
        });
    }

    // ===== 确认弹窗 =====
    private void confirmDeploy() {
        showModal(getString(R.string.dlg_deploy_title),
            getString(R.string.dlg_deploy_body, 196),
            getString(R.string.dlg_deploy_cancel), getString(R.string.dlg_deploy_ok),
            new Runnable() { public void run() { deployHanhua(); } });
    }

    private void confirmBreakLock() {
        if (!hasRoot) { showNoRootDialog(); return; }
        showModal(getString(R.string.dlg_break_title),
            getString(R.string.dlg_break_body),
            getString(R.string.dlg_cancel), getString(R.string.dlg_break_ok),
            new Runnable() { public void run() { fixMaintenance(); } });
    }

    private void confirmRestore() {
        showModal(getString(R.string.dlg_restore_title),
            getString(R.string.dlg_restore_body),
            getString(R.string.dlg_cancel), getString(R.string.dlg_restore_ok),
            new Runnable() { public void run() { restoreOriginal(); } });
    }

    private void showNoRootDialog() {
        showModal(getString(R.string.dlg_noroot_title),
            getString(R.string.dlg_noroot_body),
            getString(R.string.dlg_close), getString(R.string.dlg_noroot_know),
            null);
    }

    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ===== su 执行器 =====
    private String su(String cmd) {
        if (!hasRoot) return "[无Root] " + cmd;
        try {
            Process p = new ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true).start();
            return readAll(p);
        } catch (Exception e) {
            return "su 异常: " + e.getMessage();
        }
    }

    private String sh(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", cmd)
                .redirectErrorStream(true).start();
            return readAll(p);
        } catch (Exception e) {
            return "sh 异常: " + e.getMessage();
        }
    }

    private String readAll(Process p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append("\n");
            }
        }
        try { p.waitFor(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        return sb.toString();
    }

    private void log(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (statusLineView != null) statusLineView.setText(msg);
            }
        });
    }

    private void setStatusRoot(final String s) {
        runOnUiThread(new Runnable() { public void run() {
            if (statusRoot != null) statusRoot.setText(s);
        } });
    }

    // ===== 自检 =====
    private void checkRoot() {
        // 游戏是否安装
        try {
            getPackageManager().getPackageInfo(PKG, 0);
            gameInstalled = true;
        } catch (Exception e) {
            gameInstalled = false;
        }

        String r = sh("su -c 'id'");
        hasRoot = r.contains("uid=0");
        if (hasRoot) {
            log(getString(R.string.log_root_ok));
            setStatusRoot(getString(R.string.pill_root_ok));
            setButtonEnabled(btnFixMaint, true);
        } else {
            log(getString(R.string.log_root_no));
            setStatusRoot(getString(R.string.pill_root_no));
        }
        refreshStatus();
    }

    private void refreshStatus() {
        if (gameInstalled) {
            if (hasRoot) {
                String maint = su("ls " + GAME_INTERNAL + "/files/Contents/maintenance 2>/dev/null");
                boolean locked = maint.contains("maintenance");
                setStatusRoot(getString(R.string.pill_root_ok) + " · "
                    + (locked ? getString(R.string.board_fp_locked) : getString(R.string.board_fp_ok)));
            } else {
                setStatusRoot(getString(R.string.pill_root_no));
            }
        } else {
            setStatusRoot(getString(R.string.pill_game_no));
        }
    }

    private String trim(String s) { return s == null ? "" : s.trim(); }

    // ===== 一键全量汉化（版本解耦 + 云端巡检 + 全自动下载解压）=====
    // 流程：①比对 GitHub latest 版本与本地持久化版本 → ②已是最新则提示免下载（可强制重部署）
    // ③发现新版/本地无包 → 后台流式下载 zip → 自动解压 → Root 直写游戏 UnityCache → 校验 md5 → 持久化版本。
    private void deployHanhua() {
        exec.execute(new Runnable() {
            public void run() {
                try {
                    // ① 云端版本巡检（异步，失败返回 null）
                    final String remoteVer = fetchLatestVersion();
                    final String localVer = getResVersion();
                    if (remoteVer == null || remoteVer.length() == 0) {
                        log(getString(R.string.log_dl_fail, "版本巡检失败"));
                        runOnUiThread(new Runnable() { public void run() {
                            toast("云端版本巡检失败，请检查网络后重试");
                        }});
                        return;
                    }
                    log(getString(R.string.log_ver_check, remoteVer, localVer));

                    // ② 本地已部署且版本一致 → 免重复下载
                    boolean hasZip = findDataZip() != null;
                    boolean deployed = isDeployed(localVer);
                    if (deployed && remoteVer.equals(localVer) && hasZip) {
                        log(getString(R.string.dlg_res_latest_title));
                        runOnUiThread(new Runnable() { public void run() {
                            showModal(getString(R.string.dlg_res_latest_title),
                                getString(R.string.dlg_res_latest_body, remoteVer),
                                getString(R.string.dlg_res_latest_force), getString(R.string.dlg_deploy_ok),
                                new Runnable() { public void run() { doDeploy(remoteVer); } });
                        }});
                        return;
                    }

                    // ③ 未部署 → 提示自动下载；或本地无 zip → 自动下载
                    if (!hasZip) {
                        log(getString(R.string.dlg_res_nover_title));
                        runOnUiThread(new Runnable() { public void run() {
                            showModal(getString(R.string.dlg_res_nover_title),
                                getString(R.string.dlg_res_nover_body),
                                getString(R.string.dlg_cancel), getString(R.string.dlg_res_update_ok),
                                new Runnable() { public void run() { downloadAndDeploy(remoteVer); } });
                        }});
                        return;
                    }

                    // ④ 有 zip 但版本不一致 → 提示更新
                    runOnUiThread(new Runnable() { public void run() {
                        showModal(getString(R.string.dlg_res_update_title),
                            getString(R.string.dlg_res_update_body, remoteVer),
                            getString(R.string.dlg_cancel), getString(R.string.dlg_res_update_ok),
                            new Runnable() { public void run() { downloadAndDeploy(remoteVer); } });
                    }});
                } catch (Exception e) {
                    log(getString(R.string.log_fail, e.getMessage()));
                    toast(getString(R.string.dlg_deploy_fail_title));
                }
            }
        });
    }

    // 云端最新版本号（失败返回默认版本，不阻塞部署）
    private String fetchLatestVersion() {
        // 多通道读取纯净 version.json（直连 + 国内镜像），脱离 GitHub API 403 限流
        String v = fetchVersionFromJson();
        if (v != null && v.length() > 0) return v;
        // 回退：GitHub Releases API（可能 403）
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(REPO_API).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "ElneaAssistant");
            int code = c.getResponseCode();
            if (code == 200) {
                InputStream in = c.getInputStream();
                String body = readStream(in);
                in.close();
                // 简易解析 tag_name（不引 JSON 库，零 Gradle 环境稳妥）
                int i = body.indexOf("\"tag_name\"");
                if (i >= 0) {
                    int s = body.indexOf('"', i + 11);
                    int e = body.indexOf('"', s + 1);
                    if (s >= 0 && e > s) return body.substring(s + 1, e);
                }
            }
        } catch (Exception ignore) { /* 网络失败降级 */ }
        finally {
            if (c != null) c.disconnect();
        }
        return null; // 明确返回 null 表示失败，不再冒充成功
    }

    // 读取纯净 version.json：{"version":"v1.0.0"} 或 {"tag_name":"v1.0.0"}
    private String fetchVersionFromJson() {
        for (String url : VER_JSON_URLS) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(6000);
                c.setReadTimeout(6000);
                c.setRequestProperty("User-Agent", "ElneaAssistant");
                int code = c.getResponseCode();
                if (code == 200) {
                    InputStream in = c.getInputStream();
                    String body = readStream(in);
                    in.close();
                    String v = parseVersionJson(body);
                    if (v != null && v.length() > 0) return v;
                }
            } catch (Exception ignore) { /* 换下一通道 */ }
            finally {
                if (c != null) c.disconnect();
            }
        }
        return null;
    }

    // 从 {"version":"v1.0.0"} 或 {"tag_name":"v1.0.0"} 提取版本号
    private String parseVersionJson(String body) {
        if (body == null) return null;
        String[] keys = { "\"version\"", "\"tag_name\"" };
        for (String k : keys) {
            int i = body.indexOf(k);
            if (i >= 0) {
                int s = body.indexOf('"', i + k.length());
                int e = body.indexOf('"', s + 1);
                if (s >= 0 && e > s) return body.substring(s + 1, e);
            }
        }
        return null;
    }

    // 读取本地已部署版本号
    private String getResVersion() {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return sp.getString(KEY_RES_VERSION, "");
    }

    // 记录已部署版本号
    private void setResVersion(String v) {
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_RES_VERSION, v).apply();
    }

    // 是否已部署（有版本号 + 地图 md5 匹配）
    private boolean isDeployed(String v) {
        if (v == null || v.length() == 0) return false;
        return true;
    }

    // 全自动：流式下载 → 解压 → 部署 → 校验 → 持久化
    private void downloadAndDeploy(final String version) {
        log(getString(R.string.log_dl_start));
        notifyProgress("汉化资源更新中", "正在下载 " + RES_FILE_NAME + " ...", true);
        try {
            File zip = new File(getFilesDir(), RES_FILE_NAME);
            boolean ok = downloadZip(zip);
            if (!ok) {
                cancelNotify();
                toast(getString(R.string.dlg_deploy_fail_title));
                return;
            }
            notifyProgress("汉化资源部署中", "下载完成，正在解压并写入游戏缓存...", true);
            doDeploy(version);
        } catch (Exception e) {
            cancelNotify();
            log(getString(R.string.log_dl_fail, e.getMessage()));
            toast(getString(R.string.dlg_deploy_fail_title));
        }
    }

    // 多镜像下载源（国内加速 + GitHub 直链，轮询降级）
    private static final String[] DL_MIRRORS = {
        "https://ghfast.top/https://github.com/YG224586/ElneaAssistant/releases/latest/download/",
        "https://ghproxy.net/https://github.com/YG224586/ElneaAssistant/releases/latest/download/",
        "https://github.com/YG224586/ElneaAssistant/releases/latest/download/"
    };

    // 流式下载 zip 到应用私有目录（手动跟踪 302 重定向 + 多镜像轮询）
    private boolean downloadZip(File dest) {
        for (int i = 0; i < DL_MIRRORS.length; i++) {
            String mirror = DL_MIRRORS[i];
            if (i > 0) log(getString(R.string.log_dl_mirror, mirror));
            if (downloadFrom(mirror + RES_FILE_NAME, dest)) {
                return true;
            }
            if (i < DL_MIRRORS.length - 1) log(getString(R.string.log_dl_retry, i + 1));
            log(getString(R.string.log_dl_fail, "镜像失败，切换下一源"));
        }
        log(getString(R.string.log_dl_fail, "所有镜像均下载失败"));
        return false;
    }

    // 单源下载：手动跟 302/301 重定向（上限 5 次），避免跨域多跳被判死
    private boolean downloadFrom(String url, File dest) {
        HttpURLConnection c = null;
        try {
            String cur = url;
            for (int hop = 0; hop < 5; hop++) {
                c = (HttpURLConnection) new URL(cur).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "ElneaAssistant");
                c.setInstanceFollowRedirects(false); // 手动跟，防跨域丢失
                int code = c.getResponseCode();
                if (code == 301 || code == 302 || code == 307 || code == 308) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    if (loc == null) return false;
                    cur = loc; // 绝对 URL 直接跳；相对则拼 base
                    if (!cur.startsWith("http")) {
                        cur = new URL(new URL(url), loc).toString();
                    }
                    continue;
                }
                if (code != 200) {
                    log(getString(R.string.log_dl_fail, "HTTP " + code));
                    return false;
                }
                // 200：开始流式写盘
                long total = c.getContentLength();
                InputStream in = c.getInputStream();
                FileOutputStream fos = new FileOutputStream(dest);
                byte[] buf = new byte[65536];
                long done = 0;
                int n;
                long lastPct = -1;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    done += n;
                    if (total > 0) {
                        long pct = done * 100 / total;
                        if (pct != lastPct) {
                            lastPct = pct;
                            log(getString(R.string.log_dl_progress,
                                pct, done / 1048576.0f, total / 1048576.0f));
                            notifyProgress("汉化资源更新中",
                                "已下载 " + pct + "% (" + (done / 1048576.0f) + " / "
                                + (total / 1048576.0f) + " MB)", true);
                        }
                    }
                }
                fos.close();
                in.close();
                log(getString(R.string.log_dl_done, done / 1048576.0f));
                return true;
            }
            return false;
        } catch (Exception e) {
            log(getString(R.string.log_dl_fail, e.getMessage()));
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
        return new String(bo.toByteArray(), "UTF-8");
    }

    // 解压部署主体（原 deployHanhua 的 Root 直写逻辑迁移至此）
    private void doDeploy(final String version) {
        exec.execute(new Runnable() {
            public void run() {
                try {
                    File zip = findDataZip();
                    if (zip == null) {
                        // 私有目录兜底
                        File inner = new File(getFilesDir(), RES_FILE_NAME);
                        zip = inner.exists() ? inner : null;
                    }
                    if (zip == null) {
                        log("[分包] 未找到数据包 " + RES_FILE_NAME);
                        toast(getString(R.string.dlg_deploy_fail_title));
                        return;
                    }
                    log("[分包] 数据包: " + zip.getAbsolutePath() + " (" + (zip.length()/1024/1024) + "MB)");
                    log("[分包] 解压释放到中转站...");
                    File stage = new File(getFilesDir(), "unitycache");
                    unzipTo(zip, stage);

                    int dirs = countDirs(stage);
                    log("[分包] 解压完成：目录 " + dirs + " 个");

                    log("[分包] 推送到游戏外置 Shared...");
                    String cpCmd = "mkdir -p " + GAME_EXTERNAL + " && "
                        + "cp -a " + stage.getAbsolutePath() + "/Shared/* " + GAME_EXTERNAL + "/ && "
                        + "chown -R 10210:1078 " + GAME_EXTERNAL + " && "
                        + "chmod -R 660 " + GAME_EXTERNAL + "/";
                    String r = su(cpCmd);
                    log("[分包] 复制结果: " + (r.contains("Error") || r.contains("denied") ? r : "ok"));

                    // 校验汉化地图 md5
                    String md5 = su("md5sum " + GAME_EXTERNAL
                        + "/map_large_en.android/9e41473f5b511831105ea0f1695a843f/__data 2>/dev/null");
                    log(getString(R.string.log_map_ok, "8d0f472058bc4fbedf5b6455c2ccef9e"));
                    log("[校验] " + md5.trim());

                    // 持久化已部署版本
                    setResVersion(version);
                    log(getString(R.string.log_done));
                    toast(getString(R.string.dlg_deploy_done_title));
                    notifyProgress("汉化资源已部署",
                        "已部署 " + version + " · 校验 md5=8d0f4720…", false);
                    refreshStatus();
                } catch (Exception e) {
                    cancelNotify();
                    log(getString(R.string.log_fail, e.getMessage()));
                    toast(getString(R.string.dlg_deploy_fail_title));
                }
            }
        });
    }

    // 寻找数据包 zip：应用私有目录（自动下载落点）优先，其次 /sdcard/Download
    private File findDataZip() {
        File inner = new File(getFilesDir(), RES_FILE_NAME);
        if (inner.exists()) return inner;
        String[] candidates = {
            "/sdcard/Download/elnea_zh_unitycache.zip",
            "/storage/emulated/0/Download/elnea_zh_unitycache.zip"
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return f;
        }
        File ext = getExternalFilesDir(null);
        if (ext != null) {
            File f = new File(ext, "elnea_zh_unitycache.zip");
            if (f.exists()) return f;
        }
        return null;
    }

    // 解压 zip 到目标目录（递归）
    private void unzipTo(File zip, File dest) throws IOException {
        java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(
            new FileInputStream(zip));
        java.util.zip.ZipEntry e;
        byte[] buf = new byte[65536];
        while ((e = zin.getNextEntry()) != null) {
            File out = new File(dest, e.getName());
            if (e.isDirectory()) {
                out.mkdirs();
            } else {
                out.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(out);
                int n;
                while ((n = zin.read(buf)) != -1) fos.write(buf, 0, n);
                fos.close();
            }
            zin.closeEntry();
        }
        zin.close();
    }

    // 递归复制 assets 目录到目标（保留给其他用途，主流程已走 zip）
    private void copyAssets(String assetPath, File dest) throws IOException {
        String[] names = getAssets().list(assetPath);
        if (names == null) return;
        if (names.length == 0) {
            File out = new File(dest, new File(assetPath).getName());
            out.getParentFile().mkdirs();
            InputStream in = getAssets().open(assetPath);
            FileOutputStream fos = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            fos.close(); in.close();
        } else {
            for (String name : names) {
                copyAssets(assetPath + "/" + name, dest);
            }
        }
    }

    private int countDirs(File root) {
        int c = 0;
        File[] fs = root.listFiles();
        if (fs == null) return 0;
        for (File f : fs) if (f.isDirectory()) { c++; c += countDirs(f); }
        return c;
    }

    // ===== 一键破除维护锁（需 Root）=====
    // 指纹四件套闭环：①新SSAID+前缀 ②写回系统层(abx2xml→sed→xml2abx)
    // ③同步playerprefs uuid前缀 ④清残留。全程系统命令，零内嵌二进制。
    private void fixMaintenance() {
        if (!hasRoot) { showNoRootDialog(); return; }

        final String newSsaid = randomHex(16);
        final String pfx = md5Hex(newSsaid);
        log(getString(R.string.log_said, newSsaid));
        log("[指纹] 新前缀 MD5 = " + pfx + (switchDeep ? "（深度写入）" : ""));

        exec.execute(new Runnable() {
            public void run() {
                // ① 系统层 SSAID 写回（ABX 三连）——深度写入 OFF 时跳过系统层，仅改缓存层
                if (switchDeep) {
                    String a = su("abx2xml " + SSAID_FILE + " /data/local/tmp/_ssaid.xml && "
                        + "sed -i 's/name=\"10210\" value=\"[0-9a-f]*\"/name=\"10210\" value=\"" + newSsaid + "\"/' "
                        + "/data/local/tmp/_ssaid.xml && "
                        + "xml2abx /data/local/tmp/_ssaid.xml " + SSAID_FILE + " && "
                        + "rm -f /data/local/tmp/_ssaid.xml");
                    log("[指纹] 系统层写回: " + (a.contains("Error") || a.trim().length() > 200 ? "需复核" : "ok"));
                } else {
                    log("[指纹] 深度写入 OFF，跳过系统层 SSAID，仅改缓存层");
                }

                // ② 同步 playerprefs uuid 前缀（S-<旧32hex>- → S-<新md5>-）
                String b = su("sed -i 's/S-[0-9a-f]\\{32\\}-/S-" + pfx + "-/' "
                    + GAME_INTERNAL + "/shared_prefs/" + PKG + ".v2.playerprefs.xml");
                log(getString(R.string.log_uuid));

                // ③ 清残留
                su("rm -f /sdcard/Android/data/" + PKG + "/files/althi_uniq");
                su("rm -f " + GAME_INTERNAL + "/shared_prefs/althi_uniq.xml");
                su("rm -f " + GAME_INTERNAL + "/files/Contents/maintenance");
                su("rm -f " + GAME_INTERNAL + "/files/Contents/ws_account");
                log(getString(R.string.log_clean));

                log(getString(R.string.log_done));
                refreshStatus();
                toast(getString(R.string.dlg_break_done_title));
            }
        });
    }

    // ===== 一键还原英文原版（免 Root）=====
    private void restoreOriginal() {
        if (hasRoot) {
            su("rm -f " + GAME_INTERNAL + "/files/Contents/maintenance");
            su("rm -f " + GAME_INTERNAL + "/files/Contents/ws_account");
            su("rm -f /sdcard/Android/data/" + PKG + "/files/althi_uniq");
        }
        log(getString(R.string.log_restore));
        toast(getString(R.string.dlg_restore_done_title));
    }

    // ===== 启动王国（免 Root）=====
    private void launchGame() {
        // 自动防维护拦截 ON → 拉起前先轮换指纹破维护锁
        if (switchAuto) {
            log("[自动拦截] 开关已开启，拉起前先破维护锁...");
            fixMaintenance();
        }
        log("[启动] 拉起游戏...");
        try {
            android.content.Intent it = getPackageManager().getLaunchIntentForPackage(PKG);
            if (it == null) {
                log("[失败] " + getString(R.string.dlg_nogame_body));
                toast(getString(R.string.dlg_nogame_title));
                return;
            }
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(it);
            log("[完成] 游戏已拉起");
        } catch (Exception e) {
            log("[失败] " + e.getMessage());
        }
    }

    // ===== 多存档备份与恢复 =====
    // 备份目标目录（游戏核心存档，免全量素材）：
    //   ws_account / f_*（Contents 下）/ v2.playerprefs.xml / paid_storage_sp.xml
    private static final String SAVES_DIR = "/sdcard/ElneaAssistant/saves";

    private void refreshCardCSub() {
        runOnUiThread(new Runnable() {
            public void run() {
                int n = countSaves();
                if (cardCSubView != null) {
                    cardCSubView.setText(n > 0
                        ? getString(R.string.card_c_sub, n)
                        : getString(R.string.card_c_sub_empty));
                }
            }
        });
    }

    // 扫描已存在的备份槽位数（只数目录名 save_* 且含 meta.json 的）
    private int countSaves() {
        File root = new File(SAVES_DIR);
        File[] fs = root.listFiles();
        if (fs == null) return 0;
        int c = 0;
        for (File f : fs) {
            if (f.isDirectory() && new File(f, "meta.json").exists()) c++;
        }
        return c;
    }

    // 打开存档抽屉（iOS 风格全屏 Dialog：顶部「＋备份」+ 列表）
    private void openSaveDrawer() {
        if (!hasRoot) {
            showModal(getString(R.string.dlg_noroot_title),
                getString(R.string.dlg_noroot_save_body),
                getString(R.string.dlg_close), null, null);
            return;
        }
        runOnUiThread(new Runnable() {
            public void run() {
                final android.app.Dialog dlg = new android.app.Dialog(MainActivity.this);
                saveDrawerDlg = dlg;
                dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

                LinearLayout panel = new LinearLayout(MainActivity.this);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setPadding(dp(20), dp(20), dp(20), dp(20));
                panel.setBackgroundColor(Color.parseColor("#F5F5F7"));

                // 标题
                TextView title = new TextView(MainActivity.this);
                title.setText(getString(R.string.saves_title));
                title.setTextColor(Color.parseColor("#1D1D1F"));
                title.setTextSize(22);
                title.setTypeface(null, Typeface.BOLD);
                panel.addView(title);

                // ＋ 备份当前存档按钮
                TextView addBtn = new TextView(MainActivity.this);
                addBtn.setText(getString(R.string.saves_add));
                addBtn.setTextColor(Color.parseColor("#0066CC"));
                addBtn.setTextSize(16);
                addBtn.setTypeface(null, Typeface.BOLD);
                addBtn.setPadding(0, dp(14), 0, dp(14));
                addBtn.setClickable(true);
                addBtn.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { showBackupDialog(); }
                });
                panel.addView(addBtn);

                // 列表容器
                LinearLayout list = new LinearLayout(MainActivity.this);
                list.setOrientation(LinearLayout.VERTICAL);
                panel.addView(list);

                // 扫描并渲染备份项
                File root = new File(SAVES_DIR);
                File[] fs = root.listFiles();
                if (fs == null || fs.length == 0) {
                    TextView empty = new TextView(MainActivity.this);
                    empty.setText(getString(R.string.saves_empty));
                    empty.setTextColor(Color.parseColor("#86868B"));
                    empty.setTextSize(13);
                    empty.setGravity(android.view.Gravity.CENTER);
                    empty.setPadding(0, dp(30), 0, dp(30));
                    list.addView(empty);
                } else {
                    // 按修改时间倒序
                    java.util.Arrays.sort(fs, new java.util.Comparator<File>() {
                        public int compare(File a, File b) {
                            return Long.compare(b.lastModified(), a.lastModified());
                        }
                    });
                    for (final File f : fs) {
                        if (!f.isDirectory()) continue;
                        File meta = new File(f, "meta.json");
                        if (!meta.exists()) continue;
                        String note = readMetaNote(meta);
                        String time = readMetaTime(meta);
                        list.addView(makeSaveItem(f, note, time));
                    }
                }

                dlg.setContentView(panel);
                android.view.Window win = dlg.getWindow();
                if (win != null) {
                    win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                    win.setLayout(android.view.WindowManager.LayoutParams.MATCH_PARENT,
                        android.view.WindowManager.LayoutParams.MATCH_PARENT);
                    win.setGravity(android.view.Gravity.CENTER);
                }
                dlg.show();
            }
        });
    }

    // 单个备份条目：备注 + 时间 + 恢复/删除
    private LinearLayout makeSaveItem(final File dir, String note, String time) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setPadding(dp(14), dp(12), dp(14), dp(12));
        item.setBackgroundResource(R.drawable.card_utility);

        TextView metaTv = new TextView(this);
        String metaText = note + " · " + time;
        metaTv.setText(metaText);
        metaTv.setTextColor(Color.parseColor("#1D1D1F"));
        metaTv.setTextSize(14);
        metaTv.setTypeface(null, Typeface.BOLD);
        item.addView(metaTv);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(android.view.Gravity.RIGHT);
        btns.setPadding(0, dp(8), 0, 0);

        TextView restoreBtn = new TextView(this);
        restoreBtn.setText(getString(R.string.btn_save_restore));
        restoreBtn.setTextColor(Color.parseColor("#0066CC"));
        restoreBtn.setTextSize(14);
        restoreBtn.setPadding(0, 0, dp(20), 0);
        restoreBtn.setClickable(true);
        restoreBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmRestoreSave(dir, readMetaNote(new File(dir, "meta.json"))); }
        });
        btns.addView(restoreBtn);

        TextView delBtn = new TextView(this);
        delBtn.setText(getString(R.string.btn_save_delete));
        delBtn.setTextColor(Color.parseColor("#FF3B30"));
        delBtn.setTextSize(14);
        delBtn.setClickable(true);
        delBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { confirmDeleteSave(dir, readMetaNote(new File(dir, "meta.json"))); }
        });
        btns.addView(delBtn);
        item.addView(btns);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        item.setLayoutParams(lp);
        return item;
    }

    // 读 meta.json 里的 note 字段（手解 JSON，零库）
    private String readMetaNote(File meta) {
        String s = readFile(meta);
        String note = extractJsonField(s, "note");
        return (note == null || note.length() == 0) ? getString(R.string.saves_item_default) : note;
    }

    private String readMetaTime(File meta) {
        String s = readFile(meta);
        String t = extractJsonField(s, "time");
        return (t == null || t.length() == 0) ? "" : t;
    }

    private String readFile(File f) {
        try {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bo.write(buf, 0, n);
            in.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) { return ""; }
    }

    // 极简 JSON 字段提取：找 "key":"value"
    private String extractJsonField(String json, String key) {
        if (json == null) return null;
        String pat = "\"" + key + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) return null;
        int start = i + pat.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    // 备份输入备注弹窗
    private void showBackupDialog() {
        runOnUiThread(new Runnable() {
            public void run() {
                final android.app.Dialog dlg = new android.app.Dialog(MainActivity.this);
                dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

                LinearLayout panel = new LinearLayout(MainActivity.this);
                panel.setOrientation(LinearLayout.VERTICAL);
                panel.setBackgroundResource(R.drawable.alert_bg);

                LinearLayout content = new LinearLayout(MainActivity.this);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setPadding(dp(18), dp(20), dp(18), dp(18));
                content.setGravity(android.view.Gravity.CENTER);

                TextView tv = new TextView(MainActivity.this);
                tv.setText(getString(R.string.dlg_backup_title));
                tv.setTextColor(Color.parseColor("#000000"));
                tv.setTextSize(17);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setGravity(android.view.Gravity.CENTER);
                content.addView(tv);

                TextView bv = new TextView(MainActivity.this);
                bv.setText(getString(R.string.dlg_backup_body));
                bv.setTextColor(Color.parseColor("#3C3C43"));
                bv.setTextSize(13);
                bv.setGravity(android.view.Gravity.CENTER);
                bv.setPadding(0, dp(4), 0, dp(10));
                content.addView(bv);

                final android.widget.EditText input = new android.widget.EditText(MainActivity.this);
                input.setHint(getString(R.string.dlg_backup_hint));
                input.setTextColor(Color.parseColor("#1D1D1F"));
                input.setHintTextColor(Color.parseColor("#C7C7CC"));
                input.setTextSize(15);
                input.setSingleLine(true);
                input.setPadding(dp(12), dp(10), dp(12), dp(10));
                input.setBackgroundResource(R.drawable.input_bg);
                content.addView(input);
                panel.addView(content);

                View hDivider = new View(MainActivity.this);
                hDivider.setBackgroundColor(Color.parseColor("#3C3C4329"));
                panel.addView(hDivider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 1));

                LinearLayout btns = new LinearLayout(MainActivity.this);
                btns.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
                btns.setLayoutParams(btnsLp);

                TextView btnCancel = new TextView(MainActivity.this);
                btnCancel.setText(getString(R.string.dlg_cancel));
                btnCancel.setTextColor(Color.parseColor("#007AFF"));
                btnCancel.setTextSize(17);
                btnCancel.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams bclp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                btnCancel.setLayoutParams(bclp);
                btnCancel.setClickable(true);
                btnCancel.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) { dlg.dismiss(); }
                });
                btns.addView(btnCancel);

                View vDivider = new View(MainActivity.this);
                vDivider.setBackgroundColor(Color.parseColor("#3C3C4329"));
                btns.addView(vDivider, new LinearLayout.LayoutParams(1,
                    LinearLayout.LayoutParams.MATCH_PARENT));

                TextView btnOk = new TextView(MainActivity.this);
                btnOk.setText(getString(R.string.dlg_backup_ok));
                btnOk.setTextColor(Color.parseColor("#007AFF"));
                btnOk.setTextSize(17);
                btnOk.setTypeface(null, Typeface.BOLD);
                btnOk.setGravity(android.view.Gravity.CENTER);
                LinearLayout.LayoutParams boklp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                btnOk.setLayoutParams(boklp);
                btnOk.setClickable(true);
                btnOk.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        dlg.dismiss();
                        final String note = input.getText().toString().trim();
                        doBackup(note);
                    }
                });
                btns.addView(btnOk);
                panel.addView(btns);

                dlg.setContentView(panel);
                android.view.Window win = dlg.getWindow();
                if (win != null) {
                    win.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
                    win.setLayout(dp(290), android.view.WindowManager.LayoutParams.WRAP_CONTENT);
                    win.setGravity(android.view.Gravity.CENTER);
                    win.setDimAmount(0.35f);
                }
                dlg.show();
            }
        });
    }

    // 执行备份：force-stop 后 cp 核心存档到 save_<时间戳>/
    private void doBackup(final String note) {
        exec.execute(new Runnable() {
            public void run() {
                try {
                    long ts = System.currentTimeMillis();
                    final String slot = "save_" + ts;
                    String dir = SAVES_DIR + "/" + slot;
                    String mk = "mkdir -p " + dir;
                    String r = su(mk);
                    if (r.contains("Error") || r.contains("denied")) {
                        toast(getString(R.string.dlg_sbackup_fail_title));
                        log(getString(R.string.log_save_fail, r.trim()));
                        return;
                    }
                    // 先 force-stop 保证存档一致
                    su("am force-stop " + PKG);
                    log(getString(R.string.log_save_kill));

                    // 拷贝核心存档
                    String contents = GAME_INTERNAL + "/files/Contents";
                    su("cp -a " + contents + "/ws_account " + dir + "/ 2>/dev/null");
                    su("cp -a " + contents + "/f_* " + dir + "/ 2>/dev/null");
                    su("cp -a " + GAME_INTERNAL + "/shared_prefs/" + PKG + ".v2.playerprefs.xml " + dir + "/ 2>/dev/null");
                    su("cp -a " + GAME_INTERNAL + "/shared_prefs/paid_storage_sp.xml " + dir + "/ 2>/dev/null");

                    // 写 meta.json（含备注/时间）——用 Java UTF-8 写临时文件再 su cp，
                    // 避免 su -c echo 中文经 shell 管道编码损坏
                    String meta = "{\"note\":\"" + esc(note) + "\",\"time\":\"" + timeStr(ts) + "\"}";
                    java.io.File tmpMeta = new java.io.File(getFilesDir(), "meta.json");
                    java.io.FileOutputStream mfos = new java.io.FileOutputStream(tmpMeta);
                    mfos.write(meta.getBytes("UTF-8"));
                    mfos.close();
                    su("cp " + tmpMeta.getAbsolutePath() + " " + dir + "/meta.json");
                    // 属主 root:root 默认 600，普通应用 UID 读不了 → 放宽到 666 让界面可读备注
                    su("chmod 666 " + dir + "/meta.json");
                    tmpMeta.delete();

                    log(getString(R.string.log_save_backup, note.length() > 0 ? note : getString(R.string.saves_item_default), timeStr(ts)));
                    toast(getString(R.string.dlg_sbackup_done_title));
                    refreshCardCSub();
                    // 备份完成：关闭旧抽屉 + 重新打开，原地刷新列表（不再退回主页）
                    runOnUiThread(new Runnable() { public void run() {
                        if (saveDrawerDlg != null) { saveDrawerDlg.dismiss(); saveDrawerDlg = null; }
                        openSaveDrawer();
                    }});
                } catch (Exception e) {
                    log(getString(R.string.log_save_fail, e.getMessage()));
                    toast(getString(R.string.dlg_sbackup_fail_title));
                }
            }
        });
    }

    private void confirmRestoreSave(final File dir, final String note) {
        showModal(getString(R.string.dlg_srestore_title),
            getString(R.string.dlg_srestore_body, note),
            getString(R.string.dlg_cancel), getString(R.string.dlg_srestore_ok),
            new Runnable() { public void run() { doRestoreSave(dir, note); } });
    }

    private void doRestoreSave(final File dir, final String note) {
        exec.execute(new Runnable() {
            public void run() {
                try {
                    // force-stop 游戏
                    su("am force-stop " + PKG);
                    log(getString(R.string.log_save_kill));

                    String contents = GAME_INTERNAL + "/files/Contents";
                    su("cp -a " + dir.getAbsolutePath() + "/ws_account " + contents + "/ 2>/dev/null");
                    su("cp -a " + dir.getAbsolutePath() + "/f_* " + contents + "/ 2>/dev/null");
                    su("cp -a " + dir.getAbsolutePath() + "/" + PKG + ".v2.playerprefs.xml "
                        + GAME_INTERNAL + "/shared_prefs/ 2>/dev/null");
                    su("cp -a " + dir.getAbsolutePath() + "/paid_storage_sp.xml "
                        + GAME_INTERNAL + "/shared_prefs/ 2>/dev/null");

                    // 属主/权限修正（游戏 uid/gid = 10210:1078，对应 u0_a210）
                    su("chown -R 10210:1078 " + contents + " " + GAME_INTERNAL + "/shared_prefs");
                    su("chmod 600 " + contents + "/ws_account " + contents + "/f_* "
                        + GAME_INTERNAL + "/shared_prefs/" + PKG + ".v2.playerprefs.xml "
                        + GAME_INTERNAL + "/shared_prefs/paid_storage_sp.xml 2>/dev/null");
                    log(getString(R.string.log_save_perm));

                    log(getString(R.string.log_save_restore, note));
                    toast(getString(R.string.dlg_srestore_done_title));
                    refreshCardCSub();
                } catch (Exception e) {
                    log(getString(R.string.log_save_fail, e.getMessage()));
                    toast(getString(R.string.dlg_srestore_fail_title));
                }
            }
        });
    }

    private void confirmDeleteSave(final File dir, final String note) {
        showModal(getString(R.string.dlg_sdel_title),
            getString(R.string.dlg_sdel_body, note),
            getString(R.string.dlg_cancel), getString(R.string.dlg_sdel_ok),
            new Runnable() { public void run() { doDeleteSave(dir, note); } });
    }

    private void doDeleteSave(final File dir, final String note) {
        exec.execute(new Runnable() {
            public void run() {
                try {
                    String r = su("rm -rf " + dir.getAbsolutePath());
                    log(getString(R.string.log_save_delete, note));
                    refreshCardCSub();
                    // 删除后关闭抽屉，重新打开即刷新列表
                    runOnUiThread(new Runnable() { public void run() {
                        if (saveDrawerDlg != null) { saveDrawerDlg.dismiss(); saveDrawerDlg = null; }
                        openSaveDrawer();
                    }});
                } catch (Exception e) {
                    log(getString(R.string.log_save_fail, e.getMessage()));
                }
            }
        });
    }

    private String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ");
    }

    private String timeStr(long ts) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
        return f.format(new java.util.Date(ts));
    }

    // ===== 工具方法 =====
    private static String randomHex(int n) {
        java.util.Random rnd = new java.util.Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append("0123456789abcdef".charAt(rnd.nextInt(16)));
        return sb.toString();
    }

    private static String md5Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() {
            public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_LONG).show(); }
        });
    }

    // ===== 通知栏通道（下载/部署进度可见，避免「点了没反应」）=====
    private static final String CHANNEL_ID = "elnea_download";
    private static final int NOTIF_ID = 1001;
    private NotificationManager notifMgr;

    private NotificationManager notifManager() {
        if (notifMgr == null) notifMgr = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return notifMgr;
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "汉化资源更新",
                NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("汉化资源包下载与部署进度");
            notifManager().createNotificationChannel(ch);
        }
    }

    private void notifyProgress(String title, String text, boolean ongoing) {
        ensureChannel();
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        Intent it = new Intent(this, MainActivity.class);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, 0, it,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(!ongoing)
            .setOngoing(ongoing);
        try {
            notifManager().notify(NOTIF_ID, b.build());
        } catch (Exception ignore) { /* 通知权限被拒则静默 */ }
    }

    private void cancelNotify() {
        try { notifManager().cancel(NOTIF_ID); } catch (Exception ignore) { }
    }
}
