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
 * Version-independent repair for Google Chrome Desktop Android builds running
 * on phone layouts.
 *
 * R8 symbols are resolved from the installed Chrome DEX at runtime. Normal
 * operation therefore does not depend on obfuscated class, field, method, or
 * synthetic-class names.
 */
public final class ChromeInitHook implements IXposedHookLoadPackage {
    private static final String TAG = "DesktopChromeInit";
    private static final String TARGET_PACKAGE = "com.android.chrome";

    private static volatile ClassLoader chromeClassLoader;
    private static volatile ChromeDexResolver.Symbols resolvedSymbols;
    private static volatile Class<?> toolbarManagerClass;

    private static final ThreadLocal<Boolean> EXTENSIONS_ACTION =
            new ThreadLocal<>();

    private static final ThreadLocal<ToolbarSwap> TOOLBAR_SWAP =
            new ThreadLocal<>();

    private static final Map<Activity, Object> ACTIVE_TOOLBAR_MANAGERS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<Activity, Object> ACTIVE_COORDINATORS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<Activity, Object> ACTIVE_EXTENSION_BRIDGES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<View, Boolean> BOTTOM_BAR_SLOT_HOOKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<View, Boolean> TEMP_POPOUT_ACTION_LISTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<View, Integer> TEMP_POPOUT_BASE_ACTION_COUNTS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final Map<View, Boolean> CUSTOM_TAB_LAYOUT_HOOKS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final class ToolbarSwap {
        final Object manager;
        final Field toolbarField;
        final ViewGroup realToolbar;
        final ViewGroup temporaryToolbar;

        ToolbarSwap(
                Object manager,
                Field toolbarField,
                ViewGroup realToolbar,
                ViewGroup temporaryToolbar) {
            this.manager = manager;
            this.toolbarField = toolbarField;
            this.realToolbar = realToolbar;
            this.temporaryToolbar = temporaryToolbar;
        }
    }

    private static final class GraphNode {
        final Object value;
        final int depth;

        GraphNode(Object value, int depth) {
            this.value = value;
            this.depth = depth;
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

        try {
            resolvedSymbols = ChromeDexResolver.resolve(lpparam.appInfo);
            log("DEX symbols resolved: " + resolvedSymbols.describe());
        } catch (Throwable t) {
            log("DEX symbol resolution failed; refusing unsafe obfuscated-name "
                    + "fallbacks: " + stackSummary(t));
            return;
        }

        installExtensionSupplierToolbarBridge(
                lpparam.classLoader, resolvedSymbols);
        installToolbarInitializationHook(
                lpparam.classLoader, resolvedSymbols);
        installExtensionsMenuRepair(
                lpparam.classLoader, resolvedSymbols);
        installExtensionPopupWidthBridge(lpparam.classLoader);
        installExtensionPopupDismissCleanup(lpparam.classLoader);
    }

    private static Class<?> classForDescriptor(
            String descriptor, ClassLoader classLoader)
            throws ClassNotFoundException {
        switch (descriptor) {
            case "V": return void.class;
            case "Z": return boolean.class;
            case "B": return byte.class;
            case "S": return short.class;
            case "C": return char.class;
            case "I": return int.class;
            case "J": return long.class;
            case "F": return float.class;
            case "D": return double.class;
            default:
                if (descriptor.startsWith("[")) {
                    return Class.forName(
                            descriptor.replace('/', '.'),
                            false,
                            classLoader);
                }
                if (descriptor.startsWith("L")
                        && descriptor.endsWith(";")) {
                    String name = descriptor
                            .substring(1, descriptor.length() - 1)
                            .replace('/', '.');
                    return Class.forName(name, false, classLoader);
                }
                throw new ClassNotFoundException(
                        "Unsupported descriptor " + descriptor);
        }
    }

    private static Class<?>[] classesForDescriptors(
            String[] descriptors, ClassLoader classLoader)
            throws ClassNotFoundException {
        Class<?>[] result = new Class<?>[descriptors.length];
        for (int i = 0; i < descriptors.length; i++) {
            result[i] = classForDescriptor(descriptors[i], classLoader);
        }
        return result;
    }

