package io.hiddemoens.pluskey;

import android.content.*;
import android.content.pm.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.widget.NestedScrollView;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDragHandleView;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.slider.TickVisibilityMode;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

public final class MainActivity extends AppCompatActivity {
    private static final long MIN_TICK_SPACING_MS = 55;
    private static final String LONG_PRESS_DEFAULT_SET = "long_press_default_set";
    private static final String LONG_PRESS_SETUP_HINT = "Set the OnePlus action to Do nothing to let Plus Key handle long press and remove the ‘Press and hold…’ warning.";
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Mailbox mailbox;private WirelessController wireless;private SetupSheet setup;
    private final Map<Integer,TextView> pressNames = new LinkedHashMap<>();
    private final Map<Integer,ImageView> pressIcons = new HashMap<>();
    private final Map<Integer,MaterialButton> pressPreviews = new HashMap<>();
    private LinearLayout customRows;
    private androidx.appcompat.app.AlertDialog captureDialog;
    private boolean helperReady;
    private View longPressSetupCard;
    private FrameLayout shelfPages;
    private NestedScrollView shelfScroll;
    private LinearLayout gesturesPage;
    private ImageButton shelfNav;
    private int previousScroll;
    private final androidx.activity.OnBackPressedCallback settingsBack =
        new androidx.activity.OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { returnToGestures(); }
        };
    private static final int STATUS_GREEN = 0xff63dc96;
    private static final int STATUS_AMBER = 0xffffb454;
    private static final int STATUS_RED = 0xffff7975;
    private static final int STATUS_GRAY = 0xffa6a6aa;
    private final GradientDrawable statusDot = new GradientDrawable();
    private TextView status,detail;
    private MaterialButton start,stop,finishSetup;private LinearProgressIndicator progress;
    private boolean resumed,autoAttempt,shizukuAttempt;
    private final Shizuku.OnRequestPermissionResultListener shizukuPermission = (code, result) -> {
        if (code != ShizukuConnection.PERMISSION_REQUEST || isDestroyed()) return;
        if (result == PackageManager.PERMISSION_GRANTED) startHelper();
        else toast("Shizuku permission denied");
        setup.refresh();
    };
    private Updates updates;
    private boolean resumePairingAfterPermission;
    private MailboxEvents.Subscription statusObserver;
    private int resumeGeneration;
    private String currentStatus;
    private boolean currentVersion;
    private final Runnable statusChanged = () -> refresh(false);
    private final Runnable sessionEnded = () -> refresh(true);
    private final Runnable connectionChanged = () -> {
        if (!resumed) return;
        renderStatus(currentStatus, currentVersion);
        setup.refresh();
    };
    private final Shizuku.OnBinderReceivedListener binderReceived = () -> {
        if (resumed) {
            autoConnect();
            connectionChanged.run();
        }
    };
    private final Shizuku.OnBinderDeadListener binderDead = () -> {
        shizukuAttempt = false;
        connectionChanged.run();
    };
    private int configuringGesture;
    private String configuringPackage;
    private final androidx.activity.result.ActivityResultLauncher<Intent> shortcutSetup =
        registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
            String owner = configuringPackage;
            configuringPackage = null;
            if (owner == null || result.getResultCode() != RESULT_OK || result.getData() == null) return;
            try {
                Targets.Choice choice = ConfiguredShortcut.save(this, owner, result.getData());
                saveChoice(configuringGesture, choice);
            } catch (RuntimeException error) {
                toast("App did not return a usable shortcut");
            }
        });

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("shortcutOwner", configuringPackage);
        state.putInt("shortcutGesture", configuringGesture);
    }
    protected void onCreate(Bundle saved){
        super.onCreate(saved);
        if (saved != null) {
            configuringPackage = saved.getString("shortcutOwner");
            configuringGesture = saved.getInt("shortcutGesture");
        }
        File mailboxDir = getExternalFilesDir(null);
        updates=new Updates(this);mailbox=new Mailbox(mailboxDir);wireless=WirelessController.get(this);setup=new SetupSheet(this,wireless);
        Shizuku.addRequestPermissionResultListener(shizukuPermission);
        try{if(mailbox.selected(KeyPress.SHORT)==null && getPackageManager().getLaunchIntentForPackage("com.google.ar.lens")!=null)mailbox.select(KeyPress.SHORT, Helper.LENS);}catch(Exception ignored){}
        if (mailboxDir != null) initializeLongPressDefault();
        CoordinatorLayout page = new CoordinatorLayout(this);
        FrameLayout header = new FrameLayout(this);
        page.addView(header, new CoordinatorLayout.LayoutParams(-1, 1));
        FrameLayout photoLayer = new FrameLayout(this);
        header.addView(photoLayer, new FrameLayout.LayoutParams(-1, -1));
        ImageView handset = new ImageView(this);
        handset.setScaleType(ImageView.ScaleType.CENTER_CROP);
        handset.setImageResource(R.drawable.plus_key_header);
        photoLayer.addView(handset, new FrameLayout.LayoutParams(-1, -1));
        ImageView softPhoto = new ImageView(this);
        softPhoto.setScaleType(ImageView.ScaleType.CENTER_CROP);
        softPhoto.setImageResource(R.drawable.plus_key_header_soft);
        softPhoto.setAlpha(0f);
        softPhoto.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        photoLayer.addView(softPhoto, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout heading = Ui.column(this);
        heading.setPadding(dp(20), dp(12), dp(20), dp(16));
        GradientDrawable titleShade = new GradientDrawable();
        titleShade.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        titleShade.setGradientCenter(0f, 0f);
        titleShade.setGradientRadius(dp(240));
        titleShade.setColors(new int[] {0x86000000, 0x00000000});
        View scrim = new View(this);
        scrim.setBackground(titleShade);
        scrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        header.addView(heading, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout top = Ui.actions(this, heading);
        TextView title=new TextView(this);title.setText("Plus Key");title.setTextSize(36);title.setTypeface(null,Typeface.BOLD);title.setTextColor(Ui.color(this,com.google.android.material.R.attr.colorOnSurface));top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        status = Ui.text(this, heading, "Checking…", 16, false);
        statusDot.setShape(GradientDrawable.OVAL);
        statusDot.setSize(dp(8), dp(8));
        statusDot.setColor(STATUS_AMBER);
        status.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        status.setCompoundDrawablePadding(dp(8));
        status.setCompoundDrawablesRelativeWithIntrinsicBounds(statusDot, null, null, null);
        LinearLayout root = Ui.column(this);
        root.setPadding(dp(16), 0, dp(16), dp(28));
        root.setClipChildren(false);
        root.setClipToPadding(false);

        LinearLayout first = gestureRow(root);
        addPressCard(first, KeyPress.SHORT, "Short press", null, "●");
        addPressCard(first, KeyPress.DOUBLE, "Double press", "Off", "● ●");
        Ui.equal(this, first);
        LinearLayout second = gestureRow(root);
        addPressCard(second, KeyPress.TRIPLE, "Triple press", "Off", "● ● ●");
        addPressCard(second, KeyPress.LONG, "Long press", "Managed by OnePlus", "");
        Ui.equal(this, second);
        customRows = Ui.column(this);
        root.addView(customRows);
        Ui.button(this,root,"Record pattern",false,v -> recordPattern());
        LinearLayout longPressSetup = Ui.card(this, root);
        longPressSetupCard = (View) longPressSetup.getParent();
        longPressSetupCard.setVisibility(View.GONE);
        Ui.text(this, longPressSetup, "Turn off OnePlus action", 22, false);
        Ui.text(this, longPressSetup, LONG_PRESS_SETUP_HINT, 14, true);
        Ui.button(this, longPressSetup, "Open OnePlus settings", true, v -> onePlusSettings());
        LinearLayout connection=Ui.card(this,root);detail=Ui.text(this,connection,"",14,true);
        progress=new LinearProgressIndicator(this);progress.setIndeterminate(true);progress.setVisibility(View.GONE);connection.addView(progress,new LinearLayout.LayoutParams(-1,dp(6)));
        start=Ui.button(this,connection,"Start",true,v->startHelper());
        stop=Ui.button(this,connection,"Stop",false,v->{if(wireless.busy.get())return;try{wireless.prefs().edit().putBoolean("user_stopped",true).apply();mailbox.requestStop();setStatus("Stopping…", STATUS_AMBER);}catch(IOException error){toast("Could not stop. Try again.");}});
        finishSetup=Ui.button(this,root,"Finish setup",true,v->setup.show());
        attachGestureShelf(page, header, photoLayer, softPhoto, root, second);
        FrameLayout window = new FrameLayout(this);
        window.addView(page, new FrameLayout.LayoutParams(-1, -1));
        window.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(window);
    }

    private void attachGestureShelf(CoordinatorLayout page, View header, View photoLayer, ImageView softPhoto, LinearLayout content, View lastRow) {
        LinearLayout shelf = Ui.column(this);
        GradientDrawable surface = new GradientDrawable();
        float radius = dp(30);
        surface.setCornerRadii(new float[] {radius, radius, radius, radius, 0, 0, 0, 0});
        surface.setColor(Ui.color(this, com.google.android.material.R.attr.colorSurface));
        shelf.setBackground(surface);
        shelf.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight() + (int) radius, radius);
            }
        });
        shelf.setElevation(dp(3));
        BottomSheetDragHandleView handle = new BottomSheetDragHandleView(this);
        FrameLayout toolbar = new FrameLayout(this);
        toolbar.addView(handle, new FrameLayout.LayoutParams(dp(64), -1, Gravity.CENTER));
        shelfNav = Ui.navigationButton(this);
        updateShelfNavigation(false);
        shelfNav.setOnClickListener(v -> {
            if (settingsBack.isEnabled()) returnToGestures();
            else showSettings();
        });
        FrameLayout.LayoutParams nav = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.END);
        nav.setMarginEnd(dp(12));
        toolbar.addView(shelfNav, nav);
        shelf.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(48)));
        shelfScroll = new NestedScrollView(this);
        shelfPages = new FrameLayout(this);
        gesturesPage = content;
        shelfPages.addView(content);
        shelfScroll.addView(shelfPages);
        shelf.addView(shelfScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        getOnBackPressedDispatcher().addCallback(this, settingsBack);
        BottomSheetBehavior<LinearLayout> behavior = new BottomSheetBehavior<>();
        behavior.setHideable(false);
        CoordinatorLayout.LayoutParams layout = new CoordinatorLayout.LayoutParams(-1, -1);
        layout.setBehavior(behavior);
        page.addView(shelf, layout);
        // Draw into the transparent margin so the shadow follows the rounded edge
        // during both a drag and its spring release.
        android.graphics.Paint shadow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Ui.color(this, com.google.android.material.R.attr.colorSurface));
        int shadowMargin = dp(84);
        shadow.setShadowLayer(dp(36), 0, -dp(8), 0x87000000);
        View castShadow = new View(this) {
            @Override protected void onDraw(android.graphics.Canvas canvas) {
                float top = shadowMargin;
                canvas.drawRoundRect(dp(1), top, getWidth() - dp(1), getHeight() + radius,
                    radius, radius, shadow);
            }
        };
        castShadow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        castShadow.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        page.addView(castShadow, 1, new CoordinatorLayout.LayoutParams(-1, shadowMargin + dp(32)));
        Runnable positionEffects = () -> {
            castShadow.setTranslationY(shelf.getY() - shadowMargin);
            int travel = shelf.getHeight() - behavior.getPeekHeight();
            if (travel > 0) {
                float distance = page.getHeight() - behavior.getPeekHeight() - shelf.getY();
                float fraction = Math.max(0f, Math.min(1f, distance / travel));
                float progress = fraction * fraction * (3f - 2f * fraction);
                softPhoto.setAlpha(progress);
                float scale = 1f + .06f * (1f - progress);
                photoLayer.setScaleX(scale);
                photoLayer.setScaleY(scale);
            }
        };
        addShelfMotion(shelf, behavior, positionEffects);
        shelf.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> positionEffects.run());
        Runnable sizeShelf = () -> {
            int height = page.getHeight();
            if (height == 0 || lastRow.getHeight() == 0) return;
            int expandedHeight = height - Math.min(dp(114), height / 6);
            int restingHeight = Math.min(expandedHeight, toolbar.getHeight() + lastRow.getBottom() + dp(8));
            int photoHeight = height - restingHeight + dp(30);
            ViewGroup.LayoutParams photo = header.getLayoutParams();
            if (photo.height != photoHeight) {
                photo.height = photoHeight;
                header.setLayoutParams(photo);
            }
            if (layout.height != expandedHeight) {
                layout.height = expandedHeight;
                shelf.setLayoutParams(layout);
            }
            if (behavior.getPeekHeight() != restingHeight) behavior.setPeekHeight(restingHeight);
            positionEffects.run();
        };
        View.OnLayoutChangeListener resize = (v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            page.removeCallbacks(sizeShelf);
            page.post(sizeShelf);
        };
        page.addOnLayoutChangeListener(resize);
        lastRow.addOnLayoutChangeListener(resize);
    }

    private void addShelfMotion(View shelf, BottomSheetBehavior<LinearLayout> behavior, Runnable positionEffects) {
        SpringForce force = new SpringForce(0f)
            .setStiffness(getResources().getFloat(com.google.android.material.R.dimen.m3_sys_motion_expressive_spring_default_spatial_stiffness))
            .setDampingRatio(getResources().getFloat(com.google.android.material.R.dimen.m3_sys_motion_expressive_spring_default_spatial_damping));
        SpringAnimation recoil = new SpringAnimation(shelf, SpringAnimation.TRANSLATION_Y).setSpring(force);
        recoil.addUpdateListener((animation, value, velocity) -> positionEffects.run());
        behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            private long lastMove;
            private int lastTop;
            private float velocity;

            @Override public void onSlide(View view, float offset) {
                positionEffects.run();
                if (behavior.getState() != BottomSheetBehavior.STATE_DRAGGING) return;
                long now = SystemClock.uptimeMillis();
                long elapsed = now - lastMove;
                if (elapsed > 0) {
                    velocity = (view.getTop() - lastTop) * 1000f / elapsed;
                    lastTop = view.getTop();
                    lastMove = now;
                }
            }

            @Override public void onStateChanged(View view, int state) {
                if (state == BottomSheetBehavior.STATE_DRAGGING) {
                    recoil.cancel();
                    view.setTranslationY(0f);
                    positionEffects.run();
                    lastTop = view.getTop();
                    lastMove = SystemClock.uptimeMillis();
                    velocity = 0f;
                } else if (state == BottomSheetBehavior.STATE_SETTLING && lastMove != 0) {
                    if (android.animation.ValueAnimator.areAnimatorsEnabled() && SystemClock.uptimeMillis() - lastMove < 100) {
                        float limit = dp(480);
                        recoil.setStartVelocity(Math.max(-limit, Math.min(limit, velocity * .45f)));
                        recoil.start();
                    }
                    lastMove = 0;
                } else if (state != BottomSheetBehavior.STATE_SETTLING) {
                    lastMove = 0;
                }
            }
        });
    }

    private void initializeLongPressDefault() {
        SharedPreferences prefs = wireless.prefs();
        if (prefs.getBoolean(LONG_PRESS_DEFAULT_SET, false)) return;
        try {
            PackageManager packages = getPackageManager();
            PackageInfo installed = packages.getPackageInfo(getPackageName(), 0);
            // An absent long-press target on an upgrade can be an intentional OnePlus choice.
            if (installed.firstInstallTime == installed.lastUpdateTime
                && packages.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
                && mailbox.selected(KeyPress.LONG) == null) {
                mailbox.select(KeyPress.LONG, "action:torch");
            }
            prefs.edit().putBoolean(LONG_PRESS_DEFAULT_SET, true).apply();
        } catch (PackageManager.NameNotFoundException | IOException error) {
            android.util.Log.w("PlusKey", "Could not initialize long press", error);
        }
    }
    private void showSettings() {
        showSettingsSheet(true);
    }

    private void showClickInterval() {
        showSettingsSheet(false);
    }

    private void showSettingsSheet(boolean includeConnection) {
        if (settingsBack.isEnabled()) return;
        previousScroll = shelfScroll.getScrollY();
        LinearLayout body = Ui.column(this);
        body.setPadding(dp(20), dp(20), dp(20), dp(24));
        TextView title = Ui.text(this, body, includeConnection ? "Settings" : "Press timing", 30, false);
        title.setTypeface(null, Typeface.BOLD);
        LinearLayout timing = Ui.card(this, body);
        Ui.text(this, timing, includeConnection ? "Press timing" : "Maximum gap between presses", 19, false)
            .setTypeface(null, Typeface.BOLD);
        if (includeConnection) Ui.text(this, timing, "Maximum gap between presses", 15, true);
        int interval = mailbox.clickInterval();
        TextView value = Ui.text(this, timing, interval + " ms", 36, false);
        value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        value.setTextColor(Ui.color(this, androidx.appcompat.R.attr.colorPrimary));
        Slider slider = new Slider(this);
        slider.setContentDescription("Maximum gap between presses");
        slider.setValueFrom(Mailbox.MIN_CLICK_INTERVAL_MS);
        slider.setValueTo(Mailbox.MAX_CLICK_INTERVAL_MS);
        slider.setStepSize(1);
        slider.setTickVisibilityMode(TickVisibilityMode.TICK_VISIBILITY_HIDDEN);
        slider.setValue(interval);
        slider.setLabelFormatter(ms -> Math.round(ms) + " ms");
        timing.addView(slider, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout limits = Ui.actions(this, timing);
        Ui.text(this, limits, Mailbox.MIN_CLICK_INTERVAL_MS + " ms", 13, true);
        Ui.text(this, limits, Mailbox.MAX_CLICK_INTERVAL_MS + " ms", 13, true).setGravity(Gravity.END);
        Ui.equal(this, limits);
        Ui.text(this, timing, "A longer gap gives you more time between taps and delays the action.", 14, true);
        MaterialButton save = Ui.button(this, timing, "Save timing", true, v -> {
            try {
                mailbox.setClickInterval(Math.round(slider.getValue()));
                v.setEnabled(false);
            } catch (IOException error) {
                toast("Could not save press timing");
            }
        });
        save.setEnabled(false);
        slider.addOnChangeListener(new Slider.OnChangeListener() {
            private long lastTickTime;

            @Override public void onValueChange(Slider view, float ms, boolean fromUser) {
                int millis = Math.round(ms);
                value.setText(millis + " ms");
                if (!fromUser) return;
                save.setEnabled(true);
                // The slider reports each changed 1 ms step. Coalesce only while the motor is busy.
                long now = SystemClock.uptimeMillis();
                if (now - lastTickTime < MIN_TICK_SPACING_MS) return;
                lastTickTime = now;
                view.performHapticFeedback(Build.VERSION.SDK_INT >= 34
                    ? HapticFeedbackConstants.SEGMENT_TICK
                    : HapticFeedbackConstants.CLOCK_TICK);
            }
        });
        if (includeConnection) {
            LinearLayout connection = Ui.card(this, body);
            Ui.text(this, connection, "Button connection", 19, false);
            settingsRow(connection, "Set up button", android.R.drawable.ic_menu_manage, setup::show);
            settingsRow(connection, "Connection settings", android.R.drawable.ic_menu_preferences, setup::advanced);

            LinearLayout updatesCard = Ui.card(this, body);
            Ui.text(this, updatesCard, "App updates", 19, false);
            settingsRow(updatesCard, "Check for updates", android.R.drawable.ic_popup_sync, this::checkUpdates);
            com.google.android.material.materialswitch.MaterialSwitch automatic =
                new com.google.android.material.materialswitch.MaterialSwitch(this);
            automatic.setText("Automatically check for updates");
            automatic.setMinHeight(dp(52));
            automatic.setChecked(automaticUpdates());
            automatic.setOnCheckedChangeListener((button, checked) -> updates.setAutomatic(checked));
            updatesCard.addView(automatic);
        }
        settingsBack.setEnabled(true);
        updateShelfNavigation(true);
        switchShelfPage(body, true);
        shelfScroll.scrollTo(0, 0);
        androidx.core.view.ViewCompat.setAccessibilityPaneTitle(body, title.getText());
    }

    private void updateShelfNavigation(boolean settingsOpen) {
        int inset = dp(settingsOpen ? 12 : 8);
        shelfNav.setPadding(inset, inset, inset, inset);
        shelfNav.setImageResource(settingsOpen ? R.drawable.ic_back : R.drawable.ic_settings);
        shelfNav.setContentDescription(settingsOpen ? "Back to gestures" : "Settings");
    }

    private void returnToGestures() {
        settingsBack.setEnabled(false);
        updateShelfNavigation(false);
        switchShelfPage(gesturesPage, false);
        shelfScroll.post(() -> shelfScroll.scrollTo(0, previousScroll));
    }

    private void switchShelfPage(View next, boolean forward) {
        View previous = shelfPages.getChildAt(0);
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            android.transition.TransitionManager.endTransitions(shelfPages);
            com.google.android.material.transition.platform.MaterialSharedAxis motion =
                new com.google.android.material.transition.platform.MaterialSharedAxis(
                    com.google.android.material.transition.platform.MaterialSharedAxis.X, forward);
            motion.addTarget(previous);
            motion.addTarget(next);
            android.transition.TransitionManager.beginDelayedTransition(shelfPages, motion);
        }
        shelfPages.removeView(previous);
        shelfPages.addView(next);
    }

    private void settingsRow(LinearLayout group, String label, int icon, Runnable open) {
        MaterialButton row = new MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle);
        row.setText(label);
        row.setTextSize(16);
        row.setAllCaps(false);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setIconResource(icon);
        row.setIconPadding(dp(16));
        row.setMinHeight(dp(52));
        row.setOnClickListener(v -> open.run());
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout gestureRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.TOP);
        row.setBaselineAligned(false);
        row.setClipChildren(false);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(14);
        parent.addView(row, size);
        return row;
    }

    private CharSequence rhythmMarks(int gesture, int color) {
        android.text.SpannableString marks = new android.text.SpannableString(GesturePatterns.label(gesture));
        for (int index = 0; index < marks.length(); index++) {
            android.graphics.drawable.Drawable symbol;
            if (marks.charAt(index) == '—') {
                symbol = getDrawable(R.drawable.ic_hold).mutate();
                symbol.setBounds(0, 0, symbol.getIntrinsicWidth(), symbol.getIntrinsicHeight());
            } else if (marks.charAt(index) == '●') {
                android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
                dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                dot.setColor(android.graphics.Color.WHITE);
                dot.setBounds(0, 0, dp(10), dp(10));
                symbol = dot;
            } else {
                continue;
            }
            symbol.setTint(color);
            marks.setSpan(new android.text.style.ImageSpan(symbol, android.text.style.DynamicDrawableSpan.ALIGN_CENTER),
                index, index + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return marks;
    }

    private void addPressCard(LinearLayout row, int gesture, String title, String unsetLabel, String rhythm) {
        LinearLayout card = Ui.card(this, row);
        card.setPadding(dp(12), dp(14), dp(12), dp(10));
        MaterialCardView tile = (MaterialCardView) card.getParent();
        LinearLayout.LayoutParams tileSize = (LinearLayout.LayoutParams) tile.getLayoutParams();
        tileSize.topMargin = 0;
        tileSize.height = LinearLayout.LayoutParams.MATCH_PARENT;
        tile.setLayoutParams(tileSize);
        tile.setRadius(dp(18));
        tile.setCardElevation(dp(4));
        tile.setOutlineAmbientShadowColor(android.graphics.Color.BLACK);
        tile.setOutlineSpotShadowColor(android.graphics.Color.BLACK);
        tile.setStrokeColor(0x16FFFFFF);
        tile.setStrokeWidth(dp(1));
        tile.setFocusable(true);
        tile.setOnClickListener(v -> choose(gesture, gesture > KeyPress.TRIPLE ? rhythm : title, unsetLabel));
        LinearLayout symbols = Ui.actions(this, card);
        TextView pattern = new TextView(this);
        pattern.setTextColor(Ui.color(this, androidx.appcompat.R.attr.colorPrimary));
        pattern.setText(rhythmMarks(gesture, pattern.getCurrentTextColor()));
        pattern.setContentDescription(gesture <= KeyPress.TRIPLE ? title :
                GesturePatterns.text(gesture).replace(".", "short ").replace("-", "long ").trim());
        symbols.addView(pattern, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView icon = new ImageView(this);
        symbols.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        pressIcons.put(gesture, icon);
        TextView actionName = Ui.text(this, card, "", 21, false);
        actionName.setTypeface(null, Typeface.BOLD);
        actionName.setPadding(0, dp(12), 0, 0);
        actionName.setMinLines(2);
        pressNames.put(gesture, actionName);
        pressPreviews.put(gesture, Ui.button(this, card, "Preview", false, v -> preview(gesture)));
    }
    private void confirmRemove(int gesture) {
            new MaterialAlertDialogBuilder(this).setTitle("Remove " + GesturePatterns.label(gesture) + "?").setNegativeButton("Cancel", null)
                .setPositiveButton("Remove", (dialog, which) -> {
                    try { mailbox.removePattern(gesture); rebuildPatterns(); showSelections(); }
                    catch (IOException error) { toast(error.getMessage()); }
                }).show();
    }

    private void rebuildPatterns() {
        customRows.removeAllViews();
        pressNames.keySet().removeIf(id -> id > KeyPress.TRIPLE);
        pressIcons.keySet().removeIf(id -> id > KeyPress.TRIPLE);
        pressPreviews.keySet().removeIf(id -> id > KeyPress.TRIPLE);
        LinearLayout row = null;
        for (int id : mailbox.patterns()) {
            if (row == null || row.getChildCount() == 2) row = gestureRow(customRows);
            addPressCard(row, id, GesturePatterns.label(id), "Off", GesturePatterns.label(id));
            Ui.equal(this, row);
        }
        if (row != null && row.getChildCount() == 1) {
            View tile = row.getChildAt(0);
            LinearLayout.LayoutParams tileSize = (LinearLayout.LayoutParams) tile.getLayoutParams();
            tileSize.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            tile.setLayoutParams(tileSize);
            row.addView(new Space(this), new LinearLayout.LayoutParams(0, 1));
            Ui.equal(this, row);
        }
    }

    private void recordPattern() {
        if (!helperReady) { toast("Start Plus Key before recording a pattern"); return; }
        File dir = getExternalFilesDir(null);
        if (dir == null) { toast("Pattern storage unavailable"); return; }
        PatternCapture capture = new PatternCapture(dir);
        final String token;
        try { token = capture.start(); } catch (IOException error) { toast(error.getMessage()); return; }
        LinearLayout body = Ui.column(this);
        body.setPadding(dp(24), dp(6), dp(24), 0);
        Ui.text(this, body,"Press Plus Key up to 8 times. Tap for a dot, hold for a dash. Actions are paused while recording.",16,true);
        TextView sequence = Ui.text(this, body,"Waiting for Plus Key…",24,false);
        sequence.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle("Record pattern")
            .setView(body).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        captureDialog = dialog;
        Runnable update = () -> {
            if (!dialog.isShowing()) return;
            try {
                String value = capture.read(token);
                boolean valid = value.matches("[.-]{1,8}");
                sequence.setText(valid ? rhythmMarks(GesturePatterns.id(value), sequence.getCurrentTextColor()) : value.isEmpty() ? "Waiting for Plus Key…" : "Too many presses. Cancel and try again.");
                dialog.getButton(-1).setEnabled(valid);
            } catch (IOException error) { dialog.getButton(-1).setEnabled(false); }
        };
        MailboxEvents.Subscription observer = MailboxEvents.watch(dir, (event, path) -> {
            if (event == FileObserver.CLOSE_WRITE && PatternCapture.filename(token).equals(path)) handler.post(update);
        });
        Runnable expire = dialog::dismiss;
        dialog.setOnDismissListener(ignored -> { capture.stop(token); observer.close(); handler.removeCallbacks(expire); handler.removeCallbacks(update); captureDialog = null; });
        dialog.show(); update.run(); handler.postDelayed(expire, PatternCapture.TIMEOUT);
        dialog.getButton(-1).setOnClickListener(view -> {
            try {
                String value = capture.read(token);
                if (!value.matches("[.-]{1,8}")) return;
                int id = GesturePatterns.id(value);
                mailbox.addPattern(id); dialog.dismiss(); rebuildPatterns(); showSelections(); choose(id,GesturePatterns.label(id),"Off");
            } catch (IOException | IllegalArgumentException error) { toast(error.getMessage()); }
        });
    }

    private void choose(int gesture, String title, String unsetLabel) {
        try {
            String current = mailbox.selected(gesture);
            Runnable timing = GesturePatterns.text(gesture).length() > 1 ? this::showClickInterval : null;
            new ChoicePicker(this, choice -> {
                if (choice.target != null && choice.target.startsWith("configure:")) {
                    ComponentName component = ComponentName.unflattenFromString(choice.target.substring("configure:".length()));
                    configuringGesture = gesture;
                    configuringPackage = component.getPackageName();
                    try {
                        shortcutSetup.launch(new Intent(Intent.ACTION_CREATE_SHORTCUT).setComponent(component));
                    } catch (ActivityNotFoundException | SecurityException error) {
                        configuringPackage = null;
                        toast("Shortcut setup unavailable");
                    }
                } else if (GesturePatterns.text(gesture).endsWith("-") && choice.target != null && ChoicePicker.canHold(this, choice.target)) {
                    String normal = choice.target.startsWith("tile:") ? "Click once" : "Toggle";
                    new MaterialAlertDialogBuilder(this).setTitle(choice.label)
                        .setMessage("While held is available for patterns ending in a long press. Starts when the final hold is recognized and stops when you release Plus Key.")
                        .setNegativeButton(normal,(dialog,which) -> saveChoice(gesture,choice))
                        .setPositiveButton("While held",(dialog,which) -> saveChoice(gesture,new Targets.Choice(choice.label + " · While held","hold:" + choice.target))).show();
                } else saveChoice(gesture, choice);
            }).show(title, unsetLabel, current != null && current.startsWith("hold:") ? current.substring(5) : current, timing,
                    gesture > KeyPress.TRIPLE ? () -> confirmRemove(gesture) : null);
        } catch (IOException error) { toast("Could not read selection"); }
    }
    private void saveChoice(int gesture, Targets.Choice choice) {
        try {
            mailbox.select(gesture, choice.target);
            if (choice.target != null) Targets.remember(this, choice);
            showSelections();
            if ("action:record".equals(choice.target) || "hold:action:record".equals(choice.target) ||
                    choice.target != null && choice.target.startsWith("media:") && choice.target.contains("action:record")) {
                ArrayList<String> missing = new ArrayList<>();
                for (String permission : RecordingActivity.requiredPermissions())
                    if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
                if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), 5);
            }
        } catch (IOException | IllegalArgumentException error) { toast(error.getMessage()); }
    }
    private String systemAction(){try{return Settings.System.getString(getContentResolver(),Helper.ONEPLUS_ACTION_SETTING);}catch(Exception error){return null;}}
    private void showSelections(){
        try {
            for (int gesture : pressNames.keySet()) {
                String target = mailbox.selected(gesture);
                showSelection(gesture, target);
                MaterialButton preview = pressPreviews.get(gesture);
                if (gesture == KeyPress.LONG) {
                    String nativeAction = systemAction();
                    boolean blocked = target != null && !"nothing".equals(nativeAction);
                    boolean showSetup = blocked && nativeAction != null && !nativeAction.isEmpty();
                    boolean openSettings = target == null || (blocked && !showSetup);
                    preview.setText(openSettings ? "Plus Key settings" : "Preview");
                    preview.setEnabled(!showSetup);
                    preview.setOnClickListener(v -> {if (openSettings) onePlusSettings(); else preview(KeyPress.LONG);});
                    longPressSetupCard.setVisibility(showSetup ? View.VISIBLE : View.GONE);
                } else {
                    preview.setEnabled(target != null);
                }
            }
        } catch (IOException error) {toast("Choose an action again");}
    }
    private void showSelection(int gesture, String target){
        TextView name = pressNames.get(gesture);
        ImageView icon = pressIcons.get(gesture);
        name.setText(target == null && gesture != KeyPress.LONG ? "Off" : Targets.label(this, target));
        icon.setImageTintList(null);
        String action = target != null && target.startsWith("hold:") ? target.substring(5) : target;
        if (Options.fanApps(action) != null) {
            icon.setImageResource(R.drawable.ic_app_fan);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this, androidx.appcompat.R.attr.colorPrimary)));
            return;
        }
        if (Options.isTile(action)) {
            try {
                ComponentName component = ComponentName.unflattenFromString(action.substring(5));
                ServiceInfo service = getPackageManager().getServiceInfo(component, 0);
                icon.setImageDrawable(service.loadIcon(getPackageManager()).mutate());
                if (service.icon != 0) icon.setImageTintList(android.content.res.ColorStateList.valueOf(
                    Ui.color(this, androidx.appcompat.R.attr.colorPrimary)));
                return;
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        try{
            Intent[] intents=target==null?new Intent[0]:Targets.intents(target);
            if(intents.length>0){icon.setImageDrawable(getPackageManager().getActivityIcon(intents[intents.length-1]));return;}
        }catch(Exception ignored){}
        icon.setImageResource(ChoicePicker.actionIcon(target));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.color(this,androidx.appcompat.R.attr.colorPrimary)));
    }
    private void preview(int gesture){
        ImageView icon = pressIcons.get(gesture);
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            icon.setScaleX(.8f);
            icon.setScaleY(.8f);
            icon.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
        }
        try{String target=mailbox.selected(gesture);if(target==null)return;
            if(target.startsWith("hold:")){toast("Hold the physical Plus Key to test this action");return;}
            if(target.startsWith("tile:") || target.startsWith("action:") || Options.mediaBranches(target) != null){if(helperReady){mailbox.requestPreview(gesture);return;}toast("Start your button first to preview this action");return;}
            startActivities(Targets.intents(target));
        }catch(Exception error){toast("Action unavailable. Choose another.");}
    }
    void startHelper(){
        wireless.prefs().edit().putBoolean("user_stopped",false).apply();
        if (ShizukuConnection.authorized()) {
            if (!wireless.busy.get()) shizukuAttempt = true;
            wireless.start();return;
        }
        if(!wireless.paired()||!setup.localAllowed()){setup.show();return;}
        if(!setup.wifiDebugging()&&!wireless.automatic()){setup.show();return;}
        wireless.start();
    }
    void authorizeShizuku() {
        try {
            if (ShizukuConnection.authorized()) startHelper();
            else if (!ShizukuConnection.running()) toast("Start Shizuku first");
            else if (Shizuku.shouldShowRequestPermissionRationale()) toast("Allow Plus Key in Shizuku’s authorized apps");
            else Shizuku.requestPermission(ShizukuConnection.PERMISSION_REQUEST);
        } catch (RuntimeException disconnected) {
            toast("Shizuku disconnected. Start it and try again.");
        }
    }
    private void watchStatus() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return;
        statusObserver = MailboxEvents.watch(dir, (event, path) -> {
            Runnable callback;
            if (event == FileObserver.MOVED_TO && Mailbox.STATUS_FILE.equals(path)) callback = statusChanged;
            else if (event == FileObserver.CLOSE_WRITE && Mailbox.SESSION_FILE.equals(path)) callback = sessionEnded;
            else return;
            handler.removeCallbacks(callback);
            handler.post(callback);
        });
    }

    private void refresh(boolean requestReply) {
        if (!resumed) return;
        int generation = resumeGeneration;
        worker.execute(() -> {
            String value = null;
            boolean matchingVersion = false;
            try {
                matchingVersion = Helper.VERSION.equals(mailbox.runningVersion());
                if (requestReply && matchingVersion) {
                    if (!wireless.prefs().getBoolean("user_stopped", false)) mailbox.requestStatus();
                } else {
                    value = mailbox.readStatus(System.currentTimeMillis());
                }
            } catch (IOException ignored) { }
            final String current = value;
            final boolean versionOkay = matchingVersion;
            runOnUiThread(() -> {
                if (!resumed || resumeGeneration != generation) return;
                currentStatus = current;
                currentVersion = versionOkay;
                connectionChanged.run();
            });
        });
    }
    private void renderStatus(String current, boolean versionOkay) {
        boolean busy = wireless.busy.get();
        boolean ready = current != null && current.startsWith("Listening|") && versionOkay;
        helperReady = ready;
        boolean paired = wireless.paired();
        boolean configured = paired || ShizukuConnection.authorized();
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        start.setVisibility(!ready && !busy && configured ? View.VISIBLE : View.GONE);
        stop.setVisibility(ready && !busy ? View.VISIBLE : View.GONE);
        finishSetup.setVisibility(!ready && !busy ? View.VISIBLE : View.GONE);
        setTextIfChanged(finishSetup, configured ? "Fix setup" : "Finish setup");
        if (busy) {
            setStatus("Starting…", STATUS_AMBER);
            setTextIfChanged(detail, wireless.message);
        } else if (ready) {
            String[] fields = current.split("\\|", 3);
            boolean failed = fields.length == 3 && (fields[2].startsWith("Launch failed") || fields[2].startsWith("Long press needs"));
            setStatus(failed ? "Action needs attention" : "Active", failed ? STATUS_RED : STATUS_GREEN);
            setTextIfChanged(detail, failed ? fields[2] : wireless.automatic() && !ShizukuConnection.authorized() ? "Automatic startup on" : "Your actions are ready.");
        } else if (wireless.prefs().getBoolean("user_stopped", false)) {
            setStatus("Stopped", STATUS_GRAY);
            setTextIfChanged(detail, "Tap Start when you’re ready.");
        } else if (current != null && current.startsWith("Error|")) {
            setStatus("Needs attention", STATUS_RED);
            setTextIfChanged(detail, current.split("\\|", 3)[2]);
        } else {
            setStatus(configured ? "Not running" : "Setup needed", configured ? STATUS_GRAY : STATUS_AMBER);
            String message = wireless.message;
            if (!message.startsWith("Setup needed:")) {
                message = configured ? "Tap Start to reconnect your button." : "Finish setup to use your button.";
            }
            setTextIfChanged(detail, message);
        }
    }
    private void setStatus(String label, int color) {
        if (statusDot.getColor().getDefaultColor() != color) statusDot.setColor(color);
        setTextIfChanged(status, label);
    }

    private static void setTextIfChanged(TextView view, String value) {
        if (!TextUtils.equals(view.getText(), value)) view.setText(value);
    }
    void requestSetupPermissions(){ArrayList<String> missing=new ArrayList<>();if(!setup.localAllowed())missing.add("android.permission.ACCESS_LOCAL_NETWORK");if(!wireless.paired()&&!setup.notificationsAllowed())missing.add("android.permission.POST_NOTIFICATIONS");if(!missing.isEmpty())requestPermissions(missing.toArray(new String[0]),3);}
    void requestPairingPermissions(){ArrayList<String> missing=new ArrayList<>();if(!setup.localAllowed())missing.add("android.permission.ACCESS_LOCAL_NETWORK");if(!setup.notificationsAllowed())missing.add("android.permission.POST_NOTIFICATIONS");if(!missing.isEmpty()){resumePairingAfterPermission=true;requestPermissions(missing.toArray(new String[0]),3);}}
    public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){super.onRequestPermissionsResult(code,permissions,grants);setup.refresh();if(resumePairingAfterPermission){resumePairingAfterPermission=false;if(setup.localAllowed()&&setup.notificationsAllowed())setup.pairWithCode();}}
    void checkUpdates(){updates.check(true);}
    boolean automaticUpdates(){return updates.automatic();}
    private void onePlusSettings(){try{startActivity(new Intent("com.oplus.gesture.StartActionButtonSettingsActivity").setPackage("com.oplus.gesture"));}catch(Exception error){
        Intent settings=new Intent(Settings.ACTION_SETTINGS).putExtra(":settings:fragment_args_key","snap_card_pack");
        Bundle args=new Bundle();args.putString(":settings:fragment_args_key","snap_card_pack");settings.putExtra(":settings:show_fragment_args",args);
        startActivity(settings);toast("Tap Snap Key to choose Do nothing");
    }}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private int dp(int value){return Ui.dp(this,value);}
    private boolean autoConnect() {
        if (wireless.busy.get() || wireless.prefs().getBoolean("user_stopped", false)) return false;
        if (ShizukuConnection.authorized()) {
            if (shizukuAttempt) return false;
            shizukuAttempt = true;
        } else {
            shizukuAttempt = false;
            boolean pending = wireless.prefs().getBoolean("automatic_requested", false) && setup.wifiDebugging();
            if ((autoAttempt && !pending) || !wireless.canUseWireless()) return false;
            autoAttempt = true;
        }
        wireless.start();
        return true;
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        resumeGeneration++;
        currentStatus = null;
        currentVersion = false;
        watchStatus();
        wireless.addListener(connectionChanged);
        Shizuku.addBinderReceivedListener(binderReceived);
        Shizuku.addBinderDeadListener(binderDead);
        if (!autoConnect()) refresh(true);
        connectionChanged.run();
        updates.resumed();
        updates.check(false);
        rebuildPatterns();
        showSelections();
    }

    @Override protected void onPause() {
        if (captureDialog != null) captureDialog.dismiss();
        resumed = false;
        wireless.removeListener(connectionChanged);
        Shizuku.removeBinderReceivedListener(binderReceived);
        Shizuku.removeBinderDeadListener(binderDead);
        if (statusObserver != null) {
            statusObserver.close();
            statusObserver = null;
        }
        handler.removeCallbacks(statusChanged);
        handler.removeCallbacks(sessionEnded);
        super.onPause();
    }
    protected void onDestroy(){Shizuku.removeRequestPermissionResultListener(shizukuPermission);worker.shutdownNow();if(updates!=null)updates.close();super.onDestroy();}
}
