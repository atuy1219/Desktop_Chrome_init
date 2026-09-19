package com.atuy.desktopchromeinit;

import android.app.Activity;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.Gravity;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Build-specific repair for Google Chrome Desktop Android 153.0.8010.49
 * (versionCode 801004974).
 *
 * APK findings used by this hook:
 *
 * ChromeTabbedActivity.a3(...)
 *   -> zd4.P2() : hns
 *   -> hns.J1   : rr9
 *   -> rr9.f0 = true
 *
 * rr9 is ExtensionsToolbarCoordinatorImpl.
 * hns is ToolbarManager.
 * hns.l(...) is ToolbarManager.initializeWithNative(...).
 *
 * The normal hns.l creation block is skipped when either the extensions
 * ViewStub is absent or hns.D1.get() returns null.
 */
public final class ChromeInitHook implements IXposedHookLoadPackage {
    private static final String TAG = "DesktopChromeInit";
    private static final String TARGET_PACKAGE = "com.android.chrome";

    // Resource IDs observed directly in 801004974. Names are preferred and
    // these values are only fallbacks for this exact build.
    private static final int FALLBACK_EXTENSIONS_MENU_ID = 0x7f01054d;
    private static final int FALLBACK_EXTENSIONS_STUB_ID = 0x7f01056a;
    private static final int FALLBACK_EXTENSIONS_LAYOUT_ID = 0x7f0e0190;

    private static volatile ClassLoader chromeClassLoader;

    /**
     * hns.l() receives two objects which Chrome captures into the synthetic
     * ums Supplier used to build rr9. Keep them weakly keyed by ToolbarManager
     * so the repair can reproduce Chrome's own construction path later.
     */
    private static final Map<Object, InitCapture> INIT_CAPTURES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final ThreadLocal<Boolean> EXTENSIONS_ACTION =
            new ThreadLocal<>();

    private static final Map<Activity, FrameLayout> EXTENSIONS_BUTTON_HOSTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class InitCapture {
        final Object contextMenuFactory;
        final Object extensionSupport;

        InitCapture(Object contextMenuFactory, Object extensionSupport) {
            this.contextMenuFactory = contextMenuFactory;
            this.extensionSupport = extensionSupport;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)
                || !TARGET_PACKAGE.equals(lpparam.processName)) {
            return;
        }

        chromeClassLoader = lpparam.classLoader;
        log("loading into " + lpparam.processName);