    private static void installExtensionSupplierToolbarBridge(
            ClassLoader classLoader,
            ChromeDexResolver.Symbols symbols) {
        if (symbols.supplierToolbarTabletClassName == null) {
            log("Extensions Supplier has no ToolbarTablet cast; bridge not needed");
            return;
        }

        try {
            Class<?> managerClass = XposedHelpers.findClass(
                    symbols.toolbarManagerClassName, classLoader);
            toolbarManagerClass = managerClass;

            Class<?> supplierClass = XposedHelpers.findClass(
                    symbols.extensionSupplierClassName, classLoader);
            Method get = supplierClass.getDeclaredMethod("get");
            get.setAccessible(true);

            XposedBridge.hookMethod(get, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object manager = findCapturedToolbarManager(
                                param.thisObject, managerClass);
                        if (manager == null) {
                            return;
                        }

                        Field toolbarField = findToolbarViewField(manager);
                        if (toolbarField == null) {
                            log("Supplier bridge: toolbar field not found");
                            return;
                        }

                        Object toolbarValue = toolbarField.get(manager);
                        if (!(toolbarValue instanceof ViewGroup)) {
                            return;
                        }

                        ViewGroup realToolbar = (ViewGroup) toolbarValue;
                        Class<?> tabletClass = XposedHelpers.findClassIfExists(
                                symbols.supplierToolbarTabletClassName,
                                classLoader);
                        if (tabletClass == null
                                || tabletClass.isInstance(realToolbar)) {
                            return;
                        }

                        ViewGroup temporaryTablet =
                                createTemporaryToolbarTablet(
                                        tabletClass, realToolbar);
                        if (temporaryTablet == null) {
                            return;
                        }

                        toolbarField.set(manager, temporaryTablet);
                        TOOLBAR_SWAP.set(new ToolbarSwap(
                                manager,
                                toolbarField,
                                realToolbar,
                                temporaryTablet));
                        log("Supplier bridge: temporarily substituted "
                                + tabletClass.getName());
                    } catch (Throwable t) {
                        log("Supplier bridge preparation failed: "
                                + stackSummary(t));
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ToolbarSwap swap = TOOLBAR_SWAP.get();
                    TOOLBAR_SWAP.remove();
                    if (swap == null) {
                        return;
                    }

                    try {
                        swap.toolbarField.set(
                                swap.manager, swap.realToolbar);
                    } catch (Throwable t) {
                        log("Supplier bridge restore failed: "
                                + stackSummary(t));
                    }

                    try {
                        if (param.getThrowable() == null) {
                            Object coordinator = param.getResult();
                            if (coordinator != null) {
                                int replaced =
                                        replaceTemporaryToolbarReferences(
                                                coordinator,
                                                swap.temporaryToolbar,
                                                swap.realToolbar);
                                log("Supplier bridge: restored ToolbarPhone; "
                                        + "rebound " + replaced
                                        + " retained reference(s)");
                            }
                        }
                    } catch (Throwable t) {
                        log("Supplier bridge rebind failed: "
                                + stackSummary(t));
                    }
                }
            });

