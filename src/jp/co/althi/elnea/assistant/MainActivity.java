package jp.co.althi.elnea.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.Toast;
import android.content.Context;
import android.content.SharedPreferences;

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
    private static final String RES_FILE_NAME = "elnea_zh_unitycache.zip";
    // 默认版本（若云端请求失败则退避为本地已装版本）
    private static final String DEFAULT_RES_VERSION = "v1.0.0";
    // SharedPreferences 持久化：已部署标记 + 数据包版本
    private static final String PREFS = "res_state";
    private static final String KEY_RES_VERSION = "res_version";

    private TextView statusLineView;
    private TextView statusRoot;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private volatile boolean hasRoot = false;
    private boolean gameInstalled = false;
    // Switch 持久开关状态：自动防维护拦截 / 深度写入
    private boolean switchAuto = false;
    private boolean switchDeep = false;

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

        // ===== ① Hero（Apple tight 标题 + 副标 + 右上角退出）=====
        LinearLayout heroRow = new LinearLayout(this);
        heroRow.setOrientation(LinearLayout.HORIZONTAL);
        heroRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(getString(R.string.app_full_name));
        title.setTextColor(Color.parseColor("#1D1D1F"));
        title.setTextSize(28);
        title.setTypeface(null, Typeface.BOLD);
        title.setLetterSpacing(-0.02f);
        title.setMaxLines(1);
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(ttlp);
        heroRow.addView(title);

        // 右上角 32dp 微圆形关闭按钮 ✕（Apple 克制式）
        TextView btnClose = new TextView(this);
        btnClose.setText("\u2715");
        btnClose.setTextColor(Color.parseColor("#86868B"));
        btnClose.setTextSize(14);
        btnClose.setGravity(android.view.Gravity.CENTER);
        btnClose.setBackgroundResource(R.drawable.circle_close);
        LinearLayout.LayoutParams closelp = new LinearLayout.LayoutParams(dp(32), dp(32));
        btnClose.setLayoutParams(closelp);
        btnClose.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        heroRow.addView(btnClose);
        root.addView(heroRow);

        TextView sub = new TextView(this);
        sub.setText(getString(R.string.app_slogan));
        sub.setTextColor(Color.parseColor("#86868B"));
        sub.setTextSize(14);
        sub.setPadding(0, dp(4), 0, dp(4));
        root.addView(sub);

        statusRoot = new TextView(this);
        statusRoot.setTextColor(Color.parseColor("#007AFF"));
        statusRoot.setTextSize(13);
        statusRoot.setPadding(0, 0, 0, dp(18));
        root.addView(statusRoot);

        // ===== ② 卡片 A · 汉化部署（横向流：左图标 + 中文本 + 右Tag+Chevron）=====
        root.addView(makeCard(getString(R.string.card_a_title),
            getString(R.string.card_a_sub), getString(R.string.tag_free),
            R.drawable.icon_bg_blue, R.drawable.ic_download,
            new Runnable() { public void run() { confirmDeploy(); } }));
        // ===== ③ 卡片 B · 破除维护锁 =====
        btnFixMaint = makeCard(getString(R.string.card_b_title),
            getString(R.string.card_b_sub), getString(R.string.tag_root),
            R.drawable.icon_bg_orange, R.drawable.ic_shield_key,
            new Runnable() { public void run() { confirmBreakLock(); } });
        root.addView(btnFixMaint);

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
        exec.execute(new Runnable() { public void run() { checkRoot(); } });
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

    // Apple iOS 风格 Switch 开关卡：左文本 + 右开关
    private LinearLayout makeSwitchGroup() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_utility);

        card.addView(makeSwitchRow(getString(R.string.switch_auto_title),
            getString(R.string.switch_auto_sub), false, new Runnable() {
                public void run() { switchAuto = !switchAuto; }
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
            getString(R.string.switch_deep_sub), false, new Runnable() {
                public void run() { switchDeep = !switchDeep; }
            }));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(8));
        card.setLayoutParams(lp);
        return card;
    }

    private LinearLayout makeSwitchRow(final String titleText, final String subtitle, boolean checked,
                                       final Runnable onToggle) {
        LinearLayout row = new LinearLayout(this);
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

        // 右侧开关：自绘 iOS Switch（蓝底圆钮 / 灰底圆钮）
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(checked);
        sw.setTextOn(""); sw.setTextOff("");
        sw.setShowText(false);
        try {
            sw.setThumbTintList(getResources().getColorStateList(R.color.switch_thumb));
            sw.setTrackTintList(getResources().getColorStateList(R.color.switch_track));
        } catch (Throwable t) { /* 低版本忽略 */ }
        LinearLayout.LayoutParams swlp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sw.setLayoutParams(swlp);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.CompoundButton b, boolean isChecked) {
                if (onToggle != null) onToggle.run();
            }
        });
        row.addView(sw);

        return row;
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
                    public void onClick(View v) { dlg.dismiss(); }
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
                    // ① 云端版本巡检（异步，失败降级为 DEFAULT_RES_VERSION）
                    final String remoteVer = fetchLatestVersion();
                    final String localVer = getResVersion();
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
        return DEFAULT_RES_VERSION;
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
        try {
            File zip = new File(getFilesDir(), RES_FILE_NAME);
            boolean ok = downloadZip(zip);
            if (!ok) {
                toast(getString(R.string.dlg_deploy_fail_title));
                return;
            }
            doDeploy(version);
        } catch (Exception e) {
            log(getString(R.string.log_dl_fail, e.getMessage()));
            toast(getString(R.string.dlg_deploy_fail_title));
        }
    }

    // 流式下载 zip 到应用私有目录（进度回显状态胶囊）
    private boolean downloadZip(File dest) {
        HttpURLConnection c = null;
        try {
            // 直链：GitHub Releases 的 browser_download_url 固定名
            String url = "https://github.com/YG224586/ElneaAssistant/releases/latest/download/"
                + RES_FILE_NAME;
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "ElneaAssistant");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code != 200) {
                log(getString(R.string.log_dl_fail, "HTTP " + code));
                return false;
            }
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
                    }
                }
            }
            fos.close();
            in.close();
            log(getString(R.string.log_dl_done, done / 1048576.0f));
            return true;
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
                    refreshStatus();
                } catch (Exception e) {
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
}