        installToolbarInitCapture(lpparam.classLoader);
        installExtensionsMenuRepair(lpparam.classLoader);
    }

    /**
     * Capture the two initializeWithNative arguments that are otherwise only
     * available as local variables inside hns.l().
     */
    private static void installToolbarInitCapture(ClassLoader classLoader) {
        Class<?> toolbarManager = XposedHelpers.findClassIfExists("hns", classLoader);
        if (toolbarManager == null) {
            log("hns not found; target Chrome obfuscation does not match");
            return;
        }

        XposedBridge.hookAllMethods(toolbarManager, "l", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                // 801004974:
                // hns.l(mfe, i2q, Runnable, OnClickListener, fbi, fbi,
                //       ydt, ki4, dp4)
                if (param.args == null || param.args.length != 9) {
                    return;
                }

                // The original method moves p8 and p9 into ums.W and ums.X.
                INIT_CAPTURES.put(
                        param.thisObject,
                        new InitCapture(param.args[7], param.args[8])
                );
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object coordinator = XposedHelpers.getObjectField(
                            param.thisObject, "J1");
                    if (coordinator == null) {
                        log("initializeWithNative finished with hns.J1 == null");
                    } else {
                        log("Extensions coordinator initialized normally");
                    }
                } catch (Throwable t) {
                    log("could not inspect hns.J1 after init: " + t);
                }
            }
        });
    }

    private static void installExtensionsMenuRepair(ClassLoader classLoader) {
        Class<?> chromeTabbedActivity = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.ChromeTabbedActivity",
                classLoader
        );
        if (chromeTabbedActivity == null) {
            log("ChromeTabbedActivity not found");
            return;
        }

        XposedBridge.hookAllMethods(chromeTabbedActivity, "a3", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args == null
                        || param.args.length != 4
                        || !(param.args[0] instanceof Integer)
                        || !(param.thisObject instanceof Activity)) {
                    return;
                }

                Activity activity = (Activity) param.thisObject;
                int id = (Integer) param.args[0];
                if (!isExtensionsMenuAction(activity, id)) {
                    return;
                }

                EXTENSIONS_ACTION.set(Boolean.TRUE);

                try {
                    // Do not use XposedHelpers.callMethod() on ChromeActivity.
                    // Its best-match implementation enumerates declared methods,
                    // which attempts to resolve Android APIs that do not exist on
                    // the phone build (notably android.app.HandoffActivityData).
                    Object toolbarManager = invokeExactNoArg(activity, "P2");
                    if (toolbarManager == null) {
                        log("P2() returned null ToolbarManager");
                        param.setResult(true);
                        return;
                    }

                    Object coordinator = XposedHelpers.getObjectField(
                            toolbarManager, "J1");
                    if (coordinator != null) {
                        return;
                    }

                    log("Extensions action hit with hns.J1 == null; repairing");

                    if (!repairCoordinator(activity, toolbarManager)) {
                        // Do not let the known rr9.f0 NPE terminate Chrome.
                        log("repair incomplete; suppressing Extensions action");
                        param.setResult(true);
                    }
                } catch (Throwable t) {
                    log("repair hook failed: " + stackSummary(t));
                    param.setResult(true);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                boolean extensionAction = Boolean.TRUE.equals(
                        EXTENSIONS_ACTION.get());
                EXTENSIONS_ACTION.remove();

                if (!extensionAction) {
                    return;
                }

                Throwable t = param.getThrowable();
                if (t instanceof NullPointerException) {
                    log("suppressed known Extensions-menu NPE: "
                            + stackSummary(t));
                    param.setResult(true);
                }
            }
        });
    }

    private static boolean isExtensionsMenuAction(Activity activity, int id) {
        try {
            String entryName = activity.getResources().getResourceEntryName(id);
            if ("extensions_menu_menu_id".equals(entryName)) {
                return true;
            }
        } catch (Resources.NotFoundException ignored) {
            // Fall back to the exact ID decoded from 801004974.
        }
        return id == FALLBACK_EXTENSIONS_MENU_ID;
    }

    private static boolean repairCoordinator(
            Activity activity, Object toolbarManager) throws Throwable {

        Object existing = XposedHelpers.getObjectField(toolbarManager, "J1");
        if (existing != null) {
            relocateExtensionsButtonToBottomToolbar(activity);
            return true;
        }

        InitCapture capture = INIT_CAPTURES.get(toolbarManager);
        if (capture == null) {
            log("no hns.l() capture; force-stop Chrome after enabling module");
            return false;
        }

        Object chromeAndroidTask = getChromeAndroidTask(toolbarManager);
        if (chromeAndroidTask == null) {
            log("hns.D1.get() == null; retrying ChromeActivity.S2()");
            retryChromeAndroidTaskInitialization(activity);
            chromeAndroidTask = getChromeAndroidTask(toolbarManager);
        }

        if (chromeAndroidTask == null) {
            log("ChromeAndroidTask is still null");
            return false;
        }

        ViewStub stub = ensureExtensionsStub(activity, toolbarManager);
        if (stub == null) {
            log("could not obtain/create extensions_toolbar_container_stub");
            return false;
        }

        Object coordinator = createCoordinatorThroughChrome(
                toolbarManager,
                chromeAndroidTask,
                stub,
                capture
        );

        if (coordinator == null) {
            log("Chrome coordinator factory returned null");
            return false;
        }

        XposedHelpers.setObjectField(toolbarManager, "J1", coordinator);
        registerCoordinatorWithToolbar(toolbarManager, coordinator);

        Object verify = XposedHelpers.getObjectField(toolbarManager, "J1");
        boolean repaired = verify != null;
        log(repaired
                ? "repair successful: hns.J1 initialized"
                : "repair failed: hns.J1 remained null");

        if (repaired) {
            relocateExtensionsButtonToBottomToolbar(activity);
        }
        return repaired;
    }

    /**
     * hns.D1 is the Supplier used by ToolbarManager.initializeWithNative().
     */
    private static Object getChromeAndroidTask(Object toolbarManager) {
        try {
            Object supplier = XposedHelpers.getObjectField(
                    toolbarManager, "D1");
            if (supplier == null) {
                return null;
            }
            return XposedHelpers.callMethod(supplier, "get");
        } catch (Throwable t) {
            log("reading hns.D1 failed: " + t);
            return null;
        }
    }

    /**
     * ChromeTabbedActivity.A() calls:
     *   S2(0, F3, O2)
     * during startup. If the task supplier was not populated at that moment,
     * retry that same Chrome-owned initialization route.
     */
    private static void retryChromeAndroidTaskInitialization(Activity activity) {
        try {
            int taskId = XposedHelpers.getIntField(activity, "F3");
            Object tabModelSelector = XposedHelpers.getObjectField(
                    activity, "O2");
            if (tabModelSelector == null) {
                log("cannot retry S2(): O2 is null");
                return;
            }

            invokeThreeArgExactByHierarchy(
                    activity,
                    "S2",
                    0,
                    taskId,
                    tabModelSelector
            );
            log("retried ChromeActivity.S2(0, F3, O2) without method enumeration");
        } catch (Throwable t) {
            log("ChromeActivity.S2 retry failed: " + stackSummary(t));
        }
    }

    /**
     * hns.l() searches hns.d0 (ToolbarControlContainer) for
     * extensions_toolbar_container_stub. Phone layouts can omit that stub,
     * which causes Chrome to skip coordinator construction.
     */
    private static ViewStub ensureExtensionsStub(
            Activity activity, Object toolbarManager) {

        try {
            Object containerObject = XposedHelpers.getObjectField(
                    toolbarManager, "d0");
            if (!(containerObject instanceof View)) {
                log("hns.d0 is not a View");
                return null;
            }

            View container = (View) containerObject;
            Resources resources = activity.getResources();

            int stubId = resources.getIdentifier(
                    "extensions_toolbar_container_stub",
                    "id",
                    TARGET_PACKAGE
            );
            if (stubId == 0) {
                stubId = FALLBACK_EXTENSIONS_STUB_ID;
            }

            View existing = container.findViewById(stubId);
            if (existing instanceof ViewStub) {
                return (ViewStub) existing;
            }

            if (existing != null) {
                log("extensions stub ID exists but is "
                        + existing.getClass().getName());
                return null;
            }

            if (!(container instanceof ViewGroup)) {
                log("ToolbarControlContainer is not a ViewGroup");
                return null;
            }

            int layoutId = resources.getIdentifier(
                    "extensions_toolbar_container",
                    "layout",
                    TARGET_PACKAGE
            );
            if (layoutId == 0) {
                layoutId = FALLBACK_EXTENSIONS_LAYOUT_ID;
            }

            ViewStub injected = new ViewStub(container.getContext());
            injected.setId(stubId);
            injected.setLayoutResource(layoutId);

            ViewGroup group = (ViewGroup) container;
            try {
                group.addView(injected);
            } catch (Throwable first) {
                group.addView(
                        injected,
                        new ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                );
            }

            log("injected extensions_toolbar_container_stub");
            return injected;
        } catch (Throwable t) {
            log("stub repair failed: " + stackSummary(t));
            return null;
        }
    }

    /**
     * Reproduce only the rr9 creation block from hns.l().
     *
     * Original 801004974 flow:
     *   profile = hns.t0.h().f()
     *   key = new se4(rr9.class, profile, (wl) hns.X0)
     *   factory = new ums(...)
     *   rr9 = (rr9) bf4.e(key, factory)
     *   hns.J1 = rr9
     */
    private static Object createCoordinatorThroughChrome(
            Object toolbarManager,
            Object chromeAndroidTask,
            ViewStub stub,
            InitCapture capture) throws Throwable {

        ClassLoader cl = chromeClassLoader;
        Class<?> rr9Class = XposedHelpers.findClass("rr9", cl);
        Class<?> se4Class = XposedHelpers.findClass("se4", cl);
        Class<?> umsClass = XposedHelpers.findClass("ums", cl);
        Class<?> cmsClass = XposedHelpers.findClass("cms", cl);

        Object tabModelSelector = XposedHelpers.getObjectField(
                toolbarManager, "t0");
        if (tabModelSelector == null) {
            log("hns.t0 == null");
            return null;
        }

        Object tabModel = XposedHelpers.callMethod(tabModelSelector, "h");
        if (tabModel == null) {
            log("hns.t0.h() == null");
            return null;
        }

        Object profile = XposedHelpers.callMethod(tabModel, "f");
        if (profile == null) {
            log("TabModel.f() profile == null");
            return null;
        }

        Object windowAndroid = XposedHelpers.getObjectField(
                toolbarManager, "X0");
        if (windowAndroid == null) {
            log("hns.X0 == null");
            return null;
        }

        Object key = XposedHelpers.newInstance(
                se4Class,
                rr9Class,
                profile,
                windowAndroid
        );

        // hns.l() creates cms(byte 8), stores hns in cms.T, and captures the
        // Runnable into ums.Y.
        Object initRunnable = XposedHelpers.newInstance(
                cmsClass, (byte) 8);
        XposedHelpers.setObjectField(
                initRunnable, "T", toolbarManager);

        // ums has no declared constructor in the optimized DEX. Chrome itself
        // allocates it then directly invokes Object.<init>(); Unsafe gives us
        // the same zero-initialized instance without inventing a constructor.
        Object supplier = allocateWithoutConstructor(umsClass);

        XposedHelpers.setObjectField(supplier, "S", toolbarManager);
        XposedHelpers.setObjectField(supplier, "T", stub);
        XposedHelpers.setObjectField(
                supplier, "U", chromeAndroidTask);
        XposedHelpers.setObjectField(supplier, "V", profile);
        XposedHelpers.setObjectField(
                supplier, "W", capture.contextMenuFactory);
        XposedHelpers.setObjectField(
                supplier, "X", capture.extensionSupport);
        XposedHelpers.setObjectField(supplier, "Y", initRunnable);

        // Extensions in this Desktop build assumes ToolbarTablet during
        // construction. On a phone, hns.c0 is ToolbarPhone, and ums.get()
        // performs a hard cast to ToolbarTablet even though it only keeps that
        // value as a ViewGroup parent for RecyclerView transitions.
        //
        // Temporarily provide a minimal ToolbarTablet instance for the factory,
        // restore the real ToolbarPhone immediately afterwards, then replace
        // the retained ViewGroup reference with the real toolbar.
        Object realToolbar = XposedHelpers.getObjectField(toolbarManager, "c0");
        Object temporaryTablet = null;
        boolean toolbarSwapped = false;

        Class<?> toolbarTabletClass = XposedHelpers.findClass(
                "org.chromium.chrome.browser.toolbar.top.ToolbarTablet", cl);

        if (realToolbar != null
                && !toolbarTabletClass.isInstance(realToolbar)) {
            if (!(realToolbar instanceof ViewGroup)) {
                log("hns.c0 is neither ToolbarTablet nor ViewGroup: "
                        + realToolbar.getClass().getName());
                return null;
            }

            java.lang.reflect.Constructor<?> constructor =
                    toolbarTabletClass.getDeclaredConstructor(
                            android.content.Context.class,
                            android.util.AttributeSet.class);
            constructor.setAccessible(true);

            temporaryTablet = constructor.newInstance(
                    ((View) realToolbar).getContext(),
                    null
            );

            XposedHelpers.setObjectField(
                    toolbarManager, "c0", temporaryTablet);
            toolbarSwapped = true;
            log("temporarily substituted ToolbarTablet for ToolbarPhone");
        }

        Object coordinator;
        try {
            Class<?> bf4Class = XposedHelpers.findClass("bf4", cl);
            java.lang.reflect.Method factoryMethod =
                    bf4Class.getDeclaredMethod(
                            "e",
                            se4Class,
                            java.util.function.Supplier.class);
            factoryMethod.setAccessible(true);
            coordinator = factoryMethod.invoke(
                    chromeAndroidTask, key, supplier);
        } finally {
            if (toolbarSwapped) {
                XposedHelpers.setObjectField(
                        toolbarManager, "c0", realToolbar);
                log("restored real ToolbarPhone after coordinator construction");
            }
        }

        if (coordinator == null) {
            return null;
        }

        if (!rr9Class.isInstance(coordinator)) {
            log("factory returned unexpected class: "
                    + coordinator.getClass().getName());
            return null;
        }

        if (toolbarSwapped && realToolbar instanceof ViewGroup) {
            try {
                Object actionListCoordinator =
                        XposedHelpers.getObjectField(coordinator, "V");
                if (actionListCoordinator != null) {
                    Object recycler = XposedHelpers.getObjectField(
                            actionListCoordinator, "T");
                    if (recycler != null) {
                        XposedHelpers.setObjectField(
                                recycler, "I1", realToolbar);
                        log("rebound Extensions RecyclerView parent to ToolbarPhone");
                    }
                }
            } catch (Throwable t) {
                log("failed to rebind Extensions RecyclerView parent: "
                        + stackSummary(t));
            }
        }

        return coordinator;
    }

    /**
     * hns.l() additionally calls hns.b0.T.Y(rr9) after successful creation.
     * Keep that side effect because it wires the coordinator back into the
     * surrounding toolbar state.
     */
    private static void registerCoordinatorWithToolbar(
            Object toolbarManager, Object coordinator) {
        try {
            Object trs = XposedHelpers.getObjectField(
                    toolbarManager, "b0");
            if (trs == null) {
                return;
            }

            Object ols = XposedHelpers.getObjectField(trs, "T");
            if (ols == null) {
                return;
            }

            XposedHelpers.callMethod(ols, "Y", coordinator);
        } catch (Throwable t) {
            // The coordinator itself is already installed. Keep the menu
            // usable even if this secondary registration changes later.
            log("secondary toolbar registration failed: "
                    + stackSummary(t));
        }
    }

    /**
     * Invoke a no-argument method without XposedHelpers' best-match lookup.
     *
     * Chrome Desktop references framework classes (for example
     * android.app.HandoffActivityData) which are absent from this phone OS.
     * Class.getDeclaredMethods() resolves every method signature and therefore
     * throws NoClassDefFoundError before P2() can be invoked. Looking up the
     * exact method name avoids resolving unrelated signatures.
     */
    private static Object invokeExactNoArg(Object receiver, String name)
            throws Throwable {
        Class<?> type = receiver.getClass();
        Throwable last = null;

        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name);
                method.setAccessible(true);
                return method.invoke(receiver);
            } catch (NoSuchMethodException e) {
                last = e;
                type = type.getSuperclass();
            }
        }

        throw new NoSuchMethodException(
                receiver.getClass().getName() + "." + name + "()"
                        + (last != null ? " not found" : ""));
    }

    /**
     * Same idea for S2(int, int, TabModelSelector). The exact third parameter
     * type is obfuscated, so try the runtime class, its superclasses and
     * interfaces one-by-one with getDeclaredMethod(). This still never calls
     * getDeclaredMethods(), so unrelated unavailable Android API types are not
     * resolved.
     */
    private static Object invokeThreeArgExactByHierarchy(
            Object receiver,
            String name,
            int first,
            int second,
            Object third) throws Throwable {

        if (third == null) {
            throw new NullPointerException("third argument is null");
        }

        java.util.LinkedHashSet<Class<?>> candidates =
                new java.util.LinkedHashSet<>();
        collectTypeCandidates(third.getClass(), candidates);

        Class<?> owner = receiver.getClass();
        while (owner != null) {
            for (Class<?> thirdType : candidates) {
                try {
                    Method method = owner.getDeclaredMethod(
                            name,
                            int.class,
                            int.class,
                            thirdType
                    );
                    method.setAccessible(true);
                    return method.invoke(receiver, first, second, third);
                } catch (NoSuchMethodException ignored) {
                    // Try the next exact candidate without enumerating methods.
                }
            }
            owner = owner.getSuperclass();
        }

        throw new NoSuchMethodException(
                receiver.getClass().getName() + "." + name
                        + "(int,int,<third>)");
    }

    private static void collectTypeCandidates(
            Class<?> type,
            java.util.LinkedHashSet<Class<?>> out) {
        if (type == null || !out.add(type)) {
            return;
        }

        for (Class<?> iface : type.getInterfaces()) {
            collectTypeCandidates(iface, out);
        }

        collectTypeCandidates(type.getSuperclass(), out);
    }

    /**
     * Move Chrome's real extensions menu button out of the injected Desktop
     * toolbar and place it in the phone bottom toolbar row.
     *
     * This intentionally moves the original View rather than creating a proxy:
     * the coordinator keeps its existing click listener and popup anchor.
     */
    private static void relocateExtensionsButtonToBottomToolbar(Activity activity) {
        activity.runOnUiThread(() -> {
            try {
                Resources res = activity.getResources();

                int extensionsButtonId = res.getIdentifier(
                        "extensions_menu_button", "id", TARGET_PACKAGE);
                int bottomToolbarId = res.getIdentifier(
                        "bottom_toolbar", "id", TARGET_PACKAGE);

                if (extensionsButtonId == 0 || bottomToolbarId == 0) {
                    log("relocation skipped: extensions_menu_button or bottom_toolbar id missing");
                    return;
                }

                View extensionsButton = activity.findViewById(extensionsButtonId);
                View bottomToolbar = activity.findViewById(bottomToolbarId);
                View contentView = activity.findViewById(android.R.id.content);

                if (extensionsButton == null) {
                    log("relocation skipped: extensions_menu_button not found");
                    return;
                }
                if (bottomToolbar == null) {
                    log("relocation skipped: bottom_toolbar not found");
                    return;
                }
                if (!(contentView instanceof FrameLayout)) {
                    log("relocation skipped: android.R.id.content is "
                            + (contentView == null
                            ? "null"
                            : contentView.getClass().getName()));
                    return;
                }

                FrameLayout content = (FrameLayout) contentView;
                FrameLayout host = EXTENSIONS_BUTTON_HOSTS.get(activity);
                boolean createdHost = false;

                if (host == null) {
                    int size = Math.max(
                            dp(activity, 48),
                            Math.max(
                                    extensionsButton.getMeasuredWidth(),
                                    extensionsButton.getMeasuredHeight()));

                    host = new FrameLayout(activity);
                    host.setClipChildren(false);
                    host.setClipToPadding(false);
                    host.setElevation(Math.max(
                            bottomToolbar.getElevation(),
                            extensionsButton.getElevation()));

                    FrameLayout.LayoutParams hostLp =
                            new FrameLayout.LayoutParams(size, size, Gravity.TOP | Gravity.START);
                    content.addView(host, hostLp);

                    ViewGroup oldParent =
                            extensionsButton.getParent() instanceof ViewGroup
                                    ? (ViewGroup) extensionsButton.getParent()
                                    : null;
                    if (oldParent != null) {
                        oldParent.removeView(extensionsButton);
                    }

                    FrameLayout.LayoutParams buttonLp =
                            new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    Gravity.CENTER);
                    host.addView(extensionsButton, buttonLp);

                    EXTENSIONS_BUTTON_HOSTS.put(activity, host);
                    createdHost = true;
                    log("moved real extensions_menu_button to bottom toolbar overlay");
                } else if (extensionsButton.getParent() != host) {
                    ViewGroup currentParent =
                            extensionsButton.getParent() instanceof ViewGroup
                                    ? (ViewGroup) extensionsButton.getParent()
                                    : null;
                    if (currentParent != null) {
                        currentParent.removeView(extensionsButton);
                    }
                    host.removeAllViews();
                    host.addView(
                            extensionsButton,
                            new FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    Gravity.CENTER));
                }

                final FrameLayout finalHost = host;
                final View finalBottomToolbar = bottomToolbar;

                Runnable position = () -> positionExtensionsButtonHost(
                        activity, content, finalBottomToolbar, finalHost);

                // Position now and after the current layout pass.
                position.run();
                bottomToolbar.post(position);

                // Bottom controls can translate/resize while scrolling. Pre-draw
                // keeps the moved button visually attached to that row.
                if (createdHost) {
                    bottomToolbar.getViewTreeObserver().addOnPreDrawListener(() -> {
                        if (finalHost.isAttachedToWindow()
                                && finalBottomToolbar.isAttachedToWindow()) {
                            position.run();
                        }
                        return true;
                    });
                }
            } catch (Throwable t) {
                log("extensions button relocation failed: " + stackSummary(t));
            }
        });
    }

    private static void positionExtensionsButtonHost(
            Activity activity,
            FrameLayout content,
            View bottomToolbar,
            FrameLayout host) {

        if (bottomToolbar.getWidth() <= 0 || bottomToolbar.getHeight() <= 0) {
            return;
        }

        int[] contentLocation = new int[2];
        int[] bottomLocation = new int[2];
        content.getLocationOnScreen(contentLocation);
        bottomToolbar.getLocationOnScreen(bottomLocation);

        View tabButton = findNamedView(
                bottomToolbar,
                activity.getResources(),
                "tab_switcher_button",
                "tab_switcher_mode_tab_switcher_button");
        View menuButton = findNamedView(
                bottomToolbar,
                activity.getResources(),
                "menu_button_wrapper",
                "menu_button");

        float centerX;
        if (tabButton != null && menuButton != null) {
            int[] tabLocation = new int[2];
            int[] menuLocation = new int[2];
            tabButton.getLocationOnScreen(tabLocation);
            menuButton.getLocationOnScreen(menuLocation);

            float tabCenter =
                    tabLocation[0] + tabButton.getWidth() / 2f;
            float menuCenter =
                    menuLocation[0] + menuButton.getWidth() / 2f;
            centerX = (tabCenter + menuCenter) / 2f;
        } else {
            // Matches the visual empty slot between the center tab button and
            // the right-side menu in the phone bottom toolbar.
            centerX = bottomLocation[0] + bottomToolbar.getWidth() * 2f / 3f;
        }

        float centerY =
                bottomLocation[1] + bottomToolbar.getHeight() / 2f;

        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) host.getLayoutParams();

        int left = Math.round(
                centerX - contentLocation[0] - host.getWidth() / 2f);
        int top = Math.round(
                centerY - contentLocation[1] - host.getHeight() / 2f);

        if (lp.leftMargin != left || lp.topMargin != top) {
            lp.leftMargin = left;
            lp.topMargin = top;
            host.setLayoutParams(lp);
        }

        host.setVisibility(bottomToolbar.getVisibility());
        host.setAlpha(bottomToolbar.getAlpha());
    }

    private static View findNamedView(
            View root,
            Resources resources,
            String... names) {

        for (String name : names) {
            int id = resources.getIdentifier(name, "id", TARGET_PACKAGE);
            if (id == 0) {
                continue;
            }

            View found = root.findViewById(id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(
                value * activity.getResources().getDisplayMetrics().density);
    }

    private static Object allocateWithoutConstructor(Class<?> type)
            throws Throwable {
        Throwable firstFailure = null;

        for (String unsafeName : new String[]{
                "sun.misc.Unsafe",
                "jdk.internal.misc.Unsafe"
        }) {
            try {
                Class<?> unsafeClass = Class.forName(unsafeName);
                Field field;
                try {
                    field = unsafeClass.getDeclaredField("theUnsafe");
                } catch (NoSuchFieldException e) {
                    field = unsafeClass.getDeclaredField("THE_ONE");
                }
                field.setAccessible(true);
                Object unsafe = field.get(null);

                Method allocateInstance = unsafeClass.getDeclaredMethod(
                        "allocateInstance", Class.class);
                allocateInstance.setAccessible(true);
                return allocateInstance.invoke(unsafe, type);
            } catch (Throwable t) {
                if (firstFailure == null) {
                    firstFailure = t;
                }
            }
        }

        throw new IllegalStateException(
                "Unable to allocate " + type.getName()
                        + " without constructor",
                firstFailure
        );
    }

    private static String stackSummary(Throwable t) {
        StringBuilder out = new StringBuilder();
        out.append(t.getClass().getName());
        if (t.getMessage() != null) {
            out.append(": ").append(t.getMessage());
        }

        StackTraceElement[] stack = t.getStackTrace();
        int max = Math.min(stack.length, 4);
        for (int i = 0; i < max; i++) {
            out.append(" | ").append(stack[i]);
        }
        return out.toString();
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
