package com.atuy.desktopchromeinit.compat;

import android.util.Log;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * Internal compatibility facade backed entirely by the modern libxposed API.
 * No de.robv.android.xposed classes are referenced or packaged.
 */
public final class XposedBridge {
    private static final String TAG = "DesktopChromeInit";
    private static volatile XposedModule module;

    private XposedBridge() {}

    public static void attach(XposedModule xposedModule) {
        module = xposedModule;
    }

    public static XposedInterface.HookHandle hookMethod(
            Executable executable,
            XC_MethodHook callback) {

        XposedModule current = requireModule();
        executable.setAccessible(true);

        return current.hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(chain -> {
                    Object[] args = chain.getArgs().toArray(new Object[0]);
                    XC_MethodHook.MethodHookParam param =
                            new XC_MethodHook.MethodHookParam(
                                    executable,
                                    chain.getThisObject(),
                                    args);

                    callback.beforeHookedMethod(param);

                    if (!param.isReturnEarly()) {
                        Object result = null;
                        Throwable throwable = null;
                        try {
                            result = chain.proceed(param.args);
                        } catch (Throwable t) {
                            throwable = t;
                        }
                        param.setOriginalOutcome(result, throwable);
                    }

                    callback.afterHookedMethod(param);

                    Throwable throwable = param.getThrowable();
                    if (throwable != null) {
                        throw throwable;
                    }
                    return param.getResult();
                });
    }

    public static Set<XposedInterface.HookHandle> hookAllMethods(
            Class<?> type,
            String methodName,
            XC_MethodHook callback) {

        Set<XposedInterface.HookHandle> handles = new LinkedHashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (methodName.equals(method.getName())) {
                handles.add(hookMethod(method, callback));
            }
        }
        return handles;
    }

    public static void log(String message) {
        XposedModule current = module;
        if (current != null) {
            current.log(Log.INFO, TAG, message);
        } else {
            Log.i(TAG, message);
        }
    }

    private static XposedModule requireModule() {
        XposedModule current = module;
        if (current == null) {
            throw new IllegalStateException(
                    "Modern Xposed runtime has not been attached");
        }
        return current;
    }
}
