package com.atuy.chromemobilecompat;

import android.content.pm.ApplicationInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Resolves R8-renamed Chrome symbols directly from the installed DEX files.
 *
 * The resolver deliberately anchors on stable Chromium / Android type
 * descriptors and method shapes rather than obfuscated class, field, or method
 * names. It has been validated against:
 *
 *  - 153.0.8010.49 / 801004974
 *  - 153.0.8010.52 / 801005274
 *  - 153.0.8010.53 / 801005374
 *  - 154.0.8037.57 / 803705774
 */
final class ChromeDexResolver {
    private static final String CHROME_TABBED_ACTIVITY =
            "Lorg/chromium/chrome/browser/ChromeTabbedActivity;";
    private static final String CHROME_ACTIVITY =
            "Lorg/chromium/chrome/browser/app/ChromeActivity;";
    private static final String CHROME_ANDROID_TASK_TRACE =
            "ChromeActivity.initializeChromeAndroidTask";
    private static final String TOOLBAR_CONTROL_CONTAINER =
            "Lorg/chromium/chrome/browser/toolbar/top/ToolbarControlContainer;";
    private static final String WINDOW_ANDROID =
            "Lorg/chromium/ui/base/WindowAndroid;";
    private static final String LOCATION_BAR_MODEL =
            "Lorg/chromium/chrome/browser/toolbar/LocationBarModel;";
    private static final String RUNNABLE = "Ljava/lang/Runnable;";
    private static final String ON_CLICK_LISTENER =
            "Landroid/view/View$OnClickListener;";
    private static final String VIEW_STUB = "Landroid/view/ViewStub;";
    private static final String PROFILE =
            "Lorg/chromium/chrome/browser/profiles/Profile;";
    private static final String JAVA_SUPPLIER =
            "Ljava/util/function/Supplier;";
    private static final String LINEAR_LAYOUT = "Landroid/widget/LinearLayout;";
    private static final String EXTENSIONS_TOOLBAR_BRIDGE =
            "Lorg/chromium/chrome/browser/ui/extensions/ExtensionsToolbarBridge;";
    private static final String TOOLBAR_TABLET =
            "Lorg/chromium/chrome/browser/toolbar/top/ToolbarTablet;";
    private static final String BUNDLE = "Landroid/os/Bundle;";
    private static final String OBJECT = "Ljava/lang/Object;";
    private static final String VOID = "V";
    private static final String BOOLEAN = "Z";
    private static final String INT = "I";

    private static final int ACC_STATIC = 0x0008;

    static final class Symbols {
        final String toolbarManagerClassName;
        final String initializeMethodName;
        final String[] initializeParameterDescriptors;
        final String extensionSupplierClassName;
        final String supplierToolbarTabletClassName;
        final String coordinatorFieldName;
        final String coordinatorClassName;
        final String toolbarManagerGetterOwnerClassName;
        final String toolbarManagerGetterMethodName;
        final String taskInitializerOwnerClassName;
        final String taskInitializerMethodName;
        final String[] taskInitializerParameterDescriptors;
        final String menuHandlerMethodName;
        final String menuHandlerFourthParameterDescriptor;

        Symbols(
                String toolbarManagerClassName,
                String initializeMethodName,
                String[] initializeParameterDescriptors,
                String extensionSupplierClassName,
                String supplierToolbarTabletClassName,
                String coordinatorFieldName,
                String coordinatorClassName,
                String toolbarManagerGetterOwnerClassName,
                String toolbarManagerGetterMethodName,
                String taskInitializerOwnerClassName,
                String taskInitializerMethodName,
                String[] taskInitializerParameterDescriptors,
                String menuHandlerMethodName,
                String menuHandlerFourthParameterDescriptor) {
            this.toolbarManagerClassName = toolbarManagerClassName;
            this.initializeMethodName = initializeMethodName;
            this.initializeParameterDescriptors = initializeParameterDescriptors;
            this.extensionSupplierClassName = extensionSupplierClassName;
            this.supplierToolbarTabletClassName = supplierToolbarTabletClassName;
            this.coordinatorFieldName = coordinatorFieldName;
            this.coordinatorClassName = coordinatorClassName;
            this.toolbarManagerGetterOwnerClassName =
                    toolbarManagerGetterOwnerClassName;
            this.toolbarManagerGetterMethodName =
                    toolbarManagerGetterMethodName;
            this.taskInitializerOwnerClassName =
                    taskInitializerOwnerClassName;
            this.taskInitializerMethodName =
                    taskInitializerMethodName;
            this.taskInitializerParameterDescriptors =
                    taskInitializerParameterDescriptors;
            this.menuHandlerMethodName = menuHandlerMethodName;
            this.menuHandlerFourthParameterDescriptor =
                    menuHandlerFourthParameterDescriptor;
        }

        String describe() {
            return "ToolbarManager=" + toolbarManagerClassName
                    + "." + initializeMethodName
                    + " supplier=" + extensionSupplierClassName
                    + " coordinator=" + coordinatorClassName
                    + "/" + coordinatorFieldName
                    + " getter=" + toolbarManagerGetterOwnerClassName
                    + "." + toolbarManagerGetterMethodName
                    + " taskInit=" + taskInitializerOwnerClassName
                    + "." + taskInitializerMethodName
                    + " menu=" + menuHandlerMethodName
                    + " tabletCast=" + supplierToolbarTabletClassName;
        }
    }

    static final class MenuHandler {
        final String methodName;
        final String fourthParameterDescriptor;

        MenuHandler(
                String methodName,
                String fourthParameterDescriptor) {
            this.methodName = methodName;
            this.fourthParameterDescriptor = fourthParameterDescriptor;
        }

        String describe() {
            return methodName + "(int,boolean,Bundle,"
                    + fourthParameterDescriptor + ")";
        }
    }

    /**
     * Resolve the broad ChromeTabbedActivity command-handler shape without
     * depending on ToolbarManager/coordinator/Supplier resolution.
     *
     * This is intentionally independent from resolve(): even if Chromium
     * semantically changes the Extensions initialization path, callers can
     * still install a fail-safe around candidate menu handlers and prevent a
     * null Extensions coordinator from terminating Chrome.
     */
    static List<MenuHandler> resolveMenuHandlers(
            ApplicationInfo appInfo) throws IOException {
        List<DexFile> dexFiles = loadApplicationDexFiles(appInfo);

        DexFile activityDex = null;
        ClassDef activityClass = null;
        for (DexFile dex : dexFiles) {
            ClassDef candidate = dex.classes.get(CHROME_TABBED_ACTIVITY);
            if (candidate != null) {
                activityDex = dex;
                activityClass = candidate;
                break;
            }
        }

        if (activityDex == null || activityClass == null) {
            throw new IOException("ChromeTabbedActivity class not found");
        }

        ArrayList<MenuHandler> handlers = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        for (MethodDef def : activityDex.getDefinedMethods(activityClass)) {
            if ((def.accessFlags & ACC_STATIC) != 0) {
                continue;
            }

            MethodId method = activityDex.methods[def.methodIndex];
            Proto proto = activityDex.protos[method.protoIndex];
            String[] p = proto.parameterDescriptors;
            if (!BOOLEAN.equals(proto.returnDescriptor)
                    || p.length != 4
                    || !INT.equals(p[0])
                    || !BOOLEAN.equals(p[1])
                    || !BUNDLE.equals(p[2])
                    || !isObjectDescriptor(p[3])) {
                continue;
            }

            String key = method.name + "\n" + p[3];
            if (seen.add(key)) {
                handlers.add(new MenuHandler(method.name, p[3]));
            }
        }

        if (handlers.isEmpty()) {
            throw new IOException(
                    "No boolean(int,boolean,Bundle,*) "
                            + "ChromeTabbedActivity handlers found");
        }
        return handlers;
    }

