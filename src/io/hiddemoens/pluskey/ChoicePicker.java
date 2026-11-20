package io.hiddemoens.pluskey;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.Typeface;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.tabs.TabLayout;
import java.text.Collator;
import java.util.*;

final class ChoicePicker {
    interface Selected { void select(Targets.Choice choice); }

    private static class Entry {
        final Targets.Choice choice;
        final ResolveInfo app;
        final String title;
        Entry(Targets.Choice choice, ResolveInfo app) {
            this(choice, app, choice.label);
        }
        Entry(Targets.Choice choice, ResolveInfo app, String title) {
            this.choice = choice;
            this.app = app;
            this.title = title;
        }
    }

    private static final class Heading extends Entry {
        Heading(String title) { super(new Targets.Choice(title, null), null); }
    }

    private static final class AppGroup extends Entry {
        final ApplicationInfo info;
        final List<Entry> children = new ArrayList<>();
        final Set<String> targets = new HashSet<>();
        boolean hasLocked, hasTiles;

        AppGroup(ApplicationInfo info, PackageManager pm) {
            super(new Targets.Choice(info.loadLabel(pm).toString(), info.packageName), null);
            this.info = info;
        }

        void add(Targets.Choice choice, ResolveInfo source, String title) {
            if (!targets.add(choice.target)) return;
            children.add(new Entry(choice, source, title));
            hasLocked |= Policy.shouldWakeScreen(choice.target);
            hasTiles |= Options.isTile(choice.target);
        }

        boolean matches(String query) {
            if (title.toLowerCase(Locale.ROOT).contains(query) || info.packageName.toLowerCase(Locale.ROOT).contains(query)) return true;
            for (Entry child : children) if (child.title.toLowerCase(Locale.ROOT).contains(query)) return true;
            return false;
        }
    }

    private final Context context;
    private final Selected selected;
    private final List<Entry> visible = new ArrayList<>();
    private final List<Entry> actions = new ArrayList<>();
    private final LruCache<String, Drawable> icons = new LruCache<>(32);
    private List<AppGroup> apps;
    private AppGroup openedApp;
    private int appsPosition;
    private TextView legend, tileNote;
    private BottomSheetDialog sheet;
    private EditText search;
    private ListView list;
    private TextView empty;
    private String current, gestureTitle, playing;
    private boolean choosingFan;
    private final Set<String> fanSelection = new LinkedHashSet<>();
    private Button saveFan;
    private int mediaStep, mainPosition;
    private TextView gesture;
    private TabLayout tabs;
    private ImageButton back;
    private View timingButton, removeButton;
    private boolean browsingApps, loading;
    private Thread appLoader;
    private final Rows rows = new Rows();

    ChoicePicker(Context context, Selected selected) {
        this.context = context;
        this.selected = selected;
    }

