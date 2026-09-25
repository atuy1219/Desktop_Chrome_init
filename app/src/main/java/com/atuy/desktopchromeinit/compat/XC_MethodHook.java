package com.atuy.desktopchromeinit.compat;

import java.lang.reflect.Executable;

public abstract class XC_MethodHook {
    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam {
        public final Executable method;
        public final Object thisObject;
        public Object[] args;

        private Object result;
        private Throwable throwable;
        private boolean returnEarly;

        MethodHookParam(Executable method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Object getResult() { return result; }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() { return throwable; }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        boolean isReturnEarly() { return returnEarly; }

        void setOriginalOutcome(Object result, Throwable throwable) {
            this.result = result;
            this.throwable = throwable;
            this.returnEarly = false;
        }
    }
}