            log("installed structural Extensions Supplier bridge on "
                    + symbols.extensionSupplierClassName + ".get()");
        } catch (Throwable t) {
            log("could not install Extensions Supplier bridge: "
                    + stackSummary(t));
        }
    }

    private static Object findCapturedToolbarManager(
            Object supplier, Class<?> managerClass) {
        if (supplier == null || managerClass == null) {
            return null;
        }

        Class<?> type = supplier.getClass();
        while (type != null) {
            try {
                for (Field field : type.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(
                            field.getModifiers())
                            || field.getType().isPrimitive()) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(supplier);
                    if (managerClass.isInstance(value)) {
                        return value;
                    }
                }
            } catch (Throwable ignored) {}
            type = type.getSuperclass();
        }
        return null;
    }

    private static ViewGroup createTemporaryToolbarTablet(
            Class<?> tabletClass, ViewGroup realToolbar) {
        try {
            java.lang.reflect.Constructor<?> constructor =
                    tabletClass.getDeclaredConstructor(
                            Context.class,
                            android.util.AttributeSet.class);
            constructor.setAccessible(true);
            Object value = constructor.newInstance(
                    ((View) realToolbar).getContext(), null);
            return value instanceof ViewGroup
                    ? (ViewGroup) value
                    : null;
        } catch (Throwable t) {
            log("could not instantiate temporary ToolbarTablet: "
                    + stackSummary(t));
            return null;
        }
    }

    private static int replaceTemporaryToolbarReferences(
            Object root,
            ViewGroup temporaryToolbar,
            ViewGroup realToolbar) {
        if (root == null
                || temporaryToolbar == null
                || realToolbar == null) {
            return 0;
        }

        java.util.ArrayDeque<GraphNode> queue =
                new java.util.ArrayDeque<>();
        java.util.IdentityHashMap<Object, Boolean> visited =
                new java.util.IdentityHashMap<>();
        queue.add(new GraphNode(root, 0));

        int replacements = 0;
        int inspected = 0;
        while (!queue.isEmpty() && inspected < 64) {
            GraphNode node = queue.removeFirst();
            Object object = node.value;
            if (object == null
                    || visited.put(object, Boolean.TRUE) != null) {
                continue;
            }
            inspected++;

            Class<?> type = object.getClass();
            while (type != null) {
                try {
                    for (Field field : type.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(
                                field.getModifiers())
                                || field.getType().isPrimitive()) {
                            continue;
                        }
                        field.setAccessible(true);
                        Object value = field.get(object);
                        if (value == temporaryToolbar
                                && field.getType().isInstance(realToolbar)) {
                            field.set(object, realToolbar);
                            replacements++;
                            continue;
                        }

                        if (node.depth < 4
                                && shouldTraverseCoordinatorObject(value)) {
                            queue.addLast(
                                    new GraphNode(
                                            value, node.depth + 1));
                        }
                    }
                } catch (Throwable ignored) {}
                type = type.getSuperclass();
            }
        }
        return replacements;
    }

    private static boolean shouldTraverseCoordinatorObject(Object value) {
        if (value == null
                || value instanceof String
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Class<?>
                || value.getClass().isEnum()) {
            return false;
        }

        String name = value.getClass().getName();
        if (value instanceof View) {
            return name.contains(
                    "toolbar.extensions.ExtensionActionListRecyclerView");
        }

        ClassLoader loader = value.getClass().getClassLoader();
        return loader == chromeClassLoader
                && (!name.contains(".")
                    || name.startsWith(
                            "org.chromium.chrome.browser.toolbar.extensions.")
                    || name.startsWith(
                            "org.chromium.chrome.browser.ui.extensions."));
    }

    private static void installToolbarInitializationHook(
            ClassLoader classLoader,
            ChromeDexResolver.Symbols symbols) {
        try {
            Class<?> managerClass = XposedHelpers.findClass(
                    symbols.toolbarManagerClassName, classLoader);
            toolbarManagerClass = managerClass;
            Class<?>[] parameters = classesForDescriptors(
                    symbols.initializeParameterDescriptors,
                    classLoader);
            Method initialize = managerClass.getDeclaredMethod(
                    symbols.initializeMethodName, parameters);
            initialize.setAccessible(true);

            XposedBridge.hookMethod(initialize, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Object manager = param.thisObject;
                    try {
                        Activity activity =
                                resolveToolbarManagerActivity(manager);
                        if (activity == null) {
                            log("pre-init: ToolbarManager Activity not resolved");
                            return;
                        }

                        ACTIVE_TOOLBAR_MANAGERS.put(activity, manager);
                        ViewStub stub =
                                ensureExtensionsStub(activity, manager);
                        if (stub == null) {
                            log("pre-init: Extensions ViewStub unavailable");
                        }
                    } catch (Throwable t) {
                        log("pre-init structural preparation failed: "
                                + stackSummary(t));
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object manager = param.thisObject;
                    try {
                        Activity activity =
                                resolveToolbarManagerActivity(manager);
                        if (activity == null) {
                            log("post-init: ToolbarManager Activity not resolved");
                            return;
                        }
                        ACTIVE_TOOLBAR_MANAGERS.put(activity, manager);

                        Object coordinator =
                                findExtensionsCoordinator(manager);
                        if (coordinator == null) {
                            log("post-init: Chrome did not create Extensions "
                                    + "coordinator; menu action will fail closed");
                            return;
                        }

                        ACTIVE_COORDINATORS.put(activity, coordinator);
                        scheduleRelocateExtensionsContainer(
                                activity, coordinator, 0);
                        log("Chrome-owned Extensions initialization succeeded");
                    } catch (Throwable t) {
                        log("post-init structural inspection failed: "
                                + stackSummary(t));
                    }
                }
            });

            log("installed structural ToolbarManager initializer hook on "
                    + symbols.toolbarManagerClassName + "."
                    + symbols.initializeMethodName);
        } catch (Throwable t) {
            log("could not hook ToolbarManager initializer: "
                    + stackSummary(t));
        }
    }

    private static Activity resolveToolbarManagerActivity(Object manager) {
        View toolbar = findToolbarView(manager);
        if (toolbar != null) {
            Activity activity = unwrapActivity(toolbar.getContext());
            if (activity != null) {
                return activity;
            }
        }

        View control = findToolbarControlContainer(manager);
        return control != null ? unwrapActivity(control.getContext()) : null;
    }

    private static View findToolbarView(Object manager) {
        Field field = findToolbarViewField(manager);
        if (field == null) {
            return null;
        }
        try {
            Object value = field.get(manager);
            return value instanceof View ? (View) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findToolbarViewField(Object manager) {
        if (manager == null) {
            return null;
        }

        Class<?> type = manager.getClass();
        while (type != null) {
            try {
                for (Field field : type.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (!(value instanceof ViewGroup)) {
                        continue;
                    }

                    String name = value.getClass().getName();
                    if (name.endsWith(".ToolbarPhone")
                            || name.endsWith(".ToolbarTablet")
                            || name.contains(".toolbar.top.ToolbarPhone")
                            || name.contains(".toolbar.top.ToolbarTablet")) {
                        return field;
                    }
                }
            } catch (Throwable ignored) {
                // Continue through the hierarchy; one inaccessible field should
                // not disable structural resolution.
            }
            type = type.getSuperclass();
        }

        return null;
    }

    private static View findToolbarControlContainer(Object manager) {
        View toolbar = findToolbarView(manager);
        if (toolbar != null) {
            android.view.ViewParent parent = toolbar.getParent();
            while (parent instanceof View) {
                View view = (View) parent;
                String name = view.getClass().getName();
                if (name.contains("ToolbarControlContainer")) {
                    return view;
                }
                parent = view.getParent();
            }
        }

        Class<?> type = manager != null ? manager.getClass() : null;
        while (type != null) {
            try {
                for (Field field : type.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (value instanceof View
                            && value.getClass().getName().contains(
                                    "ToolbarControlContainer")) {
                        return (View) value;
                    }
                }
            } catch (Throwable ignored) {}
            type = type.getSuperclass();
        }

        return null;
    }

    private static Object findExtensionsCoordinator(Object manager) {
        if (manager == null) {
            return null;
        }

        ChromeDexResolver.Symbols symbols = resolvedSymbols;
        if (symbols != null) {
            try {
                Class<?> type = manager.getClass();
                while (type != null) {
                    try {
                        Field field = type.getDeclaredField(
                                symbols.coordinatorFieldName);
                        field.setAccessible(true);
                        Object value = field.get(manager);
                        if (value != null
                                && symbols.coordinatorClassName.equals(
                                        value.getClass().getName())) {
                            return value;
                        }
                        break;
                    } catch (NoSuchFieldException ignored) {
                        type = type.getSuperclass();
                    }
                }
            } catch (Throwable t) {
                log("resolved coordinator field read failed: "
                        + stackSummary(t));
            }
        }

        Class<?> type = manager.getClass();
        while (type != null) {
            try {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType().isPrimitive()) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (value == null || value instanceof View) {
                        continue;
                    }

                    LinearLayout container =
                            findCoordinatorLinearContainer(value);
                    if (container != null) {
                        return value;
                    }
                }
            } catch (Throwable ignored) {}
            type = type.getSuperclass();
        }

        return null;
    }

    private static void installExtensionsMenuRepair(
            ClassLoader classLoader,
            ChromeDexResolver.Symbols symbols) {
        Class<?> chromeTabbedActivity = XposedHelpers.findClassIfExists(
                "org.chromium.chrome.browser.ChromeTabbedActivity",
                classLoader);
        if (chromeTabbedActivity == null) {
            log("ChromeTabbedActivity not found");
            return;
        }

        try {
            Class<?> fourthParameter = classForDescriptor(
                    symbols.menuHandlerFourthParameterDescriptor,
                    classLoader);
            Method handler = chromeTabbedActivity.getDeclaredMethod(
                    symbols.menuHandlerMethodName,
                    int.class,
                    boolean.class,
                    android.os.Bundle.class,
                    fourthParameter);
            handler.setAccessible(true);

            XposedBridge.hookMethod(handler, new XC_MethodHook() {
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

                    Object manager = ACTIVE_TOOLBAR_MANAGERS.get(activity);
                    if (manager == null
                            || (toolbarManagerClass != null
                                && !toolbarManagerClass.isInstance(manager))) {
                        log("Extensions action: active ToolbarManager unavailable; "
                                + "suppressing unsafe action");
                        param.setResult(true);
                        return;
                    }

                    Object coordinator =
                            findExtensionsCoordinator(manager);
                    if (coordinator == null) {
                        log("Extensions action: coordinator unavailable; "
                                + "suppressing unsafe action");
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
                        log("suppressed Extensions-menu NPE: "
                                + stackSummary(t));
                        param.setResult(true);
                    }
                }
            });

            log("installed structural Extensions menu hook on "
                    + symbols.menuHandlerMethodName);
        } catch (Throwable t) {
            log("could not hook structural Extensions menu handler: "
                    + stackSummary(t));
        }
    }

    private static boolean isExtensionsMenuAction(
            Activity activity, int id) {
        try {
            return "extensions_menu_menu_id".equals(
                    activity.getResources().getResourceEntryName(id));
        } catch (Resources.NotFoundException ignored) {
            return false;
        }
    }

    private static ViewStub ensureExtensionsStub(
            Activity activity, Object toolbarManager) {

        try {
            View container = findToolbarControlContainer(toolbarManager);
            if (container == null) {
                log("ToolbarControlContainer not found structurally");
                return null;
            }
            Resources resources = activity.getResources();

            int stubId = resources.getIdentifier(
                    "extensions_toolbar_container_stub",
                    "id",
                    TARGET_PACKAGE
            );
            if (stubId == 0) {
                log("extensions_toolbar_container_stub resource not found");
                return null;
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
                log("extensions_toolbar_container layout resource not found");
                return null;
            }

            ViewStub injected = new ViewStub(container.getContext());
            injected.setId(stubId);
            int inflatedId = resources.getIdentifier(
                    "extensions_toolbar_container",
                    "id",
                    TARGET_PACKAGE);
            if (inflatedId != 0) {
                injected.setInflatedId(inflatedId);
            }
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

        // Rebind ExtensionActionListRecyclerView's transition root without
        // relying on obfuscated coordinator/field names.
        View actionList = findNamedView(
                extensionsToolbar, res, "extension_action_list");
        if (actionList != null) {
            rebindTransitionRoot(actionList, bottomBar);
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

            // The native Bottom Bar geometry must never depend on extension
            // state. New Tab / Tab Switcher / Extensions / app menu are always
            // four equal parent slots. Pinned actions and temporary popup
            // actions are packed *inside* the single Extensions slot and may
            // visually overflow it without changing sibling positions.
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
                            || containerLp.weight != 1.0f
                            || containerLp.height
                                    != ViewGroup.LayoutParams.MATCH_PARENT;
            containerLp.width = 0;
            containerLp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            containerLp.weight = 1.0f;
            containerLp.gravity = android.view.Gravity.CENTER_VERTICAL;
            if (containerChanged) {
                extensionsToolbar.setLayoutParams(containerLp);
            }

            // Allow the compact extension cluster to spill a little outside
            // its logical slot instead of resizing the whole Bottom Bar.
            extensionsToolbar.setGravity(android.view.Gravity.CENTER);
            extensionsToolbar.setClipChildren(false);
            extensionsToolbar.setClipToPadding(false);
            bottomBar.setClipChildren(false);
            bottomBar.setClipToPadding(false);

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

            // Each extension button keeps normal toolbar-button touch width.
            // Because the LinearLayout is centered and has no inter-item
            // margins, pinned action + Extensions appears as a tight "oo"
            // cluster while the surrounding native buttons stay put.
            int packedButtonWidth = buttonWidth;

            if (menuButton != null) {
                ViewGroup.LayoutParams old = menuButton.getLayoutParams();
                LinearLayout.LayoutParams lp =
                        old instanceof LinearLayout.LayoutParams
                                ? (LinearLayout.LayoutParams) old
                                : new LinearLayout.LayoutParams(
                                        packedButtonWidth,
                                        ViewGroup.LayoutParams.MATCH_PARENT);
                if (lp.width != packedButtonWidth
                        || lp.weight != 0f
                        || lp.leftMargin != 0
                        || lp.rightMargin != 0) {
                    lp.width = packedButtonWidth;
                    lp.weight = 0f;
                    lp.leftMargin = 0;
                    lp.rightMargin = 0;
                    lp.setMarginStart(0);
                    lp.setMarginEnd(0);
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
                if (lp.width != wantedWidth
                        || lp.weight != 0f
                        || lp.leftMargin != 0
                        || lp.rightMargin != 0) {
                    lp.width = wantedWidth;
                    lp.weight = 0f;
                    lp.leftMargin = 0;
                    lp.rightMargin = 0;
                    lp.setMarginStart(0);
                    lp.setMarginEnd(0);
                    actionListView.setLayoutParams(lp);
                }

                if (actionListView instanceof ViewGroup && actionCount > 0) {
                    ViewGroup group = (ViewGroup) actionListView;
                    group.setClipChildren(false);
                    group.setClipToPadding(false);

                    for (int i = 0; i < group.getChildCount(); i++) {
                        View child = group.getChildAt(i);
                        ViewGroup.LayoutParams childLp = child.getLayoutParams();
                        if (childLp != null
                                && childLp.width != packedButtonWidth) {
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

        View actionList = findNamedView(
                extensionsToolbar, res, "extension_action_list");
        if (actionList != null) {
            rebindTransitionRoot(actionList, actionButtons);
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

    private static void rebindTransitionRoot(
            View actionListView, ViewGroup newRoot) {
        if (actionListView == null || newRoot == null) {
            return;
        }

        // Structural fallback: ExtensionActionListRecyclerView retains a
        // ViewGroup transition root that initially points at ToolbarPhone /
        // ToolbarTablet. Replace that field by identity/type rather than name.
        Class<?> type = actionListView.getClass();
        while (type != null) {
            try {
                for (Field field : type.getDeclaredFields()) {
                    field.setAccessible(true);
                    Object value = field.get(actionListView);
                    if (!(value instanceof ViewGroup)) {
                        continue;
                    }

                    String valueName = value.getClass().getName();
                    if (valueName.contains(".toolbar.top.Toolbar")
                            || value == actionListView.getParent()) {
                        field.set(actionListView, newRoot);
                        log("rebound extension transition root via field "
                                + type.getName() + "." + field.getName());
                        return;
                    }
                }
            } catch (Throwable ignored) {}
            type = type.getSuperclass();
        }

        log("extension transition-root field not found structurally");
    }

    private static Object findObjectReferencingView(
            Object root, View target) {
        if (root == null || target == null) {
            return null;
        }

        Class<?> type = root.getClass();
        while (type != null) {
            try {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType().isPrimitive()) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object candidate = field.get(root);
                    if (candidate == null
                            || candidate instanceof View
                            || candidate == root) {
                        continue;
                    }

                    if (objectDirectlyReferences(candidate, target)) {
                        return candidate;
                    }
                }
            } catch (Throwable ignored) {}
            type = type.getSuperclass();
        }

        return null;
    }

    private static boolean objectDirectlyReferences(
            Object object, View target) {
        Class<?> type = object.getClass();
        while (type != null) {
            try {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getType().isPrimitive()) {
                        continue;
                    }
                    field.setAccessible(true);
                    if (field.get(object) == target) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
            type = type.getSuperclass();
        }
        return false;
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

        LinearLayout extensionsToolbar =
                findCoordinatorLinearContainer(coordinator);
        if (extensionsToolbar == null) {
            int containerId = activity.getResources().getIdentifier(
                    "extensions_toolbar_container", "id", TARGET_PACKAGE);
            View byId = containerId != 0
                    ? activity.findViewById(containerId)
                    : null;
            if (byId instanceof LinearLayout) {
                extensionsToolbar = (LinearLayout) byId;
            }
        }

        if (extensionsToolbar == null) {
            log("popup bridge: extensions toolbar container not found");
            return;
        }

        View recyclerView = findNamedView(
                extensionsToolbar,
                activity.getResources(),
                "extension_action_list");
        if (recyclerView == null) {
            log("popup bridge: extension_action_list not found");
            return;
        }

        Object actionListCoordinator =
                findObjectReferencingView(coordinator, recyclerView);
        if (actionListCoordinator == null) {
            log("popup bridge: action-list coordinator not found structurally");
            return;
        }

        Method getButtonMethod = null;
        try {
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
        } catch (Throwable t) {
            log("popup bridge: anchor method structural scan failed: "
                    + stackSummary(t));
        }

        if (getButtonMethod == null) {
            log("popup bridge: String->View anchor method missing on "
                    + actionListCoordinator.getClass().getName());
            return;
        }

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