    void show(String title, String unsetLabel, String current, Runnable timing, Runnable remove) {
        this.current = current;
        gestureTitle = title;
        if (unsetLabel != null) actions.add(new Entry(new Targets.Choice(unsetLabel, null), null));
        for (int i = 0; i < Targets.ACTIONS.length; i++) {
            if (Targets.ACTIONS[i].equals("action:pause_torch")) continue;
            if (Targets.ACTIONS[i].equals("action:torch") && !context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) continue;
            actions.add(new Entry(new Targets.Choice(Targets.LABELS[i], Targets.ACTIONS[i]), null));
        }
        actions.add(new Entry(new Targets.Choice("If media is playing…", "media:"), null));
        actions.add(new Entry(new Targets.Choice("App fan", "fan:"), null));
        sheet = new BottomSheetDialog(context);
        sheet.setDismissWithAnimation(true);
        sheet.setOnDismissListener(dialog -> {
            if (appLoader != null) appLoader.interrupt();
        });
        LinearLayout body = Ui.column(context);
        body.setPadding(dp(18), dp(12), dp(18), dp(14));
        body.setFocusableInTouchMode(true);
        View handle = new View(context);
        handle.setBackground(rounded(0xffa6a19d, 2));
        LinearLayout.LayoutParams grip = new LinearLayout.LayoutParams(dp(34), dp(4));
        grip.gravity = Gravity.CENTER_HORIZONTAL;
        grip.bottomMargin = dp(12);
        body.addView(handle, grip);
        LinearLayout heading = Ui.actions(context, body);
        gesture = new TextView(context);
        gesture.setText(title);
        gesture.setTextSize(28);
        gesture.setTypeface(null, Typeface.BOLD);
        heading.addView(gesture, new LinearLayout.LayoutParams(0, -2, 1));
        back = Ui.navigationButton(context);
        back.setContentDescription("Back to gestures");
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setImageResource(R.drawable.ic_back);
        back.setOnClickListener(v -> goBack());
        sheet.setOnKeyListener((dialog, key, event) -> {
            if (key != android.view.KeyEvent.KEYCODE_BACK || mediaStep == 0 && openedApp == null && !choosingFan) return false;
            if (event.getAction() == android.view.KeyEvent.ACTION_UP) goBack();
            return true;
        });
        heading.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        int accent = Ui.color(context, androidx.appcompat.R.attr.colorPrimary);
        int surface = Ui.color(context, com.google.android.material.R.attr.colorSurfaceContainer);
        tabs = new TabLayout(context);
        tabs.setBackground(rounded(surface, 24));
        tabs.setTabMode(TabLayout.MODE_FIXED);
        tabs.setTabGravity(TabLayout.GRAVITY_FILL);
        tabs.setTabIndicatorFullWidth(true);
        tabs.setTabRippleColor(null);
        tabs.setSelectedTabIndicator(new InsetDrawable(rounded(accent, 22), dp(4)));
        tabs.setSelectedTabIndicatorColor(accent);
        tabs.setSelectedTabIndicatorGravity(TabLayout.INDICATOR_GRAVITY_STRETCH);
        tabs.setTabTextColors(Ui.color(context, com.google.android.material.R.attr.colorOnSurfaceVariant),
            Ui.color(context, com.google.android.material.R.attr.colorOnPrimary));
        tabs.addTab(tabs.newTab().setText("Actions"));
        tabs.addTab(tabs.newTab().setText("Apps"));
        LinearLayout.LayoutParams segment = new LinearLayout.LayoutParams(-1, dp(48));
        segment.topMargin = dp(6);
        segment.bottomMargin = dp(12);
        body.addView(tabs, segment);
        legend = Ui.text(context, body, "Offers an action you can use while locked", 14, false);
        Drawable lock = context.getDrawable(R.drawable.ic_locked_action).mutate();
        lock.setTint(accent);
        legend.setCompoundDrawablesRelativeWithIntrinsicBounds(lock, null, null, null);
        legend.setCompoundDrawablePadding(dp(8));
        tileNote = Ui.text(context, body, "Only tiles already in Android Quick Settings are shown.", 14, false);
        search = new EditText(context);
        search.setSingleLine();
        search.setHint("Search apps");
        search.setTextSize(16);
        search.setBackground(rounded(surface, 16));
        search.setPadding(dp(16), 0, dp(16), 0);
        Drawable lens = context.getDrawable(android.R.drawable.ic_menu_search).mutate();
        lens.setTint(Ui.color(context, com.google.android.material.R.attr.colorOnSurfaceVariant));
        lens.setBounds(0, 0, dp(20), dp(20));
        search.setCompoundDrawablesRelative(lens, null, null, null);
        search.setCompoundDrawablePadding(dp(10));
        LinearLayout.LayoutParams field = new LinearLayout.LayoutParams(-1, dp(48));
        field.bottomMargin = dp(10);
        body.addView(search, field);
        list = new ListView(context);
        list.setNestedScrollingEnabled(true);
        sheet.getBehavior().setDraggableOnNestedScroll(false);
        list.setBackground(rounded(surface, 20));
        list.setClipToOutline(true);
        list.setDivider(null);
        list.setDrawSelectorOnTop(true);
        list.setAdapter(rows);
        int height = Math.min(dp(340), context.getResources().getDisplayMetrics().heightPixels / 3);
        LinearLayout.LayoutParams results = new LinearLayout.LayoutParams(-1, height);
        results.weight = 1;
        body.addView(list, results);
        empty = Ui.text(context, body, "", 16, true);
        saveFan = Ui.button(context, body, "Save", true, v -> {
            String target = "fan:" + String.join(",", fanSelection);
            if (Options.fanApps(target) != null) finish(new Targets.Choice(Targets.label(context, target), target));
        });
        if (timing != null) timingButton = Ui.button(context, body, "Press timing", false, v -> { sheet.dismiss(); timing.run(); });
        if (remove != null) removeButton = Ui.button(context, body, "Remove pattern", false, v -> { sheet.dismiss(); remove.run(); });
        list.setOnItemClickListener((parent, view, position, id) -> pick(visible.get(position)));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void afterTextChanged(Editable value) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { refresh(); }
        });
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            public void onTabSelected(TabLayout.Tab tab) {
                browsingApps = tab.getPosition() == 1;
                if (browsingApps && apps == null && !loading) loadApps();
                refresh();
                list.setSelection(0);
            }
            public void onTabUnselected(TabLayout.Tab tab) {}
            public void onTabReselected(TabLayout.Tab tab) {}
        });
        sheet.setContentView(body);
        refresh();
        sheet.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        sheet.show();
    }

    private void loadApps() {
        loading = true;
        appLoader = new Thread(() -> {
            PackageManager pm = context.getPackageManager();
            Map<String, AppGroup> groups = new HashMap<>();
            addHandlers(groups, new Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE), Options.SECURE_CAMERA, "Secure camera");
            addHandlers(groups, new Intent(Intent.ACTION_CREATE_NOTE), "note:", "Create note");
            addHandlers(groups, new Intent(android.speech.RecognizerIntent.ACTION_VOICE_SEARCH_HANDS_FREE), "voice:", "Hands-free voice");
            addHandlers(groups, new Intent(Intent.ACTION_CREATE_SHORTCUT), "configure:", "Configure shortcut");
            Set<String> scanned = new HashSet<>();
            for (ResolveInfo app : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.GET_META_DATA)) {
                if (Thread.currentThread().isInterrupted()) return;
                if (!app.activityInfo.exported || context.getPackageName().equals(app.activityInfo.packageName)) continue;
                String owner = app.activityInfo.packageName;
                String target = new ComponentName(owner, app.activityInfo.name).flattenToString();
                if (!Options.validComponent(target)) continue;
                AppGroup group = group(groups, app.activityInfo.applicationInfo);
                String name = app.loadLabel(pm).toString();
                group.add(new Targets.Choice(name, target), app, name.equals(group.title) ? "Open app" : "Open " + name);
                if (scanned.add(owner)) {
                    for (Targets.Choice locked : Targets.lockedActivities(context, group.info, group.title, group.targets.contains(Options.SECURE_CAMERA + owner)))
                        group.add(locked, app, locked.label);
                }
                for (Targets.Choice shortcut : Targets.shortcuts(context, app)) group.add(shortcut, app, shortcut.label);
            }
            if (Thread.currentThread().isInterrupted()) return;
            List<ResolveInfo> tiles = QuickTiles.discover(context, null);
            Set<String> pinned = tiles.isEmpty() ? Collections.emptySet() : QuickTiles.pinned(context);
            for (ResolveInfo tile : tiles) {
                if (Thread.currentThread().isInterrupted()) return;
                String target = "tile:" + new ComponentName(tile.serviceInfo.packageName, tile.serviceInfo.name).flattenToString();
                if (!Options.isTile(target) || !pinned.contains(target.substring(5))) continue;
                AppGroup group = group(groups, tile.serviceInfo.applicationInfo);
                String title = tile.loadLabel(pm).toString();
                group.add(new Targets.Choice(title, target), tile, title);
            }
            List<AppGroup> found = new ArrayList<>(groups.values());
            Collator collator = Collator.getInstance();
            found.sort(Comparator.comparing((AppGroup group) -> group.title, collator).thenComparing(group -> group.info.packageName));
            for (AppGroup group : found) group.children.sort(Comparator.comparing((Entry entry) -> entry.title, collator).thenComparing(entry -> entry.choice.target));
            context.getMainExecutor().execute(() -> {
                if (!sheet.isShowing()) return;
                appLoader = null;
                apps = found;
                loading = false;
                refresh();
            });
        }, "PlusKey-app-picker");
        appLoader.start();
    }

    private AppGroup group(Map<String, AppGroup> groups, ApplicationInfo info) {
        return groups.computeIfAbsent(info.packageName, owner -> new AppGroup(info, context.getPackageManager()));
    }

    private void addHandlers(Map<String, AppGroup> groups, Intent intent, String prefix, String label) {
        PackageManager pm = context.getPackageManager();
        for (ResolveInfo handler : pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)) {
            android.content.pm.ActivityInfo info = handler.activityInfo;
            if (!info.exported || context.getPackageName().equals(info.packageName) || info.permission != null &&
                context.checkSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED) continue;
            boolean configure = prefix.equals("configure:");
            String target = prefix + (configure ? new ComponentName(info.packageName, info.name).flattenToString() : info.packageName);
            if (!(configure ? Options.validComponent(target.substring(prefix.length())) : Options.validTarget(target))) continue;
            AppGroup group = group(groups, info.applicationInfo);
            String title = configure ? label + " · " + handler.loadLabel(pm) : label;
            group.add(new Targets.Choice(group.title + " · " + title, target), handler, title);
        }
    }

    private void refresh() {
        search.setVisibility((browsingApps || choosingFan) && openedApp == null ? View.VISIBLE : View.GONE);
        legend.setVisibility(browsingApps && (openedApp != null ? openedApp.hasLocked : apps != null && apps.stream().anyMatch(group -> group.hasLocked)) ? View.VISIBLE : View.GONE);
        tileNote.setVisibility(openedApp != null && openedApp.hasTiles ? View.VISIBLE : View.GONE);
        visible.clear();
        saveFan.setVisibility(choosingFan ? View.VISIBLE : View.GONE);
        if (choosingFan) {
            if (apps != null) addFanRows();
            saveFan.setText("Save " + fanSelection.size() + " apps");
            saveFan.setEnabled(fanSelection.size() >= 2);
        } else if (!browsingApps) {
            Map<String, List<Entry>> categories = new LinkedHashMap<>();
            for (Entry entry : actions) {
                String target = entry.choice.target;
                if (mediaStep != 0 && !Options.builtIn(target)) continue;
                if (target == null) visible.add(entry);
                else categories.computeIfAbsent(actionCategory(target), key -> new ArrayList<>()).add(entry);
            }
            for (Map.Entry<String, List<Entry>> category : categories.entrySet()) {
                visible.add(new Heading(category.getKey()));
                visible.addAll(category.getValue());
            }
        } else if (openedApp != null) {
            for (String section : new String[]{"Launch", "Lock screen", "Shortcuts", "Quick Settings"}) {
                boolean first = true;
                for (Entry entry : openedApp.children) {
                    if (!section.equals(appCategory(entry.choice.target))) continue;
                    if (first) visible.add(new Heading(section));
                    first = false;
                    visible.add(entry);
                }
            }
        }
        else if (apps != null) {
            String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            for (AppGroup group : apps) if (group.matches(query)) visible.add(group);
        }
        empty.setText(loading ? "Loading apps…" : "No matching apps");
        empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
        list.setVisibility(visible.isEmpty() ? View.GONE : View.VISIBLE);
        rows.notifyDataSetChanged();
    }

    private void addFanRows() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        Map<String, Entry> picked = new HashMap<>();
        List<Entry> remaining = new ArrayList<>();
        for (AppGroup group : apps) {
            boolean packageMatches = group.info.packageName.toLowerCase(Locale.ROOT).contains(query);
            for (Entry entry : group.children) {
                String target = entry.choice.target;
                if (!Options.validComponent(target)) continue;
                if (fanSelection.contains(target)) {
                    picked.put(target, new Entry(entry.choice, entry.app));
                } else if (packageMatches || entry.choice.label.toLowerCase(Locale.ROOT).contains(query)) {
                    remaining.add(new Entry(entry.choice, entry.app));
                }
            }
        }
        fanSelection.retainAll(picked.keySet());
        if (!fanSelection.isEmpty()) {
            visible.add(new Heading("Selected apps"));
            for (String target : fanSelection) visible.add(picked.get(target));
            if (!remaining.isEmpty()) visible.add(new Heading("Available apps"));
        }
        visible.addAll(remaining);
    }

    private static String actionCategory(String target) {
        switch (target) {
            case "action:sound_cycle": case "action:vibrate_toggle": case "action:silent_toggle": case "action:silent":
            case "action:vibrate": case "action:ring": case "action:volume_up": case "action:volume_down": case "action:dnd_hour": return "Sound";
            case "action:media_toggle": case "action:media_pause": case "action:media_next": case "action:media_previous": case "media:": return "Media";
            case "action:wifi_toggle": case "action:data_toggle": case "action:data_sim": case "action:data_slot1": case "action:data_slot2": case "action:hotspot": return "Connectivity";
            default: return "Device";
        }
    }

    private static String appCategory(String target) {
        if (Options.isTile(target)) return "Quick Settings";
        if (Policy.shouldWakeScreen(target)) return "Lock screen";
        if (target.startsWith("configure:") || target.startsWith("shortcut:")) return "Shortcuts";
        return "Launch";
    }

    private void pick(Entry entry) {
        if (choosingFan) {
            String target = entry.choice.target;
            if (!fanSelection.remove(target)) {
                if (fanSelection.size() == Options.MAX_FAN_APPS) {
                    Toast.makeText(context, "Choose up to " + Options.MAX_FAN_APPS + " apps", Toast.LENGTH_SHORT).show();
                    return;
                }
                fanSelection.add(target);
            }
            refresh();
            return;
        }
        if ("fan:".equals(entry.choice.target)) {
            fanSelection.clear();
            String[] saved = Options.fanApps(current);
            if (saved != null) Collections.addAll(fanSelection, saved);
            choosingFan = true;
            showApp(null);
            gesture.setText("App fan");
            search.setHint("Choose 2–6 apps");
            tabs.setVisibility(View.GONE);
            if (timingButton != null) timingButton.setVisibility(View.GONE);
            if (removeButton != null) removeButton.setVisibility(View.GONE);
            if (apps == null && !loading) loadApps();
            return;
        }
        if (mediaStep == 1) {
            playing = entry.choice.target;
            showStep(2);
            return;
        }
        if (mediaStep == 2) {
            String target = "media:" + playing + "," + entry.choice.target;
            finish(new Targets.Choice(Targets.label(context, target), target));
            return;
        }
        if ("media:".equals(entry.choice.target)) {
            mainPosition = list.getFirstVisiblePosition();
            String[] saved = Options.mediaBranches(current);
            playing = saved == null ? null : saved[0];
            showStep(1);
            return;
        }
        if (entry instanceof AppGroup) {
            appsPosition = list.getFirstVisiblePosition();
            context.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(search.getWindowToken(), 0);
            showApp((AppGroup) entry);
            return;
        }
        finish(entry.choice);
    }

    private void goBack() {
        if (choosingFan) {
            choosingFan = false;
            search.setText("");
            search.setHint("Search apps");
            showApp(null);
        } else if (openedApp != null) showApp(null);
        else if (mediaStep != 0) showStep(mediaStep - 1);
        else sheet.cancel();
    }

    private void showApp(AppGroup group) {
        openedApp = group;
        gesture.setText(group == null ? gestureTitle : group.title);
        back.setContentDescription(group == null ? "Back to gestures" : "Back to apps");
        tabs.setVisibility(group == null ? View.VISIBLE : View.GONE);
        if (timingButton != null) timingButton.setVisibility(group == null ? View.VISIBLE : View.GONE);
        if (removeButton != null) removeButton.setVisibility(group == null ? View.VISIBLE : View.GONE);
        refresh();
        list.setSelection(group == null ? appsPosition : 0);
        gesture.announceForAccessibility(gesture.getText());
    }

    private void showStep(int step) {
        boolean forward = step > mediaStep;
        mediaStep = step;
        gesture.setText(step == 0 ? gestureTitle : step == 1 ? "If media is playing" : "Otherwise");
        back.setContentDescription(step == 0 ? "Back to gestures" : step == 1 ? "Back to actions" : "Back to playback action");
        tabs.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        if (timingButton != null) timingButton.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        if (removeButton != null) removeButton.setVisibility(step == 0 ? View.VISIBLE : View.GONE);
        refresh();
        int position = step == 0 ? mainPosition : 0;
        String selected = selectedTarget();
        for (int i = 0; step != 0 && i < visible.size(); i++) {
            if (!(visible.get(i) instanceof Heading) && Objects.equals(visible.get(i).choice.target, selected)) position = i;
        }
        list.setSelection(position);
        list.animate().cancel();
        list.setTranslationX((forward ? 1 : -1) * list.getWidth());
        list.animate().translationX(0).setDuration(180).start();
        gesture.announceForAccessibility(gesture.getText());
    }

    private String selectedTarget() {
        if (mediaStep == 1) return playing;
        String[] saved = Options.mediaBranches(current);
        if (mediaStep == 2) return saved == null ? null : saved[1];
        if (current != null && current.startsWith("hold:") && Options.canHold(current.substring(5))) return current.substring(5);
        if (Options.fanApps(current) != null) return "fan:";
        return saved == null ? current : "media:";
    }

    private void finish(Targets.Choice choice) {
        sheet.dismiss();
        selected.select(choice);
    }

    static int actionIcon(String target) {
        if (target == null) return android.R.drawable.ic_menu_close_clear_cancel;
        if (target.startsWith("hold:") && Options.canHold(target.substring(5))) target = target.substring(5);
        if (Options.isTile(target)) return android.R.drawable.ic_menu_preferences;
        if (target.startsWith("fan:")) return R.drawable.ic_app_fan;
        if (target.startsWith("media:")) return R.drawable.ic_smart;
        switch (target) {
            case "action:record": return android.R.drawable.ic_btn_speak_now;
            case "action:assistant": return android.R.drawable.ic_btn_speak_now;
            case "action:previous_app": return R.drawable.ic_recent;
            case "action:pause_torch": return R.drawable.ic_pause_torch;
            case "action:media_pause": return R.drawable.ic_pause;
            case "action:hotspot": return R.drawable.ic_hotspot;
            case "action:dnd_hour": return R.drawable.ic_dnd;
            case "action:data_slot1": return R.drawable.ic_sim1;
            case "action:data_slot2": return R.drawable.ic_sim2;
            case "action:wifi_toggle": return R.drawable.ic_wifi;
            case "action:data_toggle": return R.drawable.ic_data;
            case "action:data_sim": return R.drawable.ic_sim;
            case "action:torch": return R.drawable.ic_flashlight;
            case "action:screenshot": return R.drawable.ic_capture;
            case "action:lock": return android.R.drawable.ic_lock_lock;
            case "action:notifications": return android.R.drawable.ic_dialog_info;
            case "action:quick_settings": return android.R.drawable.ic_menu_preferences;
            case "action:media_toggle": return android.R.drawable.ic_media_play;
            case "action:media_next": return android.R.drawable.ic_media_next;
            case "action:media_previous": return android.R.drawable.ic_media_previous;
            case "action:silent": return android.R.drawable.ic_lock_silent_mode;
            default: return android.R.drawable.ic_lock_silent_mode_off;
        }
    }

    static boolean canHold(Context context, String target) {
        if (!Options.canHold(target)) return false;
        return !Options.isTile(target) || QuickTiles.toggleable(context, ComponentName.unflattenFromString(target.substring(5)));
    }

    private final class Rows extends BaseAdapter {
        public int getCount() { return visible.size(); }
        public Entry getItem(int index) { return visible.get(index); }
        public long getItemId(int index) { return index; }
        public boolean areAllItemsEnabled() { return false; }
        public boolean isEnabled(int index) { return !(getItem(index) instanceof Heading); }
        public int getViewTypeCount() { return 2; }
        public int getItemViewType(int index) { return isEnabled(index) ? 0 : 1; }
        public View getView(int index, View recycled, ViewGroup parent) {
            Entry entry = getItem(index);
            if (entry instanceof Heading) {
                TextView heading = recycled == null ? new TextView(context) : (TextView) recycled;
                heading.setText(entry.title);
                heading.setTextSize(14);
                heading.setTypeface(null, Typeface.BOLD);
                heading.setPadding(dp(16), dp(12), dp(16), dp(4));
                heading.setAccessibilityHeading(true);
                return heading;
            }
            LinearLayout row = (LinearLayout) recycled;
            if (row == null) {
                row = new LinearLayout(context);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(52));
                row.setPadding(dp(16), dp(4), dp(16), dp(4));
                row.addView(new ImageView(context), new LinearLayout.LayoutParams(dp(28), dp(28)));
                TextView label = new TextView(context);
                label.setPadding(dp(14), 0, dp(8), 0);
                label.setTextSize(16);
                row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
                TextView check = new TextView(context);
                check.setText("✓");
                check.setTextSize(21);
                check.setTextColor(Ui.color(context, androidx.appcompat.R.attr.colorPrimary));
                row.addView(check);
                ImageView lock = new ImageView(context);
                lock.setImageResource(R.drawable.ic_locked_action);
                lock.setColorFilter(Ui.color(context, androidx.appcompat.R.attr.colorPrimary));
                row.addView(lock, new LinearLayout.LayoutParams(dp(24), dp(24)));
                TextView badge = new TextView(context);
                badge.setText("QS tile");
                badge.setTextSize(12);
                badge.setTextColor(Ui.color(context, com.google.android.material.R.attr.colorOnSecondaryContainer));
                badge.setBackground(rounded(Ui.color(context, com.google.android.material.R.attr.colorSecondaryContainer), 8));
                badge.setPadding(dp(6), dp(2), dp(6), dp(2));
                row.addView(badge);
            }
            AppGroup group = entry instanceof AppGroup ? (AppGroup) entry : null;
            ImageView icon = (ImageView) row.getChildAt(0);
            if (entry.app == null && group == null) {
                icon.setImageResource(actionIcon(entry.choice.target));
                icon.setColorFilter(Ui.color(context, com.google.android.material.R.attr.colorOnSurface));
            } else {
                icon.clearColorFilter();
                Drawable drawable = icons.get(entry.choice.target);
                if (drawable == null) {
                    drawable = group == null ? entry.app.loadIcon(context.getPackageManager()) : group.info.loadIcon(context.getPackageManager());
                    if (drawable != null) icons.put(entry.choice.target, drawable);
                }
                icon.setImageDrawable(drawable);
                if (Options.isTile(entry.choice.target) && entry.app.serviceInfo.icon != 0)
                    icon.setColorFilter(Ui.color(context, androidx.appcompat.R.attr.colorPrimary));
            }
            boolean supportsLocked = group == null ? Policy.shouldWakeScreen(entry.choice.target) : group.hasLocked;
            TextView label = (TextView) row.getChildAt(1);
            label.setText(entry.title);
            row.getChildAt(3).setVisibility(supportsLocked ? View.VISIBLE : View.GONE);
            row.getChildAt(4).setVisibility((group != null ? group.hasTiles : Options.isTile(entry.choice.target)) ? View.VISIBLE : View.GONE);
            label.setContentDescription(supportsLocked ? entry.title + ", supports locked use" : null);
            boolean checked = choosingFan ? fanSelection.contains(entry.choice.target) : group == null ? Objects.equals(selectedTarget(), entry.choice.target) : group.targets.contains(selectedTarget());
            row.setBackgroundColor(checked ? 0x18ff902f : android.graphics.Color.TRANSPARENT);
            row.getChildAt(2).setVisibility(checked ? View.VISIBLE : View.INVISIBLE);
            row.setStateDescription(checked ? "Selected" : null);
            return row;
        }
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(dp(radius));
        shape.setColor(color);
        return shape;
    }

    private int dp(int n) { return Ui.dp(context, n); }
}
