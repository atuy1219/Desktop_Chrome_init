package com.atuy.desktopchromeinit;

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
                String menuHandlerMethodName,
                String menuHandlerFourthParameterDescriptor) {
            this.toolbarManagerClassName = toolbarManagerClassName;
            this.initializeMethodName = initializeMethodName;
            this.initializeParameterDescriptors = initializeParameterDescriptors;
            this.extensionSupplierClassName = extensionSupplierClassName;
            this.supplierToolbarTabletClassName = supplierToolbarTabletClassName;
            this.coordinatorFieldName = coordinatorFieldName;
            this.coordinatorClassName = coordinatorClassName;
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

    private ChromeDexResolver() {}
}