    static Symbols resolve(ApplicationInfo appInfo) throws IOException {
        List<DexFile> dexFiles = loadApplicationDexFiles(appInfo);

        HashMap<String, DexFile> classOwners = new HashMap<>();
        for (DexFile dex : dexFiles) {
            for (String descriptor : dex.classes.keySet()) {
                classOwners.put(descriptor, dex);
            }
        }

        MethodCandidate toolbar = resolveToolbarManager(dexFiles, classOwners);
        DexFile toolbarDex = toolbar.dex;
        ClassDef toolbarClass = toolbarDex.classes.get(toolbar.ownerDescriptor);
        MethodDef initialize = toolbarDex.findDefinedMethod(
                toolbarClass, toolbar.methodIndex);
        if (initialize == null || initialize.codeOffset == 0) {
            throw new IOException("Resolved ToolbarManager initializer has no code");
        }

        FieldId coordinatorField =
                resolveCoordinatorField(toolbarDex, toolbarClass, classOwners);

        SupplierCandidate supplier = resolveExtensionSupplier(
                toolbarDex,
                initialize,
                toolbar.ownerDescriptor,
                classOwners);

        GetterCandidate getter = resolveToolbarManagerGetter(
                classOwners,
                toolbar.ownerDescriptor);

        MethodCandidate taskInitializer =
                resolveChromeAndroidTaskInitializer(dexFiles);

        MenuCandidate menu = resolveMenuHandler(
                dexFiles,
                toolbar.ownerDescriptor,
                coordinatorField);

        Proto initializeProto = toolbarDex.protos[
                toolbarDex.methods[toolbar.methodIndex].protoIndex];

        return new Symbols(
                descriptorToClassName(toolbar.ownerDescriptor),
                toolbarDex.methods[toolbar.methodIndex].name,
                initializeProto.parameterDescriptors.clone(),
                descriptorToClassName(supplier.classDescriptor),
                supplier.toolbarTabletCastDescriptor == null
                        ? null
                        : descriptorToClassName(supplier.toolbarTabletCastDescriptor),
                coordinatorField.name,
                descriptorToClassName(coordinatorField.typeDescriptor),
                getter == null
                        ? null
                        : descriptorToClassName(getter.ownerDescriptor),
                getter == null ? null : getter.methodName,
                taskInitializer == null
                        ? null
                        : descriptorToClassName(
                                taskInitializer.ownerDescriptor),
                taskInitializer == null
                        ? null
                        : taskInitializer.dex.methods[
                                taskInitializer.methodIndex].name,
                taskInitializer == null
                        ? null
                        : taskInitializer.dex.protos[
                                taskInitializer.dex.methods[
                                        taskInitializer.methodIndex]
                                        .protoIndex]
                                .parameterDescriptors.clone(),
                menu.methodName,
                menu.fourthParameterDescriptor);
    }

    private static MethodCandidate resolveToolbarManager(
            List<DexFile> dexFiles,
            Map<String, DexFile> classOwners) throws IOException {
        MethodCandidate best = null;
        MethodCandidate second = null;

        for (DexFile dex : dexFiles) {
            for (int i = 0; i < dex.methods.length; i++) {
                MethodId method = dex.methods[i];
                ClassDef owner = dex.classes.get(method.ownerDescriptor);
                if (owner == null) {
                    continue;
                }

                Proto proto = dex.protos[method.protoIndex];
                if (!VOID.equals(proto.returnDescriptor)) {
                    continue;
                }

                int runnableIndex = indexOf(proto.parameterDescriptors, RUNNABLE);
                int clickIndex = indexOf(
                        proto.parameterDescriptors, ON_CLICK_LISTENER);
                if (runnableIndex < 0 || clickIndex < 0) {
                    continue;
                }

                MethodDef def = dex.findDefinedMethod(owner, i);
                if (def == null || (def.accessFlags & ACC_STATIC) != 0) {
                    continue;
                }

                List<FieldId> fields = dex.getDefinedFields(owner);
                int score = 0;
                if (proto.parameterDescriptors.length == 9) {
                    score += 40;
                } else if (proto.parameterDescriptors.length >= 7
                        && proto.parameterDescriptors.length <= 12) {
                    score += 10;
                } else {
                    continue;
                }

                if (runnableIndex == 2) {
                    score += 30;
                } else {
                    score += 8;
                }
                if (clickIndex == 3) {
                    score += 30;
                } else {
                    score += 8;
                }
                if (containsFieldType(fields, TOOLBAR_CONTROL_CONTAINER)) {
                    score += 35;
                }
                if (containsFieldType(fields, WINDOW_ANDROID)) {
                    score += 25;
                }
                if (containsFieldType(fields, LOCATION_BAR_MODEL)) {
                    score += 10;
                }

                MethodCandidate candidate =
                        new MethodCandidate(dex, i, method.ownerDescriptor, score);
                if (best == null || candidate.score > best.score) {
                    second = best;
                    best = candidate;
                } else if (second == null || candidate.score > second.score) {
                    second = candidate;
                }
            }
        }

        if (best == null || best.score < 120) {
            throw new IOException(
                    "Could not structurally resolve ToolbarManager; bestScore="
                            + (best == null ? "none" : best.score));
        }
        if (second != null && second.score == best.score
                && !second.ownerDescriptor.equals(best.ownerDescriptor)) {
            throw new IOException(
                    "Ambiguous ToolbarManager candidates: "
                            + best.ownerDescriptor + " and "
                            + second.ownerDescriptor + " score=" + best.score);
        }
        return best;
    }



    /**
     * Resolve ChromeActivity.initializeChromeAndroidTask() without depending
     * on its R8 method name. Chromium 154 keeps the TraceEvent literal
     * "ChromeActivity.initializeChromeAndroidTask" inside the method, which is
     * a stronger anchor than the obfuscated name or surrounding field names.
     */
    private static MethodCandidate resolveChromeAndroidTaskInitializer(
            List<DexFile> dexFiles) {
        for (DexFile dex : dexFiles) {
            ClassDef owner = dex.classes.get(CHROME_ACTIVITY);
            if (owner == null) {
                continue;
            }

            MethodCandidate signatureFallback = null;
            for (MethodDef def : dex.getDefinedMethods(owner)) {
                if ((def.accessFlags & ACC_STATIC) != 0
                        || def.codeOffset == 0) {
                    continue;
                }
                MethodId method = dex.methods[def.methodIndex];
                Proto proto = dex.protos[method.protoIndex];
                String[] p = proto.parameterDescriptors;
                if (!VOID.equals(proto.returnDescriptor)
                        || p.length != 4
                        || !INT.equals(p[0])
                        || !isObjectDescriptor(p[1])
                        || !INT.equals(p[2])
                        || !isObjectDescriptor(p[3])) {
                    continue;
                }

                MethodCandidate candidate =
                        new MethodCandidate(
                                dex,
                                def.methodIndex,
                                CHROME_ACTIVITY,
                                methodReferencesString(
                                                dex,
                                                def,
                                                CHROME_ANDROID_TASK_TRACE)
                                        ? 1000
                                        : 1);
                if (candidate.score == 1000) {
                    return candidate;
                }
                if (signatureFallback == null) {
                    signatureFallback = candidate;
                } else {
                    // Ambiguous without the TraceEvent anchor. Refuse to
                    // guess rather than invoking the wrong ChromeActivity
                    // lifecycle method.
                    signatureFallback = null;
                }
            }
            return signatureFallback;
        }
        return null;
    }

    private static boolean methodReferencesString(
            DexFile dex,
            MethodDef method,
            String expected) {
        if (method.codeOffset == 0) {
            return false;
        }

        short[] insns = dex.readCodeUnits(method.codeOffset);
        for (int i = 0; i < insns.length;) {
            int width = instructionWidth(insns, i);
            if (width <= 0 || i + width > insns.length) break;
            int opcode = insns[i] & 0xff;

            // const-string vAA, string@BBBB
            if (opcode == 0x1a && i + 1 < insns.length) {
                int stringIndex = insns[i + 1] & 0xffff;
                if (stringIndex >= 0
                        && stringIndex < dex.strings.length
                        && expected.equals(dex.strings[stringIndex])) {
                    return true;
                }
            }

            // const-string/jumbo vAA, string@BBBBBBBB
            if (opcode == 0x1b && i + 2 < insns.length) {
                int stringIndex =
                        (insns[i + 1] & 0xffff)
                                | ((insns[i + 2] & 0xffff) << 16);
                if (stringIndex >= 0
                        && stringIndex < dex.strings.length
                        && expected.equals(dex.strings[stringIndex])) {
                    return true;
                }
            }
            i += width;
        }
        return false;
    }

    /**
     * Resolve ChromeActivity/ChromeTabbedActivity's exact no-arg ToolbarManager
     * getter from the DEX return type. Chromium source calls getToolbarManager()
     * from the app-menu path, but R8 renames it (for example P2() on older
     * builds). Returning the owner as well avoids reflective method
     * enumeration, which can resolve desktop-only Android framework types.
     */
    private static GetterCandidate resolveToolbarManagerGetter(
            Map<String, DexFile> classOwners,
            String toolbarManagerDescriptor) {
        String descriptor = CHROME_TABBED_ACTIVITY;
        HashSet<String> visited = new HashSet<>();

        while (descriptor != null && visited.add(descriptor)) {
            DexFile dex = classOwners.get(descriptor);
            if (dex == null) {
                break;
            }
            ClassDef def = dex.classes.get(descriptor);
            if (def == null) {
                break;
            }

            GetterCandidate found = null;
            for (MethodDef methodDef : dex.getDefinedMethods(def)) {
                if ((methodDef.accessFlags & ACC_STATIC) != 0) {
                    continue;
                }
                MethodId method = dex.methods[methodDef.methodIndex];
                Proto proto = dex.protos[method.protoIndex];
                if (proto.parameterDescriptors.length == 0
                        && toolbarManagerDescriptor.equals(
                                proto.returnDescriptor)) {
                    if (found != null
                            && !found.methodName.equals(method.name)) {
                        return null;
                    }
                    found = new GetterCandidate(
                            descriptor, method.name);
                }
            }

            if (found != null) {
                return found;
            }
            descriptor = def.superDescriptor;
        }
        return null;
    }

