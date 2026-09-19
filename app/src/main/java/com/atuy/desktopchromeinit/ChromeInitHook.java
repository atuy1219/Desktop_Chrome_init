package com.atuy.desktopchromeinit;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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

    private static final ThreadLocal<Boolean> POP_OUT_RECONCILING =
            new ThreadLocal<>();

    private static final Map<Activity, Object> ACTIVE_COORDINATORS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<Activity, Object> ACTIVE_EXTENSION_BRIDGES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<View, Boolean> BOTTOM_BAR_SLOT_HOOKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * RecyclerViews currently containing one temporary unpinned action that
     * exists only to anchor an extension popup. This action must never consume
     * another Bottom Bar slot, otherwise the native buttons move horizontally.
     */
    private static final Map<View, Boolean> TEMP_POPOUT_ACTION_LISTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<View, Integer> TEMP_POPOUT_BASE_ACTION_COUNTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<View, Boolean> CUSTOM_TAB_LAYOUT_HOOKS =
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
        installExtensionPopupWidthBridge(lpparam.classLoader);
        installPopoutUndoReconcile(lpparam.classLoader);
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
                final Object toolbarManagerObject = param.thisObject;
                try {
                    Object coordinator = XposedHelpers.getObjectField(
                            toolbarManagerObject, "J1");
                    if (coordinator == null) {
                        log("initializeWithNative finished with hns.J1 == null; scheduling proactive repair");
                    } else {
                        log("Extensions coordinator initialized normally");
                    }

                    Object controlContainer = XposedHelpers.getObjectField(
                            toolbarManagerObject, "d0");
                    if (!(controlContainer instanceof View)) {
                        log("proactive repair skipped: hns.d0 is not a View");
                        return;
                    }

                    View controlView = (View) controlContainer;
                    Activity activity = unwrapActivity(controlView.getContext());
                    if (activity == null) {
                        log("proactive repair skipped: could not resolve Activity from hns.d0 context");
                        return;
                    }

                    controlView.post(() -> {
                        try {
                            if (repairCoordinator(activity, toolbarManagerObject)) {
                                Object repairedCoordinator = XposedHelpers.getObjectField(
                                        toolbarManagerObject, "J1");
                                scheduleRelocateExtensionsContainer(
                                        activity, repairedCoordinator, 0);
                            }
                        } catch (Throwable t) {
                            log("proactive repair failed: " + stackSummary(t));
                        }
                    });
                } catch (Throwable t) {
                    log("could not inspect/proactively repair hns.J1 after init: " + t);
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
            ACTIVE_COORDINATORS.put(activity, existing);
            scheduleRelocateExtensionsContainer(activity, existing, 0);
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
            ACTIVE_COORDINATORS.put(activity, coordinator);
            scheduleRelocateExtensionsContainer(activity, coordinator, 0);
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
            injected.setInflatedId(
                    resources.getIdentifier(
                            "extensions_toolbar_container",
                            "id",
                            TARGET_PACKAGE));
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
    /**
     * Android Bottom Bar 1A fix.
     *
     * Do not detach extensions_menu_button from Chrome's mContainer. The
     * coordinator's showExtensionsMenu() searches the button inside mContainer,
     * and unpinned extension actions temporarily pop out through the sibling
     * extension_action_list before their popup is shown.
     *
     * Move the complete extensions_toolbar_container instead. This preserves:
     * - the app-menu "Extensions" entry;
     * - the Extensions button and its popup anchor;
     * - pinned action buttons;
     * - temporary pop-out action buttons for unpinned menu entries.
     */
    private static void scheduleRelocateExtensionsContainer(
            Activity activity, Object coordinator, int attempt) {
        if (activity == null || coordinator == null) {
            return;
        }

        activity.runOnUiThread(() -> {
            try {
                if (relocateExtensionsContainerToBottomBar(activity, coordinator)) {
                    return;
                }

                if (relocateExtensionsContainerToCustomTab(activity, coordinator)) {
                    return;
                }

                if (attempt < 50) {
                    View decor = activity.getWindow() != null
                            ? activity.getWindow().getDecorView()
                            : null;
                    if (decor != null) {
                        decor.postDelayed(
                                () -> scheduleRelocateExtensionsContainer(
                                        activity, coordinator, attempt + 1),
                                100L);
                    }
                } else {
                    log("extensions toolbar relocation gave up after 50 retries");
                }
            } catch (Throwable t) {
                log("extensions container relocation failed: " + stackSummary(t));
            }
        });
    }

    private static boolean relocateExtensionsContainerToBottomBar(
            Activity activity, Object coordinator) throws Throwable {

        Resources res = activity.getResources();
        int containerId = res.getIdentifier(
                "extensions_toolbar_container", "id", TARGET_PACKAGE);
        int bottomBarId = res.getIdentifier(
                "bottom_bar_container", "id", TARGET_PACKAGE);

        if (containerId == 0 || bottomBarId == 0) {
            log("relocation pending: extensions_toolbar_container or bottom_bar_container id missing");
            return false;
        }

        View extensionsContainer = activity.findViewById(containerId);
        if (!(extensionsContainer instanceof LinearLayout)) {
            // The injected ViewStub in older module runs did not set inflatedId.
            // Resolve mContainer from rr9 directly as a compatibility fallback.
            extensionsContainer = findCoordinatorLinearContainer(coordinator);
        }
        View bottomBarView = activity.findViewById(bottomBarId);

        if (!(extensionsContainer instanceof LinearLayout)) {
            log("relocation pending: extensions_toolbar_container not inflated yet");
            return false;
        }

        if (!(bottomBarView instanceof LinearLayout)) {
            log("relocation pending: bottom_bar_container not available yet");
            return false;
        }

        LinearLayout extensionsToolbar = (LinearLayout) extensionsContainer;
        LinearLayout bottomBar = (LinearLayout) bottomBarView;

        if (extensionsToolbar.getParent() != bottomBar) {
            View tabSwitcher = findNamedView(
                    bottomBar, res, "tab_switcher_button");

            int insertIndex = bottomBar.getChildCount();
            if (tabSwitcher != null) {
                View directTabChild = directChildOf(bottomBar, tabSwitcher);
                if (directTabChild != null) {
                    int tabIndex = bottomBar.indexOfChild(directTabChild);
                    if (tabIndex >= 0) {
                        insertIndex = tabIndex + 1;
                    }
                }
            } else {
                View appMenu = findNamedView(
                        bottomBar,
                        res,
                        "app_menu_button",
                        "menu_button",
                        "menu_button_wrapper",
                        "app_menu_stub");
                if (appMenu != null) {
                    View directMenuChild = directChildOf(bottomBar, appMenu);
                    if (directMenuChild != null) {
                        int menuIndex = bottomBar.indexOfChild(directMenuChild);
                        if (menuIndex >= 0) {
                            insertIndex = menuIndex;
                        }
                    }
                }
            }

            ViewGroup oldParent =
                    extensionsToolbar.getParent() instanceof ViewGroup
                            ? (ViewGroup) extensionsToolbar.getParent()
                            : null;
            if (oldParent != null) {
                oldParent.removeView(extensionsToolbar);
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;

            bottomBar.addView(
                    extensionsToolbar,
                    Math.min(insertIndex, bottomBar.getChildCount()),
                    lp);

            extensionsToolbar.setVisibility(View.VISIBLE);
            extensionsToolbar.setAlpha(1.0f);

            log("moved complete extensions_toolbar_container into Android Bottom Bar");
        }

        configureBottomBarEqualSlots(bottomBar, extensionsToolbar);

        // ExtensionActionListRecyclerView uses this root for transitions while
        // temporarily popping out an unpinned action. The original phone toolbar
        // is no longer the correct visual root after reparenting.
        try {
            Object actionListCoordinator =
                    XposedHelpers.getObjectField(coordinator, "V");
            if (actionListCoordinator != null) {
                Object recycler = XposedHelpers.getObjectField(
                        actionListCoordinator, "T");
                if (recycler != null) {
                    XposedHelpers.setObjectField(recycler, "I1", bottomBar);
                }
            }
        } catch (Throwable t) {
            log("could not rebind extension action transition root: "
                    + stackSummary(t));
        }

        return true;
    }

    /**
     * Android Bottom Bar lays each native button inside a 0dp/weight=1
     * BottomBarButtonContainer. Treat each extension icon as one identical
     * slot as well:
     *
     *   new tab | tab switcher | [pinned actions...] | extensions | menu
     *
     * With one pinned action the Extensions container therefore consumes two
     * slots, but each actual button still has exactly one slot of width.
     */
    private static void configureBottomBarEqualSlots(
            LinearLayout bottomBar, LinearLayout extensionsToolbar) {
        try {
            if (!BOTTOM_BAR_SLOT_HOOKS.containsKey(extensionsToolbar)) {
                BOTTOM_BAR_SLOT_HOOKS.put(extensionsToolbar, Boolean.TRUE);

                View.OnLayoutChangeListener listener =
                        (v, left, top, right, bottom,
                                oldLeft, oldTop, oldRight, oldBottom) ->
                                applyBottomBarEqualSlots(
                                        bottomBar, extensionsToolbar);

                bottomBar.addOnLayoutChangeListener(listener);
                extensionsToolbar.addOnLayoutChangeListener(listener);

                View actionList = findNamedView(
                        extensionsToolbar,
                        extensionsToolbar.getResources(),
                        "extension_action_list");
                if (actionList != null) {
                    actionList.addOnLayoutChangeListener(listener);
                }
            }

            applyBottomBarEqualSlots(bottomBar, extensionsToolbar);
            bottomBar.post(() -> applyBottomBarEqualSlots(
                    bottomBar, extensionsToolbar));
        } catch (Throwable t) {
            log("bottom bar equal-slot setup failed: " + stackSummary(t));
        }
    }

    private static void applyBottomBarEqualSlots(
            LinearLayout bottomBar, LinearLayout extensionsToolbar) {
        try {
            if (bottomBar.getWidth() <= 0) {
                return;
            }

            Resources res = extensionsToolbar.getResources();
            View actionListView = findNamedView(
                    extensionsToolbar, res, "extension_action_list");
            View menuButton = findNamedView(
                    extensionsToolbar, res, "extensions_menu_button");

            int actionCount = 0;
            if (actionListView instanceof ViewGroup
                    && actionListView.getVisibility() != View.GONE) {
                actionCount = ((ViewGroup) actionListView).getChildCount();
            }

            boolean hasTemporaryPopout =
                    actionListView != null
                            && Boolean.TRUE.equals(
                                    TEMP_POPOUT_ACTION_LISTS.get(actionListView));

            // Permanent geometry is based only on pinned actions. A temporary
            // unpinned action used as a popup anchor is squeezed into the
            // already-reserved Extensions region, so New Tab / Tab Switcher /
            // app menu never move while a popup opens.
            int pinnedActionCount;
            if (hasTemporaryPopout) {
                Integer baseCount =
                        TEMP_POPOUT_BASE_ACTION_COUNTS.get(actionListView);
                pinnedActionCount =
                        baseCount != null
                                ? Math.max(0, baseCount)
                                : Math.max(0, actionCount - 1);
            } else {
                pinnedActionCount = Math.max(0, actionCount);
            }
            int extensionSlots = Math.max(1, pinnedActionCount + 1);

            ViewGroup.LayoutParams containerBase =
                    extensionsToolbar.getLayoutParams();
            LinearLayout.LayoutParams containerLp;
            if (containerBase instanceof LinearLayout.LayoutParams) {
                containerLp = (LinearLayout.LayoutParams) containerBase;
            } else {
                containerLp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.MATCH_PARENT);
            }

            boolean containerChanged =
                    containerLp.width != 0
                            || containerLp.weight != extensionSlots
                            || containerLp.height
                                    != ViewGroup.LayoutParams.MATCH_PARENT;
            containerLp.width = 0;
            containerLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            containerLp.weight = extensionSlots;
            containerLp.gravity = android.view.Gravity.CENTER_VERTICAL;
            if (containerChanged) {
                extensionsToolbar.setLayoutParams(containerLp);
            }

            // Center the whole compact Extensions cluster inside its logical
            // N-slot allocation. This produces:
            //   o   o   o   o
            //   o   o   oo  o
            // instead of stretching the two extension icons apart.
            extensionsToolbar.setGravity(android.view.Gravity.CENTER);

            int buttonWidth = 0;
            View referenceButton = findNamedView(
                    bottomBar,
                    res,
                    "new_tab_button",
                    "tab_switcher_button",
                    "menu_button");
            if (referenceButton != null) {
                buttonWidth = Math.max(
                        referenceButton.getWidth(),
                        referenceButton.getMeasuredWidth());
                ViewGroup.LayoutParams referenceLp =
                        referenceButton.getLayoutParams();
                if (buttonWidth <= 0
                        && referenceLp != null
                        && referenceLp.width > 0) {
                    buttonWidth = referenceLp.width;
                }
            }
            if (buttonWidth <= 0) {
                buttonWidth = Math.round(
                        48f * res.getDisplayMetrics().density);
            }

            int nativeSlots = 0;
            for (int i = 0; i < bottomBar.getChildCount(); i++) {
                View child = bottomBar.getChildAt(i);
                if (child == extensionsToolbar
                        || child.getVisibility() == View.GONE) {
                    continue;
                }
                nativeSlots++;
            }

            int totalLogicalSlots = nativeSlots + extensionSlots;
            int logicalSlotWidth =
                    totalLogicalSlots > 0
                            ? bottomBar.getWidth() / totalLogicalSlots
                            : buttonWidth;
            int extensionRegionWidth =
                    Math.max(buttonWidth, logicalSlotWidth * extensionSlots);

            int visibleExtensionButtons = actionCount + 1;
            int packedButtonWidth = buttonWidth;
            if (hasTemporaryPopout && visibleExtensionButtons > 0) {
                // Keep the native slots frozen. During an unpinned popup the
                // extra temporary icon shares the existing Extensions region
                // with the menu button (and any pinned icons) instead of
                // expanding that region.
                packedButtonWidth = Math.max(
                        Math.round(
                                36f * res.getDisplayMetrics().density),
                        extensionRegionWidth / visibleExtensionButtons);
                packedButtonWidth = Math.min(buttonWidth, packedButtonWidth);
            }

            if (menuButton != null) {
                ViewGroup.LayoutParams old = menuButton.getLayoutParams();
                LinearLayout.LayoutParams lp =
                        old instanceof LinearLayout.LayoutParams
                                ? (LinearLayout.LayoutParams) old
                                : new LinearLayout.LayoutParams(
                                        packedButtonWidth,
                                        ViewGroup.LayoutParams.MATCH_PARENT);
                if (lp.width != packedButtonWidth || lp.weight != 0f) {
                    lp.width = packedButtonWidth;
                    lp.weight = 0f;
                    menuButton.setLayoutParams(lp);
                }
            }

            if (actionListView != null) {
                int wantedWidth = actionCount * packedButtonWidth;
                ViewGroup.LayoutParams old = actionListView.getLayoutParams();
                LinearLayout.LayoutParams lp =
                        old instanceof LinearLayout.LayoutParams
                                ? (LinearLayout.LayoutParams) old
                                : new LinearLayout.LayoutParams(
                                        wantedWidth,
                                        ViewGroup.LayoutParams.MATCH_PARENT);
                if (lp.width != wantedWidth || lp.weight != 0f) {
                    lp.width = wantedWidth;
                    lp.weight = 0f;
                    actionListView.setLayoutParams(lp);
                }

                if (actionListView instanceof ViewGroup && actionCount > 0) {
                    ViewGroup group = (ViewGroup) actionListView;
                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        ViewGroup.LayoutParams childLp = child.getLayoutParams();
                        if (childLp != null && childLp.width != packedButtonWidth) {
                            childLp.width = packedButtonWidth;
                            child.setLayoutParams(childLp);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            log("bottom bar equal-slot apply failed: " + stackSummary(t));
        }
    }

    /**
     * Custom Tab uses an overlay FrameLayout for end-aligned action buttons.
     * The adaptive optional_button is where the Translate button is displayed.
     * Keep Chrome's optional-button reservation in the positioning model, hide
     * the actual optional button, and place the complete Extensions container
     * at exactly the same gravity/margins.
     *
     * Moving the complete container (rather than only extensions_menu_button)
     * keeps showExtensionsMenu() and temporary unpinned-action popup anchors
     * functional.
     */
    private static boolean relocateExtensionsContainerToCustomTab(
            Activity activity, Object coordinator) throws Throwable {
        Resources res = activity.getResources();

        int toolbarId = res.getIdentifier("toolbar", "id", TARGET_PACKAGE);
        if (toolbarId == 0) {
            return false;
        }

        View toolbar = activity.findViewById(toolbarId);
        if (toolbar == null
                || !toolbar.getClass().getName().contains(
                        ".customtabs.features.toolbar.CustomTabToolbar")) {
            return false;
        }

        View extensionsContainer = findCoordinatorLinearContainer(coordinator);
        if (!(extensionsContainer instanceof LinearLayout)) {
            log("CCT relocation pending: Extensions container not inflated");
            return false;
        }

        int actionsId = res.getIdentifier(
                "action_buttons", "id", TARGET_PACKAGE);
        int optionalId = res.getIdentifier(
                "optional_button", "id", TARGET_PACKAGE);

        View actionButtonsView =
                actionsId != 0 ? toolbar.findViewById(actionsId) : null;
        View optionalButton =
                optionalId != 0 ? toolbar.findViewById(optionalId) : null;

        if (!(actionButtonsView instanceof FrameLayout)) {
            log("CCT relocation pending: action_buttons not available");
            return false;
        }
        if (optionalButton == null) {
            log("CCT relocation pending: optional/Translate button not inflated");
            return false;
        }

        FrameLayout actionButtons = (FrameLayout) actionButtonsView;
        LinearLayout extensionsToolbar = (LinearLayout) extensionsContainer;

        syncCustomTabExtensionsToOptionalSlot(
                toolbar,
                actionButtons,
                optionalButton,
                extensionsToolbar);

        if (!CUSTOM_TAB_LAYOUT_HOOKS.containsKey(toolbar)) {
            CUSTOM_TAB_LAYOUT_HOOKS.put(toolbar, Boolean.TRUE);
            toolbar.addOnLayoutChangeListener(
                    (v, left, top, right, bottom,
                            oldLeft, oldTop, oldRight, oldBottom) -> {
                        try {
                            View currentOptional =
                                    optionalId != 0
                                            ? toolbar.findViewById(optionalId)
                                            : null;
                            if (currentOptional != null) {
                                syncCustomTabExtensionsToOptionalSlot(
                                        toolbar,
                                        actionButtons,
                                        currentOptional,
                                        extensionsToolbar);
                            }
                        } catch (Throwable t) {
                            log("CCT extension-slot sync failed: "
                                    + stackSummary(t));
                        }
                    });
        }

        try {
            Object actionListCoordinator =
                    XposedHelpers.getObjectField(coordinator, "V");
            if (actionListCoordinator != null) {
                Object recycler = XposedHelpers.getObjectField(
                        actionListCoordinator, "T");
                if (recycler != null) {
                    XposedHelpers.setObjectField(
                            recycler, "I1", actionButtons);
                }
            }
        } catch (Throwable t) {
            log("CCT transition-root rebind failed: " + stackSummary(t));
        }

        return true;
    }

    private static void syncCustomTabExtensionsToOptionalSlot(
            View toolbar,
            FrameLayout actionButtons,
            View optionalButton,
            LinearLayout extensionsToolbar) {

        ViewGroup.LayoutParams optionalBase = optionalButton.getLayoutParams();
        if (!(optionalBase instanceof FrameLayout.LayoutParams)) {
            return;
        }

        FrameLayout.LayoutParams source =
                (FrameLayout.LayoutParams) optionalBase;

        optionalButton.setVisibility(View.GONE);
        optionalButton.setClickable(false);
        optionalButton.setFocusable(false);

        if (extensionsToolbar.getParent() != actionButtons) {
            ViewGroup oldParent =
                    extensionsToolbar.getParent() instanceof ViewGroup
                            ? (ViewGroup) extensionsToolbar.getParent()
                            : null;
            if (oldParent != null) {
                oldParent.removeView(extensionsToolbar);
            }
            actionButtons.addView(extensionsToolbar);
            log("moved extensions toolbar into Custom Tab Translate slot");
        }

        FrameLayout.LayoutParams target =
                extensionsToolbar.getLayoutParams()
                                instanceof FrameLayout.LayoutParams
                        ? (FrameLayout.LayoutParams)
                                extensionsToolbar.getLayoutParams()
                        : new FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.MATCH_PARENT);

        int sourceStart = source.getMarginStart();
        int sourceEnd = source.getMarginEnd();

        boolean changed =
                target.width != ViewGroup.LayoutParams.WRAP_CONTENT
                        || target.height != source.height
                        || target.gravity != source.gravity
                        || target.leftMargin != source.leftMargin
                        || target.topMargin != source.topMargin
                        || target.rightMargin != source.rightMargin
                        || target.bottomMargin != source.bottomMargin
                        || target.getMarginStart() != sourceStart
                        || target.getMarginEnd() != sourceEnd;

        if (changed) {
            target.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            target.height = source.height;
            target.gravity = source.gravity;
            target.leftMargin = source.leftMargin;
            target.topMargin = source.topMargin;
            target.rightMargin = source.rightMargin;
            target.bottomMargin = source.bottomMargin;
            target.setMarginStart(sourceStart);
            target.setMarginEnd(sourceEnd);
            extensionsToolbar.setLayoutParams(target);
        }

        extensionsToolbar.setVisibility(View.VISIBLE);
        extensionsToolbar.setAlpha(1.0f);
        extensionsToolbar.bringToFront();
    }

    private static Activity unwrapActivity(Context context) {
        Context current = context;
        while (current != null) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            if (!(current instanceof ContextWrapper)) {
                return null;
            }
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) {
                return null;
            }
            current = next;
        }
        return null;
    }

    private static View directChildOf(ViewGroup parent, View descendant) {
        View current = descendant;
        while (current != null) {
            if (current.getParent() == parent) {
                return current;
            }
            if (!(current.getParent() instanceof View)) {
                return null;
            }
            current = (View) current.getParent();
        }
        return null;
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

    /**
     * 801004974 release DEX mapping:
     *   zo9 = ExtensionActionListMediator
     *   zo9.n() = undoPopout()
     *   zo9.g() = reconcileActionItems()
     *
     * Desktop/Tablet normally gets a toolbar width pass immediately after
     * undoPopout(), which reconciles the model and removes the temporary
     * unpinned action. ToolbarPhone never performs that pass, leaving the
     * icon visible. Hook the exact state transition instead of popup.destroy().
     */
    private static void installPopoutUndoReconcile(ClassLoader classLoader) {
        Class<?> mediatorClass = XposedHelpers.findClassIfExists("zo9", classLoader);
        if (mediatorClass == null) {
            log("zo9 mediator not found; pop-out cleanup unavailable");
            return;
        }

        try {
            Method undoPopout = mediatorClass.getDeclaredMethod("n");
            undoPopout.setAccessible(true);

            XposedBridge.hookMethod(
                    undoPopout,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(POP_OUT_RECONCILING.get())) {
                                return;
                            }

                            POP_OUT_RECONCILING.set(Boolean.TRUE);
                            try {
                                Method reconcile = param.thisObject.getClass()
                                        .getDeclaredMethod("g");
                                reconcile.setAccessible(true);
                                reconcile.invoke(param.thisObject);

                                scheduleTemporaryPopoutCleanup();
                                log("undoPopout cleanup: reconciled action list; waiting for temporary icon removal");
                            } catch (Throwable t) {
                                log("undoPopout cleanup failed: " + stackSummary(t));
                            } finally {
                                POP_OUT_RECONCILING.remove();
                            }
                        }
                    });

            log("installed zo9.n() undoPopout reconcile hook");
        } catch (Throwable t) {
            log("could not hook zo9.n() undoPopout: " + stackSummary(t));
        }
    }

    private static void scheduleTemporaryPopoutCleanup() {
        java.util.ArrayList<View> pending = new java.util.ArrayList<>();
        synchronized (TEMP_POPOUT_ACTION_LISTS) {
            pending.addAll(TEMP_POPOUT_ACTION_LISTS.keySet());
        }

        for (View actionList : pending) {
            if (actionList != null) {
                waitForTemporaryActionRemoval(actionList, 0);
            }
        }
    }

    private static void waitForTemporaryActionRemoval(
            View actionListView, int attempt) {
        Integer baseValue =
                TEMP_POPOUT_BASE_ACTION_COUNTS.get(actionListView);
        if (baseValue == null) {
            TEMP_POPOUT_ACTION_LISTS.remove(actionListView);
            refreshBottomBarGeometry();
            return;
        }

        final int baseActionCount = baseValue;
        actionListView.postDelayed(() -> {
            try {
                int childCount =
                        actionListView instanceof ViewGroup
                                ? ((ViewGroup) actionListView).getChildCount()
                                : 0;

                if (childCount <= baseActionCount || attempt >= 30) {
                    TEMP_POPOUT_ACTION_LISTS.remove(actionListView);
                    TEMP_POPOUT_BASE_ACTION_COUNTS.remove(actionListView);

                    refreshBottomBarGeometry();

                    log("temporary popout cleanup complete: childCount="
                            + childCount
                            + " baseActionCount="
                            + baseActionCount
                            + " attempts="
                            + attempt);
                    return;
                }

                waitForTemporaryActionRemoval(
                        actionListView, attempt + 1);
            } catch (Throwable t) {
                log("temporary popout cleanup polling failed: "
                        + stackSummary(t));
            }
        }, attempt == 0 ? 16L : 32L);
    }

    private static void refreshBottomBarGeometry() {
        try {
            synchronized (ACTIVE_COORDINATORS) {
                for (Map.Entry<Activity, Object> entry
                        : ACTIVE_COORDINATORS.entrySet()) {
                    Activity activity = entry.getKey();
                    Object coordinator = entry.getValue();

                    if (activity == null
                            || coordinator == null
                            || activity.isFinishing()
                            || activity.isDestroyed()) {
                        continue;
                    }

                    int bottomBarId = activity.getResources().getIdentifier(
                            "bottom_bar_container",
                            "id",
                            TARGET_PACKAGE);
                    View bottomBarView =
                            bottomBarId != 0
                                    ? activity.findViewById(bottomBarId)
                                    : null;
                    LinearLayout extensionsToolbar =
                            findCoordinatorLinearContainer(coordinator);

                    if (bottomBarView instanceof LinearLayout
                            && extensionsToolbar != null
                            && extensionsToolbar.getParent() == bottomBarView) {
                        LinearLayout bottomBar =
                                (LinearLayout) bottomBarView;
                        applyBottomBarEqualSlots(
                                bottomBar, extensionsToolbar);
                        bottomBar.requestLayout();
                        bottomBar.post(() -> {
                            applyBottomBarEqualSlots(
                                    bottomBar, extensionsToolbar);
                            bottomBar.requestLayout();
                        });
                    }
                }
            }
        } catch (Throwable t) {
            log("Bottom Bar geometry refresh failed: " + stackSummary(t));
        }
    }

    private static void rememberExtensionBridge(Object bridge) {
        if (bridge == null) return;

        synchronized (ACTIVE_COORDINATORS) {
            for (Map.Entry<Activity, Object> entry : ACTIVE_COORDINATORS.entrySet()) {
                Activity activity = entry.getKey();
                if (activity != null
                        && !activity.isFinishing()
                        && !activity.isDestroyed()) {
                    ACTIVE_EXTENSION_BRIDGES.put(activity, bridge);
                    return;
                }
            }
        }
    }

    /**
     * Popup dismissal calls ExtensionActionListMediator.closePopup(), which in
     * turn calls undoPopout(). On a real Desktop/Tablet toolbar the following
     * toolbar-width pass reconciles the model and removes the temporary action.
     * ToolbarPhone never performs that pass.
     *
     * Hook ExtensionActionPopup.destroy() and post one task so closePopup() can
     * finish undoPopout() first. Then re-run the bridge observer notification,
     * which causes reconcileActionItems() and removes the temporary icon.
     */
    private static void installExtensionPopupDismissCleanup(ClassLoader classLoader) {
        Class<?> popupClass = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.toolbar.extensions.ExtensionActionPopup",
                classLoader);
        if (popupClass == null) {
            log("ExtensionActionPopup not found; dismiss cleanup unavailable");
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    popupClass,
                    "destroy",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                android.os.Handler handler =
                                        new android.os.Handler(android.os.Looper.getMainLooper());
                                handler.post(() -> reconcileAfterPopupDismiss());
                            } catch (Throwable t) {
                                log("popup dismiss cleanup scheduling failed: "
                                        + stackSummary(t));
                            }
                        }
                    });
            log("installed ExtensionActionPopup.destroy dismiss cleanup");
        } catch (Throwable t) {
            log("could not hook ExtensionActionPopup.destroy: "
                    + stackSummary(t));
        }
    }

    private static void reconcileAfterPopupDismiss() {
        try {
            int count = 0;
            synchronized (ACTIVE_EXTENSION_BRIDGES) {
                for (Map.Entry<Activity, Object> entry
                        : ACTIVE_EXTENSION_BRIDGES.entrySet()) {
                    Activity activity = entry.getKey();
                    Object bridge = entry.getValue();
                    if (activity == null
                            || bridge == null
                            || activity.isFinishing()
                            || activity.isDestroyed()) {
                        continue;
                    }

                    try {
                        Method refresh = bridge.getClass()
                                .getDeclaredMethod("onPinnedActionsChanged");
                        refresh.setAccessible(true);
                        refresh.invoke(bridge);
                        count++;
                    } catch (Throwable t) {
                        log("popup dismiss reconcile failed: " + stackSummary(t));
                    }
                }
            }
            log("popup dismiss cleanup: reconciled " + count + " active bridge(s)");
        } catch (Throwable t) {
            log("popup dismiss cleanup failed: " + stackSummary(t));
        }
    }

    /**
     * ExtensionsToolbarBridge is JNI-facing and its class/method name is kept
     * in the release APK. Native executeAction() eventually calls triggerPopup().
     * At that point ExtensionActionListMediator has already set its temporary
     * popped-out action ID, but Phone Toolbar never runs Desktop's width
     * consumers. Force their equivalent here.
     */
    private static void installExtensionPopupWidthBridge(ClassLoader classLoader) {
        Class<?> bridgeClass = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.ui.extensions.ExtensionsToolbarBridge",
                classLoader);
        if (bridgeClass == null) {
            log("ExtensionsToolbarBridge not found; popup width bridge unavailable");
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    bridgeClass,
                    "triggerPopup",
                    String.class,
                    long.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                rememberExtensionBridge(param.thisObject);
                                forcePendingExtensionActionLayout(
                                        param.thisObject,
                                        (String) param.args[0]);
                            } catch (Throwable t) {
                                log("popup width bridge failed: " + stackSummary(t));
                            }
                        }
                    });
            log("installed ExtensionsToolbarBridge.triggerPopup width bridge");
        } catch (Throwable t) {
            log("could not hook ExtensionsToolbarBridge.triggerPopup: "
                    + stackSummary(t));
        }
    }

    private static void forcePendingExtensionActionLayout(
            Object extensionsToolbarBridge,
            String actionId) throws Throwable {
        Object coordinator = null;
        Activity activity = null;

        synchronized (ACTIVE_COORDINATORS) {
            for (Map.Entry<Activity, Object> entry
                    : ACTIVE_COORDINATORS.entrySet()) {
                Activity candidate = entry.getKey();
                if (candidate != null
                        && !candidate.isFinishing()
                        && !candidate.isDestroyed()) {
                    activity = candidate;
                    coordinator = entry.getValue();
                    break;
                }
            }
        }

        if (coordinator == null || activity == null) {
            log("popup bridge: no active coordinator");
            return;
        }

        Object actionListCoordinator =
                XposedHelpers.getObjectField(coordinator, "V");
        if (actionListCoordinator == null) {
            log("popup bridge: rr9.V is null");
            return;
        }

        Method getButtonMethod = null;
        for (Method method : actionListCoordinator.getClass().getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1
                    && params[0] == String.class
                    && View.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                getButtonMethod = method;
                break;
            }
        }

        if (getButtonMethod == null) {
            log("popup bridge: String->View anchor method missing on "
                    + actionListCoordinator.getClass().getName());
            dumpDeclaredMethodShapes(actionListCoordinator.getClass());
            return;
        }

        Object recycler = null;
        try {
            recycler = XposedHelpers.getObjectField(
                    actionListCoordinator, "T");
        } catch (Throwable ignored) {}

        if (!(recycler instanceof View)) {
            log("popup bridge: action RecyclerView not found");
            return;
        }

        View recyclerView = (View) recycler;

        boolean actionAlreadyVisible = false;
        try {
            Object existingAnchor =
                    getButtonMethod.invoke(actionListCoordinator, actionId);
            actionAlreadyVisible = existingAnchor instanceof View;
        } catch (Throwable t) {
            log("popup bridge: pre-reconcile anchor check failed: "
                    + stackSummary(t));
        }

        if (!actionAlreadyVisible) {
            int baseActionCount = 0;
            if (recyclerView instanceof ViewGroup) {
                baseActionCount = ((ViewGroup) recyclerView).getChildCount();
            }
            TEMP_POPOUT_ACTION_LISTS.put(recyclerView, Boolean.TRUE);
            TEMP_POPOUT_BASE_ACTION_COUNTS.put(
                    recyclerView, baseActionCount);
            log("popup bridge: temporary unpinned action will reuse existing Bottom Bar slot; baseActionCount="
                    + baseActionCount);
        }

        // requestShowPopup()/requestActionVisibility() has already run by the
        // time this after-hook executes, so mPoppedOutActionId is populated.
        // The Phone toolbar never calls the Desktop width consumers, and R8
        // has removed their coordinator wrappers entirely in this build.
        //
        // onPinnedActionsChanged() is @CalledByNative and therefore kept. Its
        // observer path calls ExtensionActionListMediator.reconcileActionItems(),
        // which is exactly what is needed to materialize the temporary
        // unpinned action model without changing the actual pinned state.
        try {
            Method refresh = extensionsToolbarBridge.getClass()
                    .getDeclaredMethod("onPinnedActionsChanged");
            refresh.setAccessible(true);
            refresh.invoke(extensionsToolbarBridge);
            log("popup bridge: forced action-list reconcile via onPinnedActionsChanged()");
        } catch (Throwable t) {
            if (!actionAlreadyVisible) {
                TEMP_POPOUT_ACTION_LISTS.remove(recyclerView);
                TEMP_POPOUT_BASE_ACTION_COUNTS.remove(recyclerView);
            }
            log("popup bridge: reconcile trigger failed: " + stackSummary(t));
            return;
        }
        recyclerView.requestLayout();

        log("popup bridge: waiting for temporary action anchor " + actionId);

        pollForExtensionAnchorAndFlush(
                actionId,
                actionListCoordinator,
                getButtonMethod,
                recyclerView,
                0);
    }

    private static void pollForExtensionAnchorAndFlush(
            String actionId,
            Object actionListCoordinator,
            Method getButtonMethod,
            View recyclerView,
            int attempt) {

        recyclerView.postDelayed(() -> {
            try {
                recyclerView.requestLayout();

                View anchor = null;
                if (getButtonMethod != null) {
                    try {
                        Object value = getButtonMethod.invoke(
                                actionListCoordinator, actionId);
                        if (value instanceof View) {
                            anchor = (View) value;
                        }
                    } catch (Throwable t) {
                        log("popup anchor lookup failed: " + stackSummary(t));
                    }
                }

                if (anchor != null
                        && anchor.isAttachedToWindow()
                        && anchor.getWidth() > 0
                        && anchor.getHeight() > 0) {
                    log("popup bridge: temporary anchor ready "
                            + anchor.getWidth() + "x" + anchor.getHeight()
                            + "; flushing pending popup callback");

                    if (flushPendingRecyclerRunnables(recyclerView)) {
                        return;
                    }

                    // The normal layout listener may have already consumed the
                    // runnable between polling and this point. Trigger another
                    // layout so any still-queued callback is delivered.
                    recyclerView.requestLayout();
                    recyclerView.post(recyclerView::requestLayout);
                    return;
                }

                if (attempt < 30) {
                    pollForExtensionAnchorAndFlush(
                            actionId,
                            actionListCoordinator,
                            getButtonMethod,
                            recyclerView,
                            attempt + 1);
                } else {
                    log("popup bridge: temporary anchor never became ready");
                    dumpPopupRecyclerState(
                            actionId,
                            actionListCoordinator,
                            recyclerView);
                }
            } catch (Throwable t) {
                log("popup anchor polling failed: " + stackSummary(t));
            }
        }, attempt == 0 ? 16L : 32L);
    }

    @SuppressWarnings("unchecked")
    private static boolean flushPendingRecyclerRunnables(View recyclerView) {
        try {
            Class<?> type = recyclerView.getClass();

            while (type != null
                    && type.getName().startsWith(
                            "org.chromium.chrome.browser.toolbar.extensions")) {
                for (Field field : type.getDeclaredFields()) {
                    if (!java.util.List.class.isAssignableFrom(field.getType())) {
                        continue;
                    }

                    field.setAccessible(true);
                    Object value = field.get(recyclerView);
                    if (!(value instanceof java.util.List)) {
                        continue;
                    }

                    java.util.List<?> list = (java.util.List<?>) value;
                    if (list.isEmpty()) {
                        continue;
                    }

                    boolean allRunnable = true;
                    java.util.ArrayList<Runnable> runnables =
                            new java.util.ArrayList<>();
                    for (Object item : list) {
                        if (!(item instanceof Runnable)) {
                            allRunnable = false;
                            break;
                        }
                        runnables.add((Runnable) item);
                    }

                    if (!allRunnable || runnables.isEmpty()) {
                        continue;
                    }

                    ((java.util.List<Object>) list).clear();
                    log("popup bridge: flushing "
                            + runnables.size() + " pending RecyclerView runnable(s)");

                    for (Runnable runnable : runnables) {
                        recyclerView.post(runnable);
                    }
                    return true;
                }
                type = type.getSuperclass();
            }
        } catch (Throwable t) {
            log("popup runnable flush failed: " + stackSummary(t));
        }
        return false;
    }

    private static void dumpPopupRecyclerState(
            String actionId,
            Object actionListCoordinator,
            View recyclerView) {
        try {
            StringBuilder out = new StringBuilder();
            out.append("popup debug action=").append(actionId)
                    .append(" recycler=")
                    .append(recyclerView.getClass().getName())
                    .append(" size=")
                    .append(recyclerView.getWidth())
                    .append("x")
                    .append(recyclerView.getHeight())
                    .append(" shown=")
                    .append(recyclerView.isShown());

            if (recyclerView instanceof ViewGroup) {
                out.append(" childCount=")
                        .append(((ViewGroup) recyclerView).getChildCount());
            }

            log(out.toString());
        } catch (Throwable t) {
            log("popup debug dump failed: " + stackSummary(t));
        }
    }

    private static void dumpDeclaredMethodShapes(Class<?> type) {
        try {
            StringBuilder out = new StringBuilder();
            out.append("declared methods on ").append(type.getName()).append(": ");
            Method[] methods = type.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++) {
                Method method = methods[i];
                out.append(method.getName()).append("(");
                Class<?>[] params = method.getParameterTypes();
                for (int p = 0; p < params.length; p++) {
                    if (p > 0) out.append(",");
                    out.append(params[p].getSimpleName());
                }
                out.append(")->").append(method.getReturnType().getSimpleName());
                if (i + 1 < methods.length) out.append("; ");
            }
            log(out.toString());
        } catch (Throwable t) {
            log("could not dump method shapes: " + stackSummary(t));
        }
    }

    private static LinearLayout findCoordinatorLinearContainer(Object coordinator) {
        try {
            for (Field field : coordinator.getClass().getDeclaredFields()) {
                if (!LinearLayout.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(coordinator);
                if (value instanceof LinearLayout) {
                    return (LinearLayout) value;
                }
            }
        } catch (Throwable t) {
            log("could not resolve coordinator LinearLayout container: "
                    + stackSummary(t));
        }
        return null;
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
