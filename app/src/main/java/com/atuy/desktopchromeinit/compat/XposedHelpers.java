package com.atuy.desktopchromeinit.compat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedInterface;

/**
 * Reflection helpers used by DesktopChromeInit.
 *
 * This intentionally implements only the operations used by this module. It is
 * not a copy of, or dependency on, the legacy XposedHelpers implementation.
 */
public final class XposedHelpers {
    private XposedHelpers() {}

    public static Class<?> findClassIfExists(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static Class<?> findClass(String name, ClassLoader loader) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Class not found: " + name, e);
        }
    }

    public static Object getObjectField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            return field.get(target);
        } catch (Throwable t) {
            throw propagate("getObjectField " + fieldName, t);
        }
    }

    public static int getIntField(Object target, String fieldName) {
        try {
            Field field = findField(target.getClass(), fieldName);
            return field.getInt(target);
        } catch (Throwable t) {
            throw propagate("getIntField " + fieldName, t);
        }
    }

    public static void setObjectField(
            Object target,
            String fieldName,
            Object value) {
        try {
            Field field = findField(target.getClass(), fieldName);
            field.set(target, value);
        } catch (Throwable t) {
            throw propagate("setObjectField " + fieldName, t);
        }
    }

    public static Object callMethod(
            Object receiver,
            String methodName,
            Object... args) {
        try {
            Method method = findCompatibleMethod(
                    receiver.getClass(), methodName, args);
            return method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            throw propagate(
                    "callMethod " + methodName,
                    e.getCause() != null ? e.getCause() : e);
        } catch (Throwable t) {
            throw propagate("callMethod " + methodName, t);
        }
    }

    public static Object newInstance(Class<?> type, Object... args) {
        try {
            Constructor<?> constructor =
                    findCompatibleConstructor(type, args);
            return constructor.newInstance(args);
        } catch (InvocationTargetException e) {
            throw propagate(
                    "newInstance " + type.getName(),
                    e.getCause() != null ? e.getCause() : e);
        } catch (Throwable t) {
            throw propagate("newInstance " + type.getName(), t);
        }
    }

    public static XposedInterface.HookHandle findAndHookMethod(
            Class<?> type,
            String methodName,
            Object... parameterTypesAndCallback) {

        if (parameterTypesAndCallback.length == 0
                || !(parameterTypesAndCallback[
                        parameterTypesAndCallback.length - 1]
                        instanceof XC_MethodHook)) {
            throw new IllegalArgumentException(
                    "last argument must be XC_MethodHook");
        }

        XC_MethodHook callback =
                (XC_MethodHook) parameterTypesAndCallback[
                        parameterTypesAndCallback.length - 1];

        Class<?>[] parameterTypes =
                new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            Object value = parameterTypesAndCallback[i];
            if (!(value instanceof Class<?>)) {
                throw new IllegalArgumentException(
                        "parameter " + i + " is not a Class");
            }
            parameterTypes[i] = (Class<?>) value;
        }

        Method method = findExactMethod(
                type, methodName, parameterTypes);
        return XposedBridge.hookMethod(method, callback);
    }

    private static Field findField(Class<?> start, String name)
            throws NoSuchFieldException {
        Class<?> type = start;
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(start.getName() + "." + name);
    }

    private static Method findExactMethod(
            Class<?> start,
            String name,
            Class<?>[] parameterTypes) {
        Class<?> type = start;
        while (type != null) {
            try {
                Method method =
                        type.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new IllegalStateException(
                "Method not found: " + start.getName() + "." + name);
    }

    private static Method findCompatibleMethod(
            Class<?> start,
            String name,
            Object[] args) {

        Class<?> type = start;
        Method best = null;
        int bestScore = Integer.MIN_VALUE;

        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName())
                        || method.getParameterCount() != args.length) {
                    continue;
                }

                int score = compatibilityScore(
                        method.getParameterTypes(), args);
                if (score > bestScore) {
                    best = method;
                    bestScore = score;
                }
            }
            type = type.getSuperclass();
        }

        if (best == null || bestScore < 0) {
            throw new IllegalStateException(
                    "Compatible method not found: "
                            + start.getName() + "." + name);
        }

        best.setAccessible(true);
        return best;
    }

    private static Constructor<?> findCompatibleConstructor(
            Class<?> type,
            Object[] args) {

        Constructor<?> best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (constructor.getParameterCount() != args.length) {
                continue;
            }

            int score = compatibilityScore(
                    constructor.getParameterTypes(), args);
            if (score > bestScore) {
                best = constructor;
                bestScore = score;
            }
        }

        if (best == null || bestScore < 0) {
            throw new IllegalStateException(
                    "Compatible constructor not found: " + type.getName());
        }

        best.setAccessible(true);
        return best;
    }

    private static int compatibilityScore(
            Class<?>[] parameterTypes,
            Object[] args) {

        int score = 0;
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameter = parameterTypes[i];
            Object arg = args[i];

            if (arg == null) {
                if (parameter.isPrimitive()) {
                    return -1;
                }
                score += 1;
                continue;
            }

            Class<?> actual = arg.getClass();
            Class<?> boxed = box(parameter);

            if (boxed == actual) {
                score += 8;
            } else if (boxed.isAssignableFrom(actual)) {
                score += 4;
            } else {
                return -1;
            }
        }
        return score;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == char.class) return Character.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == void.class) return Void.class;
        return type;
    }

    private static RuntimeException propagate(
            String operation,
            Throwable cause) {
        if (cause instanceof RuntimeException) {
            return (RuntimeException) cause;
        }
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return new IllegalStateException(operation + " failed", cause);
    }
}