    private static FieldId resolveCoordinatorField(
            DexFile toolbarDex,
            ClassDef toolbarClass,
            Map<String, DexFile> classOwners) throws IOException {
        FieldId best = null;
        int bestScore = Integer.MIN_VALUE;
        int secondScore = Integer.MIN_VALUE;

        for (FieldId field : toolbarDex.getDefinedFields(toolbarClass)) {
            if (!isObjectDescriptor(field.typeDescriptor)) {
                continue;
            }

            DexFile fieldDex = classOwners.get(field.typeDescriptor);
            if (fieldDex == null) {
                continue;
            }
            ClassDef fieldClass = fieldDex.classes.get(field.typeDescriptor);
            if (fieldClass == null) {
                continue;
            }

            List<FieldId> nestedFields = fieldDex.getDefinedFields(fieldClass);
            int score = 0;
            if (containsFieldType(nestedFields, LINEAR_LAYOUT)) {
                score += 35;
            }
            if (containsFieldType(nestedFields, EXTENSIONS_TOOLBAR_BRIDGE)) {
                score += 70;
            }
            if (containsFieldType(nestedFields, PROFILE)) {
                score += 10;
            }
            if (countPrimitiveBooleanFields(nestedFields) > 0) {
                score += 5;
            }

            if (score > bestScore) {
                secondScore = bestScore;
                bestScore = score;
                best = field;
            } else if (score > secondScore) {
                secondScore = score;
            }
        }

        if (best == null || bestScore < 100) {
            throw new IOException(
                    "Could not structurally resolve Extensions coordinator field; "
                            + "bestScore=" + bestScore);
        }
        if (secondScore == bestScore) {
            throw new IOException(
                    "Ambiguous Extensions coordinator fields on "
                            + toolbarClass.descriptor);
        }
        return best;
    }

    private static SupplierCandidate resolveExtensionSupplier(
            DexFile initializerDex,
            MethodDef initialize,
            String toolbarManagerDescriptor,
            Map<String, DexFile> classOwners) throws IOException {
        short[] insns = initializerDex.readCodeUnits(initialize.codeOffset);
        SupplierCandidate best = null;
        int secondScore = Integer.MIN_VALUE;
        HashSet<String> seen = new HashSet<>();

        for (int i = 0; i + 1 < insns.length; i++) {
            int opcode = insns[i] & 0xff;
            if (opcode != 0x22) {
                continue;
            }

            int typeIndex = insns[i + 1] & 0xffff;
            if (typeIndex < 0 || typeIndex >= initializerDex.types.length) {
                continue;
            }

            String candidateDescriptor = initializerDex.types[typeIndex];
            if (!seen.add(candidateDescriptor)) {
                continue;
            }

            DexFile candidateDex = classOwners.get(candidateDescriptor);
            if (candidateDex == null) {
                continue;
            }
            ClassDef candidateClass =
                    candidateDex.classes.get(candidateDescriptor);
            if (candidateClass == null) {
                continue;
            }

            int score = 0;
            if (implementsInterface(
                    candidateDescriptor,
                    JAVA_SUPPLIER,
                    classOwners,
                    new HashSet<>())) {
                score += 60;
            } else {
                continue;
            }

            List<FieldId> fields = candidateDex.getDefinedFields(candidateClass);
            if (containsFieldType(fields, toolbarManagerDescriptor)) {
                score += 25;
            }
            if (containsFieldType(fields, VIEW_STUB)) {
                score += 20;
            }
            if (containsFieldType(fields, PROFILE)) {
                score += 15;
            }
            if (containsFieldType(fields, RUNNABLE)) {
                score += 15;
            }

            MethodDef get = candidateDex.findDefinedMethod(
                    candidateClass, "get", OBJECT, new String[0]);
            if (get != null) {
                score += 15;
            }

            String toolbarTabletCast = get == null
                    ? null
                    : findToolbarTabletCheckCast(candidateDex, get);

            SupplierCandidate candidate =
                    new SupplierCandidate(
                            candidateDescriptor, score, toolbarTabletCast);
            if (best == null || candidate.score > best.score) {
                secondScore = best == null ? Integer.MIN_VALUE : best.score;
                best = candidate;
            } else if (candidate.score > secondScore) {
                secondScore = candidate.score;
            }
        }

        if (best == null || best.score < 120) {
            throw new IOException(
                    "Could not structurally resolve Extensions Supplier; "
                            + "bestScore="
                            + (best == null ? "none" : best.score));
        }
        if (secondScore == best.score) {
            throw new IOException("Ambiguous Extensions Supplier candidates");
        }
        return best;
    }

    private static MenuCandidate resolveMenuHandler(
            List<DexFile> dexFiles,
            String toolbarManagerDescriptor,
            FieldId coordinatorField) throws IOException {
        DexFile activityDex = null;
        ClassDef activityClass = null;
        for (DexFile dex : dexFiles) {
            ClassDef candidate = dex.classes.get(CHROME_TABBED_ACTIVITY);
            if (candidate != null) {
                activityDex = dex;
                activityClass = candidate;
                break;
            }
        }
        if (activityDex == null || activityClass == null) {
            throw new IOException("ChromeTabbedActivity class not found");
        }

        // Newer Chromium invokes ToolbarManager.getExtensionsToolbarCoordinator()
        // rather than accessing the field directly. Resolve that getter from its
        // return type and backing-field access, not its R8-obfuscated name.
        MethodId coordinatorGetter = findCoordinatorGetter(
                dexFiles, toolbarManagerDescriptor, coordinatorField);

        MenuCandidate best = null;
        MenuCandidate second = null;
        for (MethodDef def : activityDex.getDefinedMethods(activityClass)) {
            MethodId method = activityDex.methods[def.methodIndex];
            Proto proto = activityDex.protos[method.protoIndex];
            String[] p = proto.parameterDescriptors;
            if (!BOOLEAN.equals(proto.returnDescriptor)
                    || p.length != 4
                    || !INT.equals(p[0])
                    || !BOOLEAN.equals(p[1])
                    || !BUNDLE.equals(p[2])
                    || !isObjectDescriptor(p[3])) {
                continue;
            }

            int score = 100;
            if (def.codeOffset != 0
                    && methodReferencesField(
                            activityDex,
                            def,
                            toolbarManagerDescriptor,
                            coordinatorField.name,
                            coordinatorField.typeDescriptor)) {
                score += 80;
            }
            if (def.codeOffset != 0 && coordinatorGetter != null
                    && methodInvokesCoordinatorGetter(
                            activityDex, def, toolbarManagerDescriptor,
                            coordinatorGetter)) {
                score += 120;
            }

            MenuCandidate candidate = new MenuCandidate(
                    method.name, p[3], score);
            if (best == null || candidate.score > best.score) {
                second = best;
                best = candidate;
            } else if (second == null || candidate.score > second.score) {
                second = candidate;
            }
        }

        if (best == null) {
            throw new IOException(
                    "Could not resolve ChromeTabbedActivity menu handler");
        }
        if (second != null && second.score == best.score) {
            throw new IOException(
                    "Ambiguous ChromeTabbedActivity menu handlers");
        }
        return best;
    }

    /**
     * Locate the zero-argument coordinator getter by its return descriptor
     * and direct read of ToolbarManager's coordinator field. This survives R8
     * renames, and avoids guessing which candidate handles app menu commands.
     */
    private static MethodId findCoordinatorGetter(
            List<DexFile> dexFiles,
            String managerDescriptor,
            FieldId coordinatorField) {
        for (DexFile dex : dexFiles) {
            ClassDef managerClass = dex.classes.get(managerDescriptor);
            if (managerClass == null) continue;
            for (MethodDef def : dex.getDefinedMethods(managerClass)) {
                MethodId id = dex.methods[def.methodIndex];
                Proto proto = dex.protos[id.protoIndex];
                if (proto.parameterDescriptors.length != 0
                        || !coordinatorField.typeDescriptor.equals(proto.returnDescriptor)
                        || def.codeOffset == 0) {
                    continue;
                }
                if (methodReferencesField(dex, def, managerDescriptor,
                        coordinatorField.name, coordinatorField.typeDescriptor)) {
                    return id;
                }
            }
        }
        return null;
    }

