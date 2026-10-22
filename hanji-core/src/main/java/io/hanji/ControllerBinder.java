package io.hanji;

import io.hanji.json.JsonMapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Binds a controller object to a loaded page: {@code @UiField}, {@code @UiHandler}, {@code @JsCallable}, {@code initialize()}. */
final class ControllerBinder {

    private ControllerBinder() {
    }

    static void bind(Window window, Object controller) throws Exception {
        List<Class<?>> hierarchy = new ArrayList<>();
        for (Class<?> c = controller.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            hierarchy.add(c);
        }
        Collections.reverse(hierarchy);

        for (Class<?> c : hierarchy) {
            for (Field f : c.getDeclaredFields()) {
                UiField ann = f.getAnnotation(UiField.class);
                if (ann != null) {
                    inject(window, controller, f, ann);
                }
            }
        }
        for (Class<?> c : hierarchy) {
            for (Method m : c.getDeclaredMethods()) {
                UiHandler handler = m.getAnnotation(UiHandler.class);
                if (handler != null) {
                    registerHandler(window, controller, m, handler);
                }
                JsCallable callable = m.getAnnotation(JsCallable.class);
                if (callable != null) {
                    registerCallable(window, controller, m, callable);
                }
            }
        }
        Method init = findInitialize(controller.getClass());
        if (init != null) {
            makeAccessible(init);
            if (init.getParameterCount() == 0) {
                init.invoke(controller);
            } else {
                init.invoke(controller, window);
            }
        }
    }

    private static void inject(Window window, Object controller, Field f, UiField ann) throws Exception {
        if (Modifier.isStatic(f.getModifiers())) {
            throw new IllegalStateException("@UiField must not be static: " + f);
        }
        makeAccessible(f);
        Class<?> type = f.getType();
        String id = ann.value().isEmpty() ? f.getName() : ann.value();
        Object value;
        if (type == Window.class) {
            value = window;
        } else if (type == Element.class || type == HasElement.class) {
            value = window.byId(id);
        } else if (HasElement.class.isAssignableFrom(type)) {
            Constructor<?> ctor;
            try {
                ctor = type.getDeclaredConstructor(Element.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("@UiField " + f + ": " + type.getName()
                        + " needs a constructor taking an Element", e);
            }
            makeAccessible(ctor);
            value = ctor.newInstance(window.byId(id));
        } else {
            throw new IllegalStateException("@UiField " + f + ": unsupported type " + type.getName()
                    + " (use Element, a component class or Window)");
        }
        f.set(controller, value);
    }

    private static void registerHandler(Window window, Object controller, Method m, UiHandler ann) {
        Class<?>[] params = m.getParameterTypes();
        if (params.length > 1 || (params.length == 1 && params[0] != Event.class)) {
            throw new IllegalStateException("@UiHandler method must take no parameters or one Event: " + m);
        }
        makeAccessible(m);
        EventOptions opts = EventOptions.of();
        if (ann.preventDefault()) {
            opts.preventDefault();
        }
        if (ann.stopPropagation()) {
            opts.stopPropagation();
        }
        if (ann.debounce() > 0) {
            opts.debounce(ann.debounce());
        }
        if (!ann.delegate().isEmpty()) {
            opts.delegate(ann.delegate());
        }
        if (ann.keys().length > 0) {
            opts.keys(ann.keys());
        }
        EventHandler handler = e -> {
            try {
                if (params.length == 0) {
                    m.invoke(controller);
                } else {
                    m.invoke(controller, e);
                }
            } catch (InvocationTargetException ite) {
                throw sneaky(ite.getCause());
            } catch (IllegalAccessException iae) {
                throw new IllegalStateException(iae);
            }
        };
        for (String id : ann.value()) {
            window.byId(id).on(ann.event(), opts, handler);
        }
    }

    private static void registerCallable(Window window, Object controller, Method m, JsCallable ann) {
        makeAccessible(m);
        String name = ann.value().isEmpty() ? m.getName() : ann.value();
        Type[] types = m.getGenericParameterTypes();
        Class<?>[] raw = m.getParameterTypes();
        window.exposeForPage(name, args -> {
            Object[] converted = new Object[types.length];
            int next = 0;
            for (int i = 0; i < types.length; i++) {
                if (raw[i] == Window.class) {
                    converted[i] = window;
                } else {
                    Object arg = next < args.size() ? args.get(next) : null;
                    next++;
                    try {
                        converted[i] = JsonMapper.fromJson(arg, types[i]);
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Argument " + (i + 1) + " of '" + name + "': " + e.getMessage(), e);
                    }
                }
            }
            if (ann.background()) {
                return Hanji.async(() -> invoke(m, controller, converted));
            }
            return invoke(m, controller, converted);
        });
    }

    private static Object invoke(Method m, Object target, Object[] args) throws Exception {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw sneaky(cause);
        }
    }

    private static Method findInitialize(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals("initialize") || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0 || (p.length == 1 && p[0] == Window.class)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static void makeAccessible(java.lang.reflect.AccessibleObject o) {
        if (!o.trySetAccessible()) {
            throw new IllegalStateException("Cannot access " + o + ". If you use the module system, add "
                    + "'opens <your.package> to io.hanji;' to module-info.java");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
