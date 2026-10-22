package io.hanji.json;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Path;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Converts between Java objects and the plain JSON model ({@code Map}/{@code List}/scalars).
 * Supports primitives, strings, enums, collections, maps, arrays, records and simple POJOs.
 */
public final class JsonMapper {

    private static final int MAX_DEPTH = 64;

    private JsonMapper() {
    }

    // ------------------------------------------------------------------ Java -> JSON model

    public static Object toPlain(Object value) {
        return toPlain(value, 0);
    }

    private static Object toPlain(Object v, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("Object graph too deep (cyclic reference?)");
        }
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
            return v;
        }
        if (v instanceof Character || v instanceof UUID || v instanceof URI || v instanceof Path
                || v instanceof TemporalAccessor) {
            return v.toString();
        }
        if (v instanceof Date) {
            return ((Date) v).toInstant().toString();
        }
        if (v instanceof Enum<?>) {
            return ((Enum<?>) v).name();
        }
        if (v instanceof Optional<?>) {
            return toPlain(((Optional<?>) v).orElse(null), depth + 1);
        }
        if (v instanceof io.hanji.HasElement) {
            return ((io.hanji.HasElement) v).element().getId();
        }
        if (v instanceof Map<?, ?>) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                out.put(String.valueOf(e.getKey()), toPlain(e.getValue(), depth + 1));
            }
            return out;
        }
        if (v instanceof Iterable<?>) {
            List<Object> out = new ArrayList<>();
            for (Object item : (Iterable<?>) v) {
                out.add(toPlain(item, depth + 1));
            }
            return out;
        }
        Class<?> c = v.getClass();
        if (c.isArray()) {
            int n = Array.getLength(v);
            List<Object> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                out.add(toPlain(Array.get(v, i), depth + 1));
            }
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (c.isRecord()) {
                for (RecordComponent rc : c.getRecordComponents()) {
                    Method accessor = rc.getAccessor();
                    accessor.trySetAccessible();
                    out.put(rc.getName(), toPlain(accessor.invoke(v), depth + 1));
                }
                return out;
            }
            Method[] methods = c.getMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method m : methods) {
                if (Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0
                        || m.getDeclaringClass() == Object.class || m.getReturnType() == void.class) {
                    continue;
                }
                String name = m.getName();
                String prop = null;
                if (name.startsWith("get") && name.length() > 3) {
                    prop = name.substring(3);
                } else if (name.startsWith("is") && name.length() > 2
                        && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                    prop = name.substring(2);
                }
                if (prop != null) {
                    m.trySetAccessible();
                    out.put(decapitalize(prop), toPlain(m.invoke(v), depth + 1));
                }
            }
            for (Field f : c.getFields()) {
                if (!Modifier.isStatic(f.getModifiers())) {
                    out.put(f.getName(), toPlain(f.get(v), depth + 1));
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot convert " + c.getName() + " to JSON: " + e, e);
        }
        return out;
    }

    private static String decapitalize(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) {
            return s;
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------ JSON model -> Java

    @SuppressWarnings("unchecked")
    public static <T> T fromJson(Object json, Class<T> type) {
        return (T) fromJson(json, (Type) type);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object fromJson(Object json, Type type) {
        Class<?> raw = rawType(type);
        if (raw == Object.class) {
            return json;
        }
        if (raw == Optional.class) {
            return Optional.ofNullable(fromJson(json, typeArgument(type, 0)));
        }
        if (json == null) {
            return raw.isPrimitive() ? primitiveDefault(raw) : null;
        }
        if (raw == String.class) {
            return json instanceof String ? json
                    : (json instanceof Map || json instanceof List) ? Json.stringify(json) : String.valueOf(json);
        }
        if (raw == int.class || raw == Integer.class) {
            return number(json).intValue();
        }
        if (raw == long.class || raw == Long.class) {
            return number(json).longValue();
        }
        if (raw == double.class || raw == Double.class) {
            return number(json).doubleValue();
        }
        if (raw == float.class || raw == Float.class) {
            return number(json).floatValue();
        }
        if (raw == short.class || raw == Short.class) {
            return number(json).shortValue();
        }
        if (raw == byte.class || raw == Byte.class) {
            return number(json).byteValue();
        }
        if (raw == BigDecimal.class) {
            return new BigDecimal(String.valueOf(json));
        }
        if (raw == BigInteger.class) {
            return new BigDecimal(String.valueOf(json)).toBigInteger();
        }
        if (raw == Number.class) {
            return number(json);
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return json instanceof Boolean ? json : Boolean.parseBoolean(String.valueOf(json));
        }
        if (raw == char.class || raw == Character.class) {
            String s = String.valueOf(json);
            return s.isEmpty() ? '\0' : s.charAt(0);
        }
        if (raw.isEnum()) {
            return Enum.valueOf((Class) raw, String.valueOf(json));
        }
        if (raw.isArray()) {
            List<?> list = asList(json);
            Type componentType = type instanceof GenericArrayType
                    ? ((GenericArrayType) type).getGenericComponentType() : raw.getComponentType();
            Object array = Array.newInstance(raw.getComponentType(), list.size());
            for (int i = 0; i < list.size(); i++) {
                Array.set(array, i, fromJson(list.get(i), componentType));
            }
            return array;
        }
        if (Collection.class.isAssignableFrom(raw)) {
            Type elementType = typeArgument(type, 0);
            Collection<Object> out = Set.class.isAssignableFrom(raw) ? new LinkedHashSet<>() : new ArrayList<>();
            for (Object item : asList(json)) {
                out.add(fromJson(item, elementType));
            }
            return out;
        }
        if (Map.class.isAssignableFrom(raw)) {
            Type valueType = typeArgument(type, 1);
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : asMap(json).entrySet()) {
                out.put(e.getKey(), fromJson(e.getValue(), valueType));
            }
            return out;
        }
        if (raw.isInstance(json)) {
            return json;
        }
        try {
            if (raw.isRecord()) {
                Map<String, Object> map = asMap(json);
                RecordComponent[] components = raw.getRecordComponents();
                Class<?>[] types = new Class<?>[components.length];
                Object[] args = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    types[i] = components[i].getType();
                    args[i] = fromJson(map.get(components[i].getName()), components[i].getGenericType());
                }
                Constructor<?> ctor = raw.getDeclaredConstructor(types);
                ctor.trySetAccessible();
                return ctor.newInstance(args);
            }
            Map<String, Object> map = asMap(json);
            Constructor<?> ctor = raw.getDeclaredConstructor();
            ctor.trySetAccessible();
            Object target = ctor.newInstance();
            for (Class<?> c = raw; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    int mod = f.getModifiers();
                    if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || !map.containsKey(f.getName())) {
                        continue;
                    }
                    f.trySetAccessible();
                    f.set(target, fromJson(map.get(f.getName()), f.getGenericType()));
                }
            }
            return target;
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot convert JSON to " + raw.getName() + ": " + e, e);
        }
    }

    private static Number number(Object json) {
        if (json instanceof Number) {
            return (Number) json;
        }
        if (json instanceof Boolean) {
            return ((Boolean) json) ? 1 : 0;
        }
        String s = String.valueOf(json).trim();
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Not a number: '" + s + "'");
        }
    }

    private static List<?> asList(Object json) {
        if (json instanceof List<?>) {
            return (List<?>) json;
        }
        throw new IllegalArgumentException("Expected JSON array but got " + describe(json));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object json) {
        if (json instanceof Map<?, ?>) {
            return (Map<String, Object>) json;
        }
        throw new IllegalArgumentException("Expected JSON object but got " + describe(json));
    }

    private static String describe(Object json) {
        return json == null ? "null" : json.getClass().getSimpleName() + " " + json;
    }

    private static Object primitiveDefault(Class<?> c) {
        if (c == boolean.class) {
            return false;
        }
        if (c == char.class) {
            return '\0';
        }
        if (c == long.class) {
            return 0L;
        }
        if (c == double.class) {
            return 0d;
        }
        if (c == float.class) {
            return 0f;
        }
        if (c == short.class) {
            return (short) 0;
        }
        if (c == byte.class) {
            return (byte) 0;
        }
        return 0;
    }

    static Class<?> rawType(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return rawType(((ParameterizedType) type).getRawType());
        }
        if (type instanceof GenericArrayType) {
            return Array.newInstance(rawType(((GenericArrayType) type).getGenericComponentType()), 0).getClass();
        }
        if (type instanceof WildcardType) {
            return rawType(((WildcardType) type).getUpperBounds()[0]);
        }
        if (type instanceof TypeVariable<?>) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return bounds.length == 0 ? Object.class : rawType(bounds[0]);
        }
        return Object.class;
    }

    private static Type typeArgument(Type type, int index) {
        if (type instanceof ParameterizedType) {
            Type[] args = ((ParameterizedType) type).getActualTypeArguments();
            if (index < args.length) {
                return args[index];
            }
        }
        return Object.class;
    }
}