    /** DEX invoke-kind opcodes use the next code unit as their method index. */
    private static boolean methodInvokesCoordinatorGetter(
            DexFile dex,
            MethodDef def,
            String ownerDescriptor,
            MethodId getter) {
        short[] insns = dex.readCodeUnits(def.codeOffset);
        for (int i = 0; i + 1 < insns.length; i++) {
            int opcode = insns[i] & 0xff;
            if (!((opcode >= 0x6e && opcode <= 0x72)
                    || (opcode >= 0x74 && opcode <= 0x78))) {
                continue;
            }
            int methodIndex = insns[i + 1] & 0xffff;
            if (methodIndex < 0 || methodIndex >= dex.methods.length) continue;
            MethodId target = dex.methods[methodIndex];
            Proto targetProto = dex.protos[target.protoIndex];
            if (ownerDescriptor.equals(target.ownerDescriptor)
                    && getter.name.equals(target.name)
                    && targetProto.parameterDescriptors.length == 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean methodReferencesField(
            DexFile dex,
            MethodDef method,
            String ownerDescriptor,
            String fieldName,
            String fieldTypeDescriptor) {
        short[] insns = dex.readCodeUnits(method.codeOffset);
        for (int i = 0; i + 1 < insns.length; i++) {
            int opcode = insns[i] & 0xff;
            if (!isFieldOpcode(opcode)) {
                continue;
            }
            int fieldIndex = insns[i + 1] & 0xffff;
            if (fieldIndex < 0 || fieldIndex >= dex.fields.length) {
                continue;
            }
            FieldId field = dex.fields[fieldIndex];
            if (ownerDescriptor.equals(field.ownerDescriptor)
                    && fieldName.equals(field.name)
                    && fieldTypeDescriptor.equals(field.typeDescriptor)) {
                return true;
            }
        }
        return false;
    }

    private static String findToolbarTabletCheckCast(
            DexFile dex,
            MethodDef method) {
        if (method.codeOffset == 0) {
            return null;
        }
        short[] insns = dex.readCodeUnits(method.codeOffset);
        for (int i = 0; i + 1 < insns.length; i++) {
            int opcode = insns[i] & 0xff;
            if (opcode != 0x1f) {
                continue;
            }
            int typeIndex = insns[i + 1] & 0xffff;
            if (typeIndex < 0 || typeIndex >= dex.types.length) {
                continue;
            }
            String descriptor = dex.types[typeIndex];
            if (TOOLBAR_TABLET.equals(descriptor)
                    || descriptor.endsWith("/ToolbarTablet;")) {
                return descriptor;
            }
        }
        return null;
    }

    private static boolean isFieldOpcode(int opcode) {
        return (opcode >= 0x52 && opcode <= 0x6d)
                || (opcode >= 0xe3 && opcode <= 0xea);
    }

    private static boolean implementsInterface(
            String classDescriptor,
            String targetInterface,
            Map<String, DexFile> classOwners,
            Set<String> visited) {
        if (!visited.add(classDescriptor)) {
            return false;
        }

        DexFile dex = classOwners.get(classDescriptor);
        if (dex == null) {
            return false;
        }
        ClassDef def = dex.classes.get(classDescriptor);
        if (def == null) {
            return false;
        }

        for (String iface : dex.getInterfaces(def)) {
            if (targetInterface.equals(iface)
                    || implementsInterface(
                            iface, targetInterface, classOwners, visited)) {
                return true;
            }
        }

        return def.superDescriptor != null
                && implementsInterface(
                        def.superDescriptor,
                        targetInterface,
                        classOwners,
                        visited);
    }

    private static boolean containsFieldType(
            List<FieldId> fields, String descriptor) {
        for (FieldId field : fields) {
            if (descriptor.equals(field.typeDescriptor)) {
                return true;
            }
        }
        return false;
    }

    private static int countPrimitiveBooleanFields(List<FieldId> fields) {
        int count = 0;
        for (FieldId field : fields) {
            if (BOOLEAN.equals(field.typeDescriptor)) {
                count++;
            }
        }
        return count;
    }

    private static int indexOf(String[] values, String target) {
        for (int i = 0; i < values.length; i++) {
            if (target.equals(values[i])) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isObjectDescriptor(String descriptor) {
        return descriptor != null
                && descriptor.length() >= 2
                && descriptor.charAt(0) == 'L'
                && descriptor.charAt(descriptor.length() - 1) == ';';
    }

    private static String descriptorToClassName(String descriptor) {
        if (!isObjectDescriptor(descriptor)) {
            throw new IllegalArgumentException(
                    "Not an object descriptor: " + descriptor);
        }
        return descriptor.substring(1, descriptor.length() - 1)
                .replace('/', '.');
    }

    private static List<DexFile> loadApplicationDexFiles(
            ApplicationInfo appInfo) throws IOException {
        if (appInfo == null) {
            throw new IOException("ApplicationInfo is null");
        }

        ArrayList<String> apkPaths = new ArrayList<>();
        if (appInfo.sourceDir != null) {
            apkPaths.add(appInfo.sourceDir);
        }
        if (appInfo.splitSourceDirs != null) {
            for (String path : appInfo.splitSourceDirs) {
                if (path != null) {
                    apkPaths.add(path);
                }
            }
        }

        if (apkPaths.isEmpty()) {
            throw new IOException("Chrome APK path list is empty");
        }

        ArrayList<DexFile> dexFiles = new ArrayList<>();
        IOException firstFailure = null;
        for (String path : apkPaths) {
            try {
                loadDexFiles(path, dexFiles);
            } catch (IOException e) {
                // Language / feature splits do not all need to be readable for
                // symbol resolution. Keep scanning other paths; base.apk is
                // sufficient on the currently verified Chrome builds.
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }

        if (dexFiles.isEmpty()) {
            throw new IOException(
                    "No readable classes*.dex found in Chrome APKs",
                    firstFailure);
        }
        return dexFiles;
    }

    private static void loadDexFiles(
            String apkPath, List<DexFile> output) throws IOException {
        if (apkPath == null) {
            return;
        }

        try (ZipFile zip = new ZipFile(apkPath)) {
            zip.stream()
                    .filter(entry -> !entry.isDirectory())
                    .filter(entry -> entry.getName().matches(
                            "classes(?:[0-9]+)?\\.dex"))
                    .forEach(entry -> {
                        try {
                            output.add(new DexFile(
                                    apkPath + "!" + entry.getName(),
                                    readAll(zip, entry)));
                        } catch (IOException e) {
                            throw new DexReadRuntimeException(e);
                        }
                    });
        } catch (DexReadRuntimeException e) {
            throw e.ioException;
        }
    }

    private static byte[] readAll(ZipFile zip, ZipEntry entry)
            throws IOException {
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output =
                     new ByteArrayOutputStream((int) Math.max(
                             4096, Math.min(Integer.MAX_VALUE, entry.getSize())))) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read != 0) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toByteArray();
        }
    }

    private static final class DexReadRuntimeException
            extends RuntimeException {
        final IOException ioException;

        DexReadRuntimeException(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }
    }

    private static final class MethodCandidate {
        final DexFile dex;
        final int methodIndex;
        final String ownerDescriptor;
        final int score;

        MethodCandidate(
                DexFile dex,
                int methodIndex,
                String ownerDescriptor,
                int score) {
            this.dex = dex;
            this.methodIndex = methodIndex;
            this.ownerDescriptor = ownerDescriptor;
            this.score = score;
        }
    }

    private static final class SupplierCandidate {
        final String classDescriptor;
        final int score;
        final String toolbarTabletCastDescriptor;

        SupplierCandidate(
                String classDescriptor,
                int score,
                String toolbarTabletCastDescriptor) {
            this.classDescriptor = classDescriptor;
            this.score = score;
            this.toolbarTabletCastDescriptor = toolbarTabletCastDescriptor;
        }
    }

    private static final class GetterCandidate {
        final String ownerDescriptor;
        final String methodName;

        GetterCandidate(
                String ownerDescriptor,
                String methodName) {
            this.ownerDescriptor = ownerDescriptor;
            this.methodName = methodName;
        }
    }

    private static final class MenuCandidate {
        final String methodName;
        final String fourthParameterDescriptor;
        final int score;

        MenuCandidate(
                String methodName,
                String fourthParameterDescriptor,
                int score) {
            this.methodName = methodName;
            this.fourthParameterDescriptor = fourthParameterDescriptor;
            this.score = score;
        }
    }

    private static final class Proto {
        final String returnDescriptor;
        final String[] parameterDescriptors;

        Proto(String returnDescriptor, String[] parameterDescriptors) {
            this.returnDescriptor = returnDescriptor;
            this.parameterDescriptors = parameterDescriptors;
        }
    }

    private static final class FieldId {
        final String ownerDescriptor;
        final String typeDescriptor;
        final String name;

        FieldId(
                String ownerDescriptor,
                String typeDescriptor,
                String name) {
            this.ownerDescriptor = ownerDescriptor;
            this.typeDescriptor = typeDescriptor;
            this.name = name;
        }
    }

    private static final class MethodId {
        final String ownerDescriptor;
        final String name;
        final int protoIndex;

        MethodId(String ownerDescriptor, String name, int protoIndex) {
            this.ownerDescriptor = ownerDescriptor;
            this.name = name;
            this.protoIndex = protoIndex;
        }
    }

    private static final class ClassDef {
        final String descriptor;
        final String superDescriptor;
        final int interfacesOffset;
        final int classDataOffset;

        ClassDef(
                String descriptor,
                String superDescriptor,
                int interfacesOffset,
                int classDataOffset) {
            this.descriptor = descriptor;
            this.superDescriptor = superDescriptor;
            this.interfacesOffset = interfacesOffset;
            this.classDataOffset = classDataOffset;
        }
    }

    private static final class MethodDef {
        final int methodIndex;
        final int accessFlags;
        final int codeOffset;

        MethodDef(int methodIndex, int accessFlags, int codeOffset) {
            this.methodIndex = methodIndex;
            this.accessFlags = accessFlags;
            this.codeOffset = codeOffset;
        }
    }

    private static final class ClassMembers {
        final List<FieldId> fields;
        final List<MethodDef> methods;

        ClassMembers(List<FieldId> fields, List<MethodDef> methods) {
            this.fields = fields;
            this.methods = methods;
        }
    }

    private static final class DexFile {
        private final String source;
        private final byte[] data;
        private final String[] strings;
        private final String[] types;
        private final Proto[] protos;
        private final FieldId[] fields;
        private final MethodId[] methods;
        private final Map<String, ClassDef> classes = new HashMap<>();
        private final Map<String, ClassMembers> memberCache = new HashMap<>();

        DexFile(String source, byte[] data) throws IOException {
            this.source = source;
            this.data = data;
            if (data.length < 112
                    || data[0] != 'd'
                    || data[1] != 'e'
                    || data[2] != 'x'
                    || data[3] != '\n') {
                throw new IOException("Invalid DEX: " + source);
            }

            int stringIdsSize = readInt(56);
            int stringIdsOffset = readInt(60);
            int typeIdsSize = readInt(64);
            int typeIdsOffset = readInt(68);
            int protoIdsSize = readInt(72);
            int protoIdsOffset = readInt(76);
            int fieldIdsSize = readInt(80);
            int fieldIdsOffset = readInt(84);
            int methodIdsSize = readInt(88);
            int methodIdsOffset = readInt(92);
            int classDefsSize = readInt(96);
            int classDefsOffset = readInt(100);

            strings = new String[stringIdsSize];
            for (int i = 0; i < stringIdsSize; i++) {
                int offset = readInt(stringIdsOffset + i * 4);
                strings[i] = readString(offset);
            }

            types = new String[typeIdsSize];
            for (int i = 0; i < typeIdsSize; i++) {
                int stringIndex = readInt(typeIdsOffset + i * 4);
                types[i] = strings[stringIndex];
            }

            protos = new Proto[protoIdsSize];
            for (int i = 0; i < protoIdsSize; i++) {
                int offset = protoIdsOffset + i * 12;
                int returnTypeIndex = readInt(offset + 4);
                int parametersOffset = readInt(offset + 8);
                protos[i] = new Proto(
                        types[returnTypeIndex],
                        readTypeList(parametersOffset));
            }

            fields = new FieldId[fieldIdsSize];
            for (int i = 0; i < fieldIdsSize; i++) {
                int offset = fieldIdsOffset + i * 8;
                int classIndex = readUShort(offset);
                int typeIndex = readUShort(offset + 2);
                int nameIndex = readInt(offset + 4);
                fields[i] = new FieldId(
                        types[classIndex],
                        types[typeIndex],
                        strings[nameIndex]);
            }

            methods = new MethodId[methodIdsSize];
            for (int i = 0; i < methodIdsSize; i++) {
                int offset = methodIdsOffset + i * 8;
                int classIndex = readUShort(offset);
                int protoIndex = readUShort(offset + 2);
                int nameIndex = readInt(offset + 4);
                methods[i] = new MethodId(
                        types[classIndex],
                        strings[nameIndex],
                        protoIndex);
            }

            for (int i = 0; i < classDefsSize; i++) {
                int offset = classDefsOffset + i * 32;
                int classIndex = readInt(offset);
                int superIndex = readInt(offset + 8);
                int interfacesOffset = readInt(offset + 12);
                int classDataOffset = readInt(offset + 24);
                String descriptor = types[classIndex];
                String superDescriptor = superIndex == -1
                        ? null : types[superIndex];
                classes.put(
                        descriptor,
                        new ClassDef(
                                descriptor,
                                superDescriptor,
                                interfacesOffset,
                                classDataOffset));
            }
        }

        List<FieldId> getDefinedFields(ClassDef def) {
            return getMembers(def).fields;
        }

        List<MethodDef> getDefinedMethods(ClassDef def) {
            return getMembers(def).methods;
        }

        String[] getInterfaces(ClassDef def) {
            return readTypeList(def.interfacesOffset);
        }

        MethodDef findDefinedMethod(ClassDef def, int methodIndex) {
            for (MethodDef method : getDefinedMethods(def)) {
                if (method.methodIndex == methodIndex) {
                    return method;
                }
            }
            return null;
        }

        MethodDef findDefinedMethod(
                ClassDef def,
                String name,
                String returnDescriptor,
                String[] parameterDescriptors) {
            for (MethodDef method : getDefinedMethods(def)) {
                MethodId id = methods[method.methodIndex];
                if (!name.equals(id.name)) {
                    continue;
                }
                Proto proto = protos[id.protoIndex];
                if (returnDescriptor.equals(proto.returnDescriptor)
                        && Arrays.equals(
                                parameterDescriptors,
                                proto.parameterDescriptors)) {
                    return method;
                }
            }
            return null;
        }

        short[] readCodeUnits(int codeOffset) {
            if (codeOffset <= 0 || codeOffset + 16 > data.length) {
                return new short[0];
            }
            int size = readInt(codeOffset + 12);
            if (size < 0 || codeOffset + 16L + size * 2L > data.length) {
                return new short[0];
            }
            short[] out = new short[size];
            int cursor = codeOffset + 16;
            for (int i = 0; i < size; i++) {
                out[i] = (short) readUShort(cursor + i * 2);
            }
            return out;
        }

        private ClassMembers getMembers(ClassDef def) {
            ClassMembers cached = memberCache.get(def.descriptor);
            if (cached != null) {
                return cached;
            }

            ArrayList<FieldId> classFields = new ArrayList<>();
            ArrayList<MethodDef> classMethods = new ArrayList<>();
            if (def.classDataOffset != 0) {
                int[] cursor = new int[]{def.classDataOffset};
                int staticFieldsSize = readUleb128(cursor);
                int instanceFieldsSize = readUleb128(cursor);
                int directMethodsSize = readUleb128(cursor);
                int virtualMethodsSize = readUleb128(cursor);

                int fieldIndex = 0;
                for (int i = 0; i < staticFieldsSize; i++) {
                    fieldIndex += readUleb128(cursor);
                    readUleb128(cursor);
                    if (fieldIndex >= 0 && fieldIndex < fields.length) {
                        classFields.add(fields[fieldIndex]);
                    }
                }

                fieldIndex = 0;
                for (int i = 0; i < instanceFieldsSize; i++) {
                    fieldIndex += readUleb128(cursor);
                    readUleb128(cursor);
                    if (fieldIndex >= 0 && fieldIndex < fields.length) {
                        classFields.add(fields[fieldIndex]);
                    }
                }

                int methodIndex = 0;
                for (int i = 0; i < directMethodsSize; i++) {
                    methodIndex += readUleb128(cursor);
                    int accessFlags = readUleb128(cursor);
                    int codeOffset = readUleb128(cursor);
                    classMethods.add(
                            new MethodDef(
                                    methodIndex, accessFlags, codeOffset));
                }

                methodIndex = 0;
                for (int i = 0; i < virtualMethodsSize; i++) {
                    methodIndex += readUleb128(cursor);
                    int accessFlags = readUleb128(cursor);
                    int codeOffset = readUleb128(cursor);
                    classMethods.add(
                            new MethodDef(
                                    methodIndex, accessFlags, codeOffset));
                }
            }

            ClassMembers members =
                    new ClassMembers(classFields, classMethods);
            memberCache.put(def.descriptor, members);
            return members;
        }

        private String[] readTypeList(int offset) {
            if (offset == 0) {
                return new String[0];
            }
            int size = readInt(offset);
            String[] result = new String[size];
            for (int i = 0; i < size; i++) {
                int typeIndex = readUShort(offset + 4 + i * 2);
                result[i] = types[typeIndex];
            }
            return result;
        }

        private String readString(int offset) {
            int[] cursor = new int[]{offset};
            readUleb128(cursor);
            int start = cursor[0];
            int end = start;
            while (end < data.length && data[end] != 0) {
                end++;
            }
            return new String(
                    data, start, end - start, StandardCharsets.UTF_8);
        }

        private int readUleb128(int[] cursorRef) {
            int result = 0;
            int shift = 0;
            int cursor = cursorRef[0];
            while (cursor < data.length && shift <= 28) {
                int value = data[cursor++] & 0xff;
                result |= (value & 0x7f) << shift;
                if ((value & 0x80) == 0) {
                    cursorRef[0] = cursor;
                    return result;
                }
                shift += 7;
            }
            cursorRef[0] = cursor;
            return result;
        }

        private int readUShort(int offset) {
            return (data[offset] & 0xff)
                    | ((data[offset + 1] & 0xff) << 8);
        }

        private int readInt(int offset) {
            return (data[offset] & 0xff)
                    | ((data[offset + 1] & 0xff) << 8)
                    | ((data[offset + 2] & 0xff) << 16)
                    | ((data[offset + 3] & 0xff) << 24);
        }

        @Override
        public String toString() {
            return source;
        }
    }


    /** Source-derived pair: edit-card eligibility and its desktop capability check. */
    static final class OmniboxSymbols {
        final String processorClass, processorMethod, matchClass;
        final String capabilityClass, capabilityMethod;
        OmniboxSymbols(String processorClass, String processorMethod, String matchClass,
                String capabilityClass, String capabilityMethod) {
            this.processorClass = processorClass;
            this.processorMethod = processorMethod;
            this.matchClass = matchClass;
            this.capabilityClass = capabilityClass;
            this.capabilityMethod = capabilityMethod;
        }
    }

    static OmniboxSymbols resolveOmnibox(ApplicationInfo info) throws IOException {
        List<DexFile> files = loadApplicationDexFiles(info);
        Map<String, DexFile> owners = new HashMap<>();
        for (DexFile dex : files) {
            for (String owner : dex.classes.keySet()) owners.put(owner, dex);
        }
        OmniboxSymbols found = null;
        for (DexFile dex : files) {
            // Skip DEX files that do not contain the source's metric anchor.
            if (!Arrays.asList(dex.strings).contains("Omnibox.EditUrlSuggestion.Tap")) continue;
            for (ClassDef owner : dex.classes.values()) {
                List<MethodDef> methods = dex.getDefinedMethods(owner);
                boolean tap = false, action = false;
                for (MethodDef method : methods) {
                    tap |= methodReferencesString(dex, method, "Omnibox.EditUrlSuggestion.Tap");
                    action |= methodReferencesString(dex, method, "Omnibox.EditUrlSuggestion.Copy")
                            || methodReferencesString(dex, method, "Omnibox.EditUrlSuggestion.Edit");
                    if (tap && action) break;
                }
                if (!tap || !action) continue;
                for (MethodDef method : methods) {
                    if ((method.accessFlags & ACC_STATIC) != 0 || method.codeOffset == 0) continue;
                    MethodId id = dex.methods[method.methodIndex];
                    Proto proto = dex.protos[id.protoIndex];
                    String[] p = proto.parameterDescriptors;
                    if (!BOOLEAN.equals(proto.returnDescriptor) || p.length != 2
                            || !isObjectDescriptor(p[0]) || !INT.equals(p[1])) continue;
                    // AutocompleteMatch is a JNI-backed class. Require its original
                    // descriptor when available, otherwise refuse a different named type.
                    if (owners.containsKey("Lorg/chromium/components/omnibox/AutocompleteMatch;")
                            && !p[0].equals("Lorg/chromium/components/omnibox/AutocompleteMatch;")) continue;
                    MethodId capability = null;
                    short[] code = dex.readCodeUnits(method.codeOffset);
                    for (int i = 0; i < code.length;) {
                        int op = code[i] & 0xff;
                        int width = instructionWidth(code, i);
                        if (width <= 0 || i + width > code.length) break;
                        if ((op == 0x71 || op == 0x77) && width == 3) {
                            int index = code[i + 1] & 0xffff;
                            if (index < dex.methods.length) {
                                MethodId candidate = dex.methods[index];
                                Proto cp = dex.protos[candidate.protoIndex];
                                if (BOOLEAN.equals(cp.returnDescriptor)
                                        && Arrays.equals(cp.parameterDescriptors,
                                                new String[]{"Landroid/content/Context;"})
                                        && owners.containsKey(candidate.ownerDescriptor)) {
                                    if (capability != null && capability != candidate) {
                                        capability = null;
                                        break;
                                    }
                                    capability = candidate;
                                }
                            }
                        }
                        i += width;
                    }
                    if (capability == null) continue;
                    OmniboxSymbols next = new OmniboxSymbols(
                            descriptorToClassName(owner.descriptor), id.name,
                            descriptorToClassName(p[0]),
                            descriptorToClassName(capability.ownerDescriptor), capability.name);
                    if (found != null) throw new IOException("Ambiguous Omnibox processor/capability");
                    found = next;
                }
            }
        }
        if (found == null) throw new IOException("Omnibox source anchors not uniquely resolved (possibly inlined)");
        return found;
    }

    // DEX instruction widths in 16-bit code units. Do not mistake an operand
    // or switch/array payload for an invoke-static opcode.
    private static int instructionWidth(short[] code, int i) {
        int unit = code[i] & 0xffff, op = unit & 0xff;
        if (op == 0 && unit != 0) {
            if (i + 1 >= code.length) return 0;
            int count = code[i + 1] & 0xffff;
            if (unit == 0x0100) return 4 + count * 2;
            if (unit == 0x0200) return 2 + count * 4;
            if (unit == 0x0300 && i + 3 < code.length) {
                long size = (code[i + 2] & 0xffffL) | ((code[i + 3] & 0xffffL) << 16);
                long width = 4 + (count * size + 1) / 2;
                return width > Integer.MAX_VALUE ? 0 : (int) width;
            }
            return 0;
        }
        if (op == 0x18) return 5;
        if (op == 0xfa || op == 0xfb) return 4;
        if (op == 0x03 || op == 0x06 || op == 0x09 || op == 0x14 || op == 0x17
                || op == 0x1b || op == 0x24 || op == 0x25 || op == 0x26
                || op == 0x2a || op == 0x2b || op == 0x2c
                || (op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)
                || op == 0xfc || op == 0xfd) return 3;
        if (op == 0x02 || op == 0x05 || op == 0x08 || op == 0x13 || op == 0x15
                || op == 0x16 || op == 0x19 || op == 0x1a || op == 0x1c
                || op == 0x1f || op == 0x20 || op == 0x22 || op == 0x23
                || op == 0x29 || (op >= 0x2d && op <= 0x3d)
                || (op >= 0x44 && op <= 0x6d) || (op >= 0x90 && op <= 0xaf)
                || (op >= 0xd0 && op <= 0xe2) || op == 0xfe || op == 0xff) return 2;
        if (op == 0x00 || op == 0x01 || op == 0x04 || op == 0x07
                || (op >= 0x0a && op <= 0x12) || op == 0x1d || op == 0x1e
                || op == 0x21 || op == 0x27 || op == 0x28
                || (op >= 0x7b && op <= 0x8f) || (op >= 0xb0 && op <= 0xcf)) return 1;
        return 0;
    }

    /**
     * Locates Chromium TabFavicon's R8-obfuscated static bitmap accessor.
     * The upstream class defines BOTH Bitmap(Tab) and Bitmap(Tab, boolean).
     * Find an exact matching *pair* by DEX method signatures, not names;
     * reject ambiguous pairs rather than invoking an unrelated function.
     *
     * Called on a worker only after the installed Chrome package version
     * changes. Return [ownerClass, methodName, tabParameterDescriptor].
     */
    static String[] findFaviconAccessor(ApplicationInfo appInfo) throws IOException {
        List<DexFile> dexes = loadApplicationDexFiles(appInfo);
        ArrayList<String[]> exact = new ArrayList<>();
        ArrayList<String[]> named = new ArrayList<>();
        for (DexFile dex : dexes) {
            for (ClassDef owner : dex.classes.values()) {
                List<MethodDef> methods = dex.getDefinedMethods(owner);
                // TabFavicon has JNI entry points: static Bitmap(Tab,boolean),
                // static TabFavicon(Tab), and static void(Tab,Bitmap,GURL).
                // Tie all three to the same descriptor, even if Tab is renamed.
                for (MethodDef def : methods) {
                    if ((def.accessFlags & ACC_STATIC) == 0) continue;
                    MethodId id = dex.methods[def.methodIndex];
                    Proto proto = dex.protos[id.protoIndex];
                    String[] p = proto.parameterDescriptors;
                    if (!"Landroid/graphics/Bitmap;".equals(proto.returnDescriptor)
                            || p.length != 2 || !BOOLEAN.equals(p[1])
                            || !isObjectDescriptor(p[0])) continue;
                    String tab = p[0];
                    String from = null, async = null;
                    int creators = 0, ownerGetters = 0, callbacks = 0, requests = 0;
                    for (MethodDef other : methods) {
                        MethodId m = dex.methods[other.methodIndex];
                        Proto op = dex.protos[m.protoIndex];
                        String[] q = op.parameterDescriptors;
                        if ((other.accessFlags & ACC_STATIC) != 0) {
                            if (q.length == 1 && tab.equals(q[0])
                                    && owner.descriptor.equals(op.returnDescriptor)) {
                                ownerGetters++;
                                // TabFavicon.get(Tab) has the same signature.
                                // Only from(Tab) constructs its own user-data object.
                                if ("from".equals(m.name)
                                        || constructsOwner(dex, other, owner.descriptor)) {
                                    from = m.name;
                                    creators++;
                                }
                            }
                            if ("V".equals(op.returnDescriptor) && q.length == 3
                                    && tab.equals(q[0])
                                    && "Landroid/graphics/Bitmap;".equals(q[1])
                                    && "Lorg/chromium/url/GURL;".equals(q[2])) callbacks++;
                        } else if (q.length == 0 && isObjectDescriptor(op.returnDescriptor)
                                && (op.returnDescriptor.equals("Lorg/chromium/base/Promise;")
                                    || methodReferencesString(dex, other, "Not eligible for favicon"))) {
                            async = m.name;
                            requests++;
                        }
                    }
                    String[] candidate = {descriptorToClassName(owner.descriptor), id.name,
                            tab, creators == 1 ? from : null, requests == 1 ? async : null};
                    if (ownerGetters >= 1 && callbacks == 1) exact.add(candidate);
                    else if (tab.endsWith("/tab/Tab;") && creators == 1) named.add(candidate);
                }
            }
        }
        String[] selected = exact.size() == 1 ? exact.get(0)
                : exact.isEmpty() && named.size() == 1 ? named.get(0) : null;
        // Independent native local DB path. Resolve a named helper or a
        // call site in TabFavicon's verified async method; never guess between
        // the identical local and foreign favicon signatures.
        String[] local = findLocalFavicon(dexes, selected);
        if (selected == null && local == null) {
            throw new IOException("TabFavicon candidates exact=" + exact.size()
                    + " named=" + named.size() + "; local helper unresolved");
        }
        String[] result = selected == null ? new String[8] : Arrays.copyOf(selected, 8);
        if (local != null) System.arraycopy(local, 0, result, 5, 3);
        return result;
    }

    private static boolean localFaviconShape(Proto p) {
        String[] a = p.parameterDescriptors;
        return BOOLEAN.equals(p.returnDescriptor) && a.length == 5
                && isObjectDescriptor(a[0]) && "Lorg/chromium/url/GURL;".equals(a[1])
                && INT.equals(a[2]) && BOOLEAN.equals(a[3]) && isObjectDescriptor(a[4]);
    }

    private static String[] findLocalFavicon(List<DexFile> dexes, String[] selected) {
        final String namedOwner = "Lorg/chromium/chrome/browser/ui/favicon/FaviconHelper;";
        ArrayList<String[]> candidates = new ArrayList<>();
        for (DexFile dex : dexes) {
            ClassDef owner = dex.classes.get(namedOwner);
            if (owner == null) continue;
            for (MethodDef def : dex.getDefinedMethods(owner)) {
                MethodId m = dex.methods[def.methodIndex];
                if ("getLocalFaviconImageForURL".equals(m.name)
                        && (def.accessFlags & ACC_STATIC) == 0
                        && localFaviconShape(dex.protos[m.protoIndex])) {
                    String[] entry = localEntry(dex, owner, m.name);
                    if (entry != null) candidates.add(entry);
                }
            }
        }
        if (candidates.size() == 1) return candidates.get(0);
        if (selected == null || selected[0] == null || selected[4] == null) return null;
        String desc = "L" + selected[0].replace('.', '/') + ";";
        for (DexFile dex : dexes) {
            ClassDef caller = dex.classes.get(desc);
            if (caller == null) continue;
            for (MethodDef def : dex.getDefinedMethods(caller)) {
                MethodId m = dex.methods[def.methodIndex];
                if (!selected[4].equals(m.name) || def.codeOffset == 0) continue;
                short[] code = dex.readCodeUnits(def.codeOffset);
                for (int i = 0; i < code.length;) {
                    int width = instructionWidth(code, i);
                    if (width <= 0 || i + width > code.length) break;
                    int op = code[i] & 0xff;
                    if (op == 0x6e || op == 0x74) {
                        int mi = code[i + 1] & 0xffff;
                        if (mi < dex.methods.length) {
                            MethodId called = dex.methods[mi];
                            if (localFaviconShape(dex.protos[called.protoIndex])) {
                                for (DexFile targetDex : dexes) {
                                    ClassDef target = targetDex.classes.get(called.ownerDescriptor);
                                    if (target == null) continue;
                                    String[] entry = localEntry(targetDex, target, called.name);
                                    if (entry != null && candidates.stream().noneMatch(
                                            old -> Arrays.equals(old, entry))) candidates.add(entry);
                                }
                            }
                        }
                    }
                    i += width;
                }
            }
        }
        return candidates.size() == 1 ? candidates.get(0) : null;
    }

    private static String[] localEntry(DexFile dex, ClassDef owner, String method) {
        int constructors = 0, destroyCount = 0, requests = 0;
        String destroy = null, namedDestroy = null;
        for (MethodDef def : dex.getDefinedMethods(owner)) {
            if ((def.accessFlags & ACC_STATIC) != 0) continue;
            MethodId m = dex.methods[def.methodIndex];
            Proto p = dex.protos[m.protoIndex];
            if (m.name.equals(method) && localFaviconShape(p)) requests++;
            if ("V".equals(p.returnDescriptor) && p.parameterDescriptors.length == 0) {
                if ("<init>".equals(m.name)) constructors++;
                else {
                    destroy = m.name;
                    destroyCount++;
                    if ("destroy".equals(m.name)) namedDestroy = m.name;
                }
            }
        }
        if (constructors != 1 || requests != 1) return null;
        boolean nativePtr = false;
        for (FieldId f : dex.getDefinedFields(owner)) if ("J".equals(f.typeDescriptor)) nativePtr = true;
        if (!nativePtr) return null;
        String cleanup = namedDestroy != null ? namedDestroy : destroyCount == 1 ? destroy : null;
        return cleanup == null ? null : new String[]{descriptorToClassName(owner.descriptor),
                method, cleanup};
    }

    private static boolean constructsOwner(DexFile dex, MethodDef method, String owner) {
        if (method.codeOffset == 0) return false;
        short[] code = dex.readCodeUnits(method.codeOffset);
        for (int i = 0; i < code.length;) {
            int width = instructionWidth(code, i);
            if (width <= 0 || i + width > code.length) break;
            if ((code[i] & 0xff) == 0x22 && width == 2) {
                int index = code[i + 1] & 0xffff;
                if (index < dex.types.length && owner.equals(dex.types[index])) return true;
            }
            i += width;
        }
        return false;
    }

    // Source-defined JNI callback shapes for MostVisitedSitesBridge and
    // DevTools' feature gate. Descriptors are discovered from the installed
    // DEX rather than from a version-to-obfuscated-name table.
    static String[] resolveAdditionalUi(ApplicationInfo info) throws IOException {
        List<DexFile> files = loadApplicationDexFiles(info);
        ArrayList<String[]> mv = new ArrayList<>();
        ArrayList<String[]> dev = new ArrayList<>();
        ArrayList<String[]> allowedDev = new ArrayList<>();
        ArrayList<String[]> controllers = new ArrayList<>();
        ArrayList<String[]> builders = new ArrayList<>();
        final String contextType = "Landroid/content/Context;";
        final String uriType = "Landroid/net/Uri;";
        for (DexFile dex : files) {
            boolean hasFeature = Arrays.asList(dex.strings).contains("AndroidDevToolsFrontend");
            for (ClassDef owner : dex.classes.values()) {
                List<MethodDef> methods = dex.getDefinedMethods(owner);
                boolean siteFactory = false, siteCallback = false, iconCallback = false;
                String singleton = null, launch = null, lensParams = null;
                int singletonCount = 0, launchCount = 0;
                boolean builderConstructor = false;
                String imageSetter = null;
                int imageSetterCount = 0;
                for (MethodDef def : methods) {
                    MethodId id = dex.methods[def.methodIndex];
                    Proto p = dex.protos[id.protoIndex];
                    String[] a = p.parameterDescriptors;
                    boolean isStatic = (def.accessFlags & ACC_STATIC) != 0;
                    if (isStatic && a.length == 0 && owner.descriptor.equals(p.returnDescriptor)) {
                        singleton = id.name; singletonCount++;
                    }
                    if (!isStatic && VOID.equals(p.returnDescriptor) && a.length == 2
                            && contextType.equals(a[0]) && isObjectDescriptor(a[1])) {
                        launch = id.name; lensParams = a[1]; launchCount++;
                    }
                    if ("<init>".equals(id.name) && Arrays.equals(a,new String[]{INT,BOOLEAN}))
                        builderConstructor = true;
                    if (!isStatic && owner.descriptor.equals(p.returnDescriptor)
                            && Arrays.equals(a,new String[]{uriType})) {
                        imageSetter = id.name; imageSetterCount++;
                    }
                    if (isStatic && isObjectDescriptor(p.returnDescriptor)
                            && Arrays.equals(a, new String[]{"Ljava/lang/String;",
                            "Lorg/chromium/url/GURL;", INT, INT, INT})) siteFactory = true;
                    if (!isStatic && VOID.equals(p.returnDescriptor)) {
                        if (Arrays.equals(a, new String[]{BOOLEAN, "Ljava/util/List;"}))
                            siteCallback = true;
                        if (Arrays.equals(a, new String[]{"Lorg/chromium/url/GURL;"}))
                            iconCallback = true;
                    }
                    boolean gateOnly = Arrays.equals(a,new String[]{contextType});
                    boolean allowedGate = a.length == 3
                            && (contextType.equals(a[0]) || "Landroid/app/Activity;".equals(a[0]))
                            && "Lorg/chromium/chrome/browser/profiles/Profile;".equals(a[1])
                            && "Lorg/chromium/content_public/browser/WebContents;".equals(a[2]);
                    if (!hasFeature || !isStatic || !BOOLEAN.equals(p.returnDescriptor)
                            || (!gateOnly && !allowedGate)
                            || def.codeOffset == 0
                            || !methodReferencesString(dex, def, "AndroidDevToolsFrontend")) continue;
                    short[] code = dex.readCodeUnits(def.codeOffset);
                    ArrayList<MethodId> candidates = new ArrayList<>();
                    ArrayList<MethodId> features = new ArrayList<>();
                    boolean nativePolicyCall = false;
                    for (int i = 0; i < code.length;) {
                        int width = instructionWidth(code, i);
                        if (width <= 0 || i + width > code.length) break;
                        int op = code[i] & 0xff;
                        if ((op >= 0x6e && op <= 0x72) || (op >= 0x74 && op <= 0x78)) {
                            int mi = code[i + 1] & 0xffff;
                            if (mi < dex.methods.length) {
                                MethodId called = dex.methods[mi];
                                Proto cp = dex.protos[called.protoIndex];
                                if (BOOLEAN.equals(cp.returnDescriptor)
                                        && (op == 0x71 || op == 0x77)
                                        && Arrays.equals(cp.parameterDescriptors,new String[]{contextType})
                                        && !called.ownerDescriptor.equals(owner.descriptor)) {
                                    candidates.add(called);
                                }
                                if (BOOLEAN.equals(cp.returnDescriptor)
                                        && Arrays.equals(cp.parameterDescriptors,new String[]{"Ljava/lang/String;"}))
                                    features.add(called);
                                // R8 can inline the JNI adapter into the Java gate.
                                // The shared JNI dispatch remains a boolean call with
                                // the profile/web contents object arguments.
                                if (BOOLEAN.equals(cp.returnDescriptor) &&
                                        (Arrays.equals(cp.parameterDescriptors,new String[]{INT,
                                                "Ljava/lang/Object;","Ljava/lang/Object;"})
                                        || Arrays.equals(cp.parameterDescriptors,new String[]{
                                                "Lorg/chromium/chrome/browser/profiles/Profile;",
                                                "Lorg/chromium/content_public/browser/WebContents;"})))
                                    nativePolicyCall = true;
                            }
                        }
                        i += width;
                    }
                    if (candidates.size() == 1 && (!allowedGate || nativePolicyCall)) {
                        MethodId tablet = candidates.get(0);
                        MethodId feature = features.size() == 1 ? features.get(0) : null;
                        (gateOnly ? dev : allowedDev).add(new String[]{
                                descriptorToClassName(owner.descriptor), id.name,
                                descriptorToClassName(tablet.ownerDescriptor), tablet.name,
                                feature == null ? null : descriptorToClassName(feature.ownerDescriptor),
                                feature == null ? null : feature.name});
                    }
                }
                if (singletonCount == 1 && launchCount == 1) {
                    // LensController exposes the same parameter object on the
                    // WindowAndroid and Context launch overloads.
                    for (MethodDef def : methods) {
                        MethodId id = dex.methods[def.methodIndex];
                        Proto p = dex.protos[id.protoIndex];
                        if ((def.accessFlags & ACC_STATIC) == 0 && VOID.equals(p.returnDescriptor)
                                && Arrays.equals(p.parameterDescriptors,new String[]{WINDOW_ANDROID,lensParams})) {
                            controllers.add(new String[]{descriptorToClassName(owner.descriptor),
                                    singleton, launch, lensParams});
                            break;
                        }
                    }
                }
                if (builderConstructor && imageSetterCount == 1) {
                    for (MethodDef def : methods) {
                        MethodId id = dex.methods[def.methodIndex];
                        Proto p = dex.protos[id.protoIndex];
                        if ((def.accessFlags & ACC_STATIC) == 0 && p.parameterDescriptors.length == 0
                                && isObjectDescriptor(p.returnDescriptor)
                                && !owner.descriptor.equals(p.returnDescriptor)) {
                            builders.add(new String[]{descriptorToClassName(owner.descriptor),
                                    imageSetter,id.name,p.returnDescriptor});
                        }
                    }
                }
                if (siteFactory && siteCallback && iconCallback) {
                    for (MethodDef def : methods) {
                        MethodId id = dex.methods[def.methodIndex];
                        Proto p = dex.protos[id.protoIndex];
                        if ("<init>".equals(id.name) && p.parameterDescriptors.length == 1
                                && isObjectDescriptor(p.parameterDescriptors[0])) {
                            mv.add(new String[]{descriptorToClassName(owner.descriptor),
                                    descriptorToClassName(p.parameterDescriptors[0])});
                        }
                    }
                }
            }
        }
        String[] result = new String[14];
        if (mv.size() == 1) System.arraycopy(mv.get(0), 0, result, 0, 2);
        String[] gate = allowedDev.size() == 1 ? allowedDev.get(0)
                : dev.size() == 1 ? dev.get(0) : null;
        if (gate != null) {
            System.arraycopy(gate,0,result,2,4);
            System.arraycopy(gate,4,result,12,2);
        }
        ArrayList<String[]> lens = new ArrayList<>();
        for (String[] c : controllers) for (String[] b : builders) {
            if (c[3].equals(b[3]))
                lens.add(new String[]{c[0],c[1],c[2],b[0],b[1],b[2]});
        }
        if (lens.size() == 1) System.arraycopy(lens.get(0),0,result,6,6);
        com.atuy.chromemobilecompat.compat.XposedBridge.log(
                "Extra UI resolver: dex=" + files.size() + " mostVisitedCandidates=" + mv.size()
                + " devAvailableCandidates=" + dev.size() + " devPolicyCandidates=" + allowedDev.size()
                + " lensControllers=" + controllers.size() + " lensBuilders=" + builders.size()
                + " lensPairs=" + lens.size());
        return result;
    }

    private ChromeDexResolver() {}
}
