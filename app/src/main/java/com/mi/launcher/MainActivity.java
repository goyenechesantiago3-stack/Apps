package com.mi.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

    static final int REQ_ICON = 1;
    static final int REQ_FONT = 2;

    static class AppItem {
        String pkg;
        String cls;
        String label;
        ResolveInfo ri;
    }

    PackageManager pm;
    SharedPreferences sp;
    float dens;
    int icoPx, statusH, navH;

    FrameLayout root, drawer;
    TextView clockTv, greetTv, dateTv;
    LinearLayout dock;
    GridView grid;
    EditText search;
    AppAdapter adapter;

    final List<AppItem> apps = new ArrayList<>();
    final Map<String, Bitmap> cache = new HashMap<>();
    Typeface font = Typeface.DEFAULT;
    boolean drawerOpen = false;
    boolean showHidden = false;
    String pendingPkg;
    final Handler h = new Handler(Looper.getMainLooper());
    GestureDetector gd;
    BroadcastReceiver pkgReceiver;

    final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            h.postDelayed(this, 10000);
        }
    };

    // ---------------------------------------------------------------- utilidades

    int dp(float v) { return (int) (v * dens + 0.5f); }

    int sysDimen(String name) {
        int id = getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    AlertDialog.Builder dlg() {
        return new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK);
    }

    GradientDrawable glass(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    TextView tv(float size, int color) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setShadowLayer(dp(4), 0, dp(1), 0x66000000);
        t.setTypeface(font);
        return t;
    }

    // ---------------------------------------------------------------- datos

    List<String> dockList() {
        String s = sp.getString("dock", "");
        List<String> l = new ArrayList<>();
        if (!s.isEmpty()) {
            for (String p : s.split(",")) {
                if (!p.isEmpty()) l.add(p);
            }
        }
        return l;
    }

    void saveDock(List<String> l) {
        sp.edit().putString("dock", TextUtils.join(",", l)).apply();
    }

    Set<String> hidden() {
        return new HashSet<>(sp.getStringSet("hidden", new HashSet<String>()));
    }

    void saveHidden(Set<String> s) {
        sp.edit().putStringSet("hidden", s).apply();
    }

    String labelOf(AppItem a) {
        return sp.getString("lbl_" + a.pkg, a.label);
    }

    AppItem find(String pkg) {
        for (AppItem a : apps) {
            if (a.pkg.equals(pkg)) return a;
        }
        return null;
    }

    Typeface loadFont() {
        String p = sp.getString("font", null);
        if (p != null) {
            File f = new File(p);
            if (f.exists()) {
                try {
                    return Typeface.createFromFile(f);
                } catch (Exception e) {
                    // se usa la fuente por defecto
                }
            }
        }
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    void loadApps() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> l = pm.queryIntentActivities(i, 0);
        apps.clear();
        for (ResolveInfo r : l) {
            if (r.activityInfo.packageName.equals(getPackageName())) continue;
            AppItem a = new AppItem();
            a.pkg = r.activityInfo.packageName;
            a.cls = r.activityInfo.name;
            a.label = r.loadLabel(pm).toString();
            a.ri = r;
            apps.add(a);
        }
    }

    void initDock() {
        if (sp.getBoolean("dock_init", false)) return;
        List<String> l = new ArrayList<>();
        addDefault(l, new Intent(Intent.ACTION_DIAL));
        Intent sms = new Intent(Intent.ACTION_MAIN);
        sms.addCategory(Intent.CATEGORY_APP_MESSAGING);
        addDefault(l, sms);
        Intent br = new Intent(Intent.ACTION_MAIN);
        br.addCategory(Intent.CATEGORY_APP_BROWSER);
        addDefault(l, br);
        addDefault(l, new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE));
        saveDock(l);
        sp.edit().putBoolean("dock_init", true).apply();
    }

    void addDefault(List<String> l, Intent i) {
        ResolveInfo ri = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY);
        if (ri != null && ri.activityInfo != null) {
            String p = ri.activityInfo.packageName;
            if (!p.equals("android") && find(p) != null && !l.contains(p)) l.add(p);
        }
    }

    // ---------------------------------------------------------------- íconos

    float shapeRadius() {
        int s = sp.getInt("shape", 1);
        if (s == 0) return 0.5f;
        if (s == 1) return 0.30f;
        return 0.14f;
    }

    Bitmap shaped(Bitmap src) {
        int s = icoPx;
        Bitmap out = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        float r = shapeRadius() * s;
        c.drawRoundRect(new RectF(0, 0, s, s), r, r, p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        Bitmap scaled = (src.getWidth() == s && src.getHeight() == s)
                ? src : Bitmap.createScaledBitmap(src, s, s, true);
        c.drawBitmap(scaled, 0, 0, p);
        return out;
    }

    Bitmap icon(AppItem a) {
        Bitmap b = cache.get(a.pkg);
        if (b != null) return b;
        File f = new File(new File(getFilesDir(), "icons"), a.pkg + ".png");
        Bitmap src = null;
        if (f.exists()) src = BitmapFactory.decodeFile(f.getPath());
        if (src == null) {
            Drawable dr = a.ri.loadIcon(pm);
            src = Bitmap.createBitmap(icoPx, icoPx, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(src);
            dr.setBounds(0, 0, icoPx, icoPx);
            dr.draw(c);
        }
        b = shaped(src);
        cache.put(a.pkg, b);
        return b;
    }

    Bitmap decodeUri(Uri u, int target) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        InputStream in = getContentResolver().openInputStream(u);
        BitmapFactory.decodeStream(in, null, o);
        in.close();
        int s = 1;
        while (o.outWidth / (s * 2) >= target && o.outHeight / (s * 2) >= target) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = s;
        in = getContentResolver().openInputStream(u);
        Bitmap b = BitmapFactory.decodeStream(in, null, o2);
        in.close();
        return b;
    }

    // ---------------------------------------------------------------- ciclo de vida

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pm = getPackageManager();
        sp = getSharedPreferences("launcher", MODE_PRIVATE);
        dens = getResources().getDisplayMetrics().density;
        icoPx = dp(56);
        statusH = sysDimen("status_bar_height");
        navH = sysDimen("navigation_bar_height");

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        font = loadFont();
        loadApps();
        initDock();

        gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (e1 == null || e2 == null) return false;
                float dy = e2.getY() - e1.getY();
                float dx = e2.getX() - e1.getX();
                if (Math.abs(dy) < Math.abs(dx) * 1.5f) return false;
                if (!drawerOpen && dy < -dp(80) && vy < -800) {
                    openDrawer(true);
                    return true;
                }
                if (drawerOpen && dy > dp(80) && vy > 800 && gridAtTop()) {
                    closeDrawer();
                    return true;
                }
                return false;
            }
        });
        gd.setIsLongpressEnabled(false);

        buildUi();
        registerPkgReceiver();
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(tick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        h.removeCallbacks(tick);
        try {
            if (pkgReceiver != null) unregisterReceiver(pkgReceiver);
        } catch (Exception e) {
            // nada
        }
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (drawerOpen) closeDrawer();
    }

    @Override
    public void onBackPressed() {
        if (drawerOpen) closeDrawer();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (gd != null) gd.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    void registerPkgReceiver() {
        pkgReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                loadApps();
                cache.clear();
                refreshAll();
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(pkgReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(pkgReceiver, f);
        }
    }

    // ---------------------------------------------------------------- interfaz

    void buildUi() {
        drawerOpen = false;
        root = new FrameLayout(this);
        setContentView(root);

        // Reloj, saludo y fecha
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        greetTv = tv(18, Color.WHITE);
        clockTv = tv(84, Color.WHITE);
        clockTv.setTypeface(font, Typeface.BOLD);
        dateTv = tv(16, 0xCCFFFFFF);
        clockTv.setOnLongClickListener(x -> {
            showSettings();
            return true;
        });
        top.addView(greetTv);
        top.addView(clockTv);
        top.addView(dateTv);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tlp.topMargin = statusH + dp(40);
        root.addView(top, tlp);

        // Dock
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER);
        dock.setPadding(dp(12), dp(12), dp(12), dp(12));
        dock.setBackground(glass(0x33FFFFFF, 0x66FFFFFF, 30));
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = navH + dp(36);
        root.addView(dock, dlp);
        fillDock();

        // Indicador de "desliza hacia arriba"
        View pill = new View(this);
        pill.setBackground(glass(0x88FFFFFF, 0x00000000, 3));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                dp(44), dp(5), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        plp.bottomMargin = navH + dp(14);
        root.addView(pill, plp);

        // Cajón de apps
        drawer = new FrameLayout(this);
        drawer.setBackgroundColor(0xE60A0A12);
        drawer.setClickable(true);
        drawer.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), statusH + dp(12), dp(16), 0);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        search = new EditText(this);
        search.setHint("Buscar apps");
        search.setHintTextColor(0x99FFFFFF);
        search.setTextColor(Color.WHITE);
        search.setTypeface(font);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setPadding(dp(18), dp(12), dp(18), dp(12));
        search.setBackground(glass(0x33FFFFFF, 0x55FFFFFF, 26));
        bar.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView gear = tv(26, Color.WHITE);
        gear.setText("\u2699");
        gear.setPadding(dp(14), dp(6), dp(6), dp(6));
        gear.setOnClickListener(x -> showSettings());
        bar.addView(gear);
        col.addView(bar);

        adapter = new AppAdapter();
        grid = new GridView(this);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(8));
        grid.setPadding(0, dp(12), 0, navH + dp(16));
        grid.setClipToPadding(false);
        grid.setSelector(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        grid.setVerticalScrollBarEnabled(false);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(adapter.list.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            showAppMenu(adapter.list.get(pos));
            return true;
        });
        col.addView(grid, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        drawer.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(drawer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                adapter.filter(s.toString());
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        search.setOnEditorActionListener((v, actionId, ev) -> {
            if (adapter.list.size() > 0) launch(adapter.list.get(0));
            return true;
        });

        adapter.filter("");
        updateClock();
    }

    void rebuildUi() {
        boolean was = drawerOpen;
        buildUi();
        if (was) openDrawer(false);
    }

    void refreshAll() {
        if (dock != null) fillDock();
        if (adapter != null && search != null) adapter.filter(search.getText().toString());
    }

    void fillDock() {
        dock.removeAllViews();
        int count = 0;
        for (String p : dockList()) {
            final AppItem a = find(p);
            if (a == null) continue;
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(icon(a));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(icoPx, icoPx);
            lp.setMargins(dp(8), 0, dp(8), 0);
            iv.setLayoutParams(lp);
            iv.setOnClickListener(x -> launch(a));
            iv.setOnLongClickListener(x -> {
                showAppMenu(a);
                return true;
            });
            dock.addView(iv);
            count++;
        }
        dock.setVisibility(count == 0 ? View.GONE : View.VISIBLE);
    }

    void updateClock() {
        if (clockTv == null) return;
        Date now = new Date();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clockTv.setText(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now));
        int hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greetTv.setText(hr < 6 ? "Buenas noches" : hr < 12 ? "Buenos días"
                : hr < 19 ? "Buenas tardes" : "Buenas noches");
        String ds = new SimpleDateFormat("EEEE d 'de' MMMM", new Locale("es", "MX")).format(now);
        dateTv.setText(ds.substring(0, 1).toUpperCase(new Locale("es")) + ds.substring(1));
    }

    boolean gridAtTop() {
        if (grid == null || grid.getChildCount() == 0) return true;
        return grid.getFirstVisiblePosition() == 0
                && grid.getChildAt(0).getTop() >= grid.getPaddingTop();
    }

    void openDrawer(boolean anim) {
        drawerOpen = true;
        drawer.setVisibility(View.VISIBLE);
        if (anim) {
            drawer.setAlpha(0f);
            drawer.setTranslationY(dp(120));
            drawer.animate().alpha(1f).translationY(0f).setDuration(220).start();
        } else {
            drawer.setAlpha(1f);
            drawer.setTranslationY(0f);
        }
    }

    void closeDrawer() {
        drawerOpen = false;
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        } catch (Exception e) {
            // nada
        }
        search.clearFocus();
        search.setText("");
        drawer.animate().alpha(0f).translationY(dp(120)).setDuration(180)
                .withEndAction(() -> {
                    if (!drawerOpen) drawer.setVisibility(View.GONE);
                }).start();
    }

    void launch(AppItem a) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setClassName(a.pkg, a.cls);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            if (drawerOpen) closeDrawer();
        } catch (Exception e) {
            toast("No se pudo abrir la app");
        }
    }

    // ---------------------------------------------------------------- menús

    void showAppMenu(final AppItem a) {
        final boolean inDock = dockList().contains(a.pkg);
        final boolean isHid = hidden().contains(a.pkg);
        final String[] o = {
                "Renombrar",
                "Cambiar ícono",
                "Restablecer ícono y nombre",
                inDock ? "Quitar del dock" : "Agregar al dock",
                isHid ? "Mostrar app" : "Ocultar app",
                "Información de la app",
                "Desinstalar"
        };
        dlg().setTitle(labelOf(a)).setItems(o, (dialog, which) -> {
            try {
                switch (which) {
                    case 0:
                        renameDialog(a);
                        break;
                    case 1: {
                        pendingPkg = a.pkg;
                        Intent it = new Intent(Intent.ACTION_GET_CONTENT);
                        it.setType("image/*");
                        startActivityForResult(Intent.createChooser(it, "Elegir imagen"), REQ_ICON);
                        break;
                    }
                    case 2:
                        new File(new File(getFilesDir(), "icons"), a.pkg + ".png").delete();
                        sp.edit().remove("lbl_" + a.pkg).apply();
                        cache.remove(a.pkg);
                        refreshAll();
                        break;
                    case 3: {
                        List<String> l = dockList();
                        if (inDock) {
                            l.remove(a.pkg);
                        } else {
                            if (l.size() >= 5) {
                                toast("El dock admite 5 apps");
                                break;
                            }
                            l.add(a.pkg);
                        }
                        saveDock(l);
                        refreshAll();
                        break;
                    }
                    case 4: {
                        Set<String> s = hidden();
                        if (isHid) s.remove(a.pkg);
                        else s.add(a.pkg);
                        saveHidden(s);
                        refreshAll();
                        break;
                    }
                    case 5:
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + a.pkg)));
                        break;
                    case 6:
                        startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pkg)));
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                toast("No se pudo: " + e.getMessage());
            }
        }).show();
    }

    void renameDialog(final AppItem a) {
        final EditText input = new EditText(this);
        input.setText(labelOf(a));
        input.setSingleLine(true);
        input.setTextColor(Color.WHITE);
        dlg().setTitle("Nuevo nombre").setView(input)
                .setPositiveButton("Guardar", (dialog, w) -> {
                    String t = input.getText().toString().trim();
                    if (t.isEmpty()) sp.edit().remove("lbl_" + a.pkg).apply();
                    else sp.edit().putString("lbl_" + a.pkg, t).apply();
                    refreshAll();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    void showSettings() {
        final String[] o = {
                "Cambiar fuente (.ttf)",
                "Restablecer fuente",
                "Forma de los íconos",
                showHidden ? "Dejar de ver apps ocultas" : "Ver apps ocultas",
                "Cambiar fondo de pantalla",
                "Evitar que el sistema cierre el launcher"
        };
        dlg().setTitle("Ajustes del launcher").setItems(o, (dialog, which) -> {
            try {
                switch (which) {
                    case 0: {
                        Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        it.addCategory(Intent.CATEGORY_OPENABLE);
                        it.setType("*/*");
                        startActivityForResult(it, REQ_FONT);
                        break;
                    }
                    case 1:
                        new File(getFilesDir(), "font.ttf").delete();
                        sp.edit().remove("font").apply();
                        font = loadFont();
                        rebuildUi();
                        break;
                    case 2:
                        dlg().setTitle("Forma de los íconos")
                                .setItems(new String[]{"Círculo", "Squircle (iPhone)", "Cuadrado redondeado"},
                                        (d2, w2) -> {
                                            sp.edit().putInt("shape", w2).apply();
                                            cache.clear();
                                            refreshAll();
                                        }).show();
                        break;
                    case 3:
                        showHidden = !showHidden;
                        refreshAll();
                        break;
                    case 4:
                        startActivity(Intent.createChooser(
                                new Intent(Intent.ACTION_SET_WALLPAPER), "Fondo de pantalla"));
                        break;
                    case 5:
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                        break;
                    default:
                        break;
                }
            } catch (Exception e) {
                toast("No se pudo: " + e.getMessage());
            }
        }).show();
    }

    @Override
    protected void onActivityResult(int rq, int rs, Intent data) {
        super.onActivityResult(rq, rs, data);
        if (rs != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        try {
            if (rq == REQ_ICON && pendingPkg != null) {
                Bitmap bm = decodeUri(u, 256);
                if (bm == null) throw new IOException("imagen no válida");
                int s = Math.min(bm.getWidth(), bm.getHeight());
                Bitmap sq = Bitmap.createBitmap(bm, (bm.getWidth() - s) / 2,
                        (bm.getHeight() - s) / 2, s, s);
                sq = Bitmap.createScaledBitmap(sq, 256, 256, true);
                File dir = new File(getFilesDir(), "icons");
                dir.mkdirs();
                FileOutputStream fo = new FileOutputStream(new File(dir, pendingPkg + ".png"));
                sq.compress(Bitmap.CompressFormat.PNG, 100, fo);
                fo.close();
                cache.remove(pendingPkg);
                refreshAll();
            } else if (rq == REQ_FONT) {
                File out = new File(getFilesDir(), "font.ttf");
                InputStream in = getContentResolver().openInputStream(u);
                FileOutputStream fo = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
                in.close();
                fo.close();
                try {
                    Typeface.createFromFile(out);
                } catch (Exception e) {
                    out.delete();
                    toast("Esa fuente no es válida");
                    return;
                }
                sp.edit().putString("font", out.getPath()).apply();
                font = loadFont();
                rebuildUi();
            }
        } catch (Exception e) {
            toast("No se pudo: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- adaptador

    class AppAdapter extends BaseAdapter {
        final List<AppItem> list = new ArrayList<>();
        Set<String> hid = new HashSet<>();

        void filter(String q) {
            list.clear();
            hid = hidden();
            String qq = q.trim().toLowerCase(Locale.ROOT);
            for (AppItem a : apps) {
                boolean isHidden = hid.contains(a.pkg);
                if (isHidden && !showHidden) continue;
                if (!qq.isEmpty() && !labelOf(a).toLowerCase(Locale.ROOT).contains(qq)) continue;
                list.add(a);
            }
            Collections.sort(list, (x, y) -> labelOf(x).compareToIgnoreCase(labelOf(y)));
            notifyDataSetChanged();
        }

        @Override public int getCount() { return list.size(); }
        @Override public Object getItem(int i) { return list.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            AppItem a = list.get(pos);
            LinearLayout ll;
            ImageView iv;
            TextView t;
            if (v == null) {
                ll = new LinearLayout(MainActivity.this);
                ll.setOrientation(LinearLayout.VERTICAL);
                ll.setGravity(Gravity.CENTER_HORIZONTAL);
                ll.setLayoutParams(new AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(96)));
                iv = new ImageView(MainActivity.this);
                iv.setLayoutParams(new LinearLayout.LayoutParams(icoPx, icoPx));
                t = tv(11, Color.WHITE);
                t.setSingleLine(true);
                t.setEllipsize(TextUtils.TruncateAt.END);
                t.setGravity(Gravity.CENTER);
                t.setPadding(dp(4), dp(6), dp(4), 0);
                ll.addView(iv);
                ll.addView(t);
                ll.setTag(new View[]{iv, t});
            } else {
                ll = (LinearLayout) v;
                View[] w = (View[]) ll.getTag();
                iv = (ImageView) w[0];
                t = (TextView) w[1];
            }
            iv.setImageBitmap(icon(a));
            t.setText(labelOf(a));
            t.setTypeface(font);
            ll.setAlpha(hid.contains(a.pkg) ? 0.5f : 1f);
            return ll;
        }
    }
}
