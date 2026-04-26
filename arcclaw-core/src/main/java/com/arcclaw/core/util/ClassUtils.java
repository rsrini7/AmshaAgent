package com.arcclaw.core.util;

/**
 * Utilities for class name manipulation, specifically handling CDI proxies.
 */
public class ClassUtils {

    /**
     * Extracts a clean simple name from a class, stripping CDI proxy suffixes.
     * 
     * @param clazz the class to get the name for
     * @return the simple name without the proxy suffix
     */
    public static String getCleanName(Class<?> clazz) {
        if (clazz == null) return "null";
        String name = clazz.getSimpleName();
        int proxyIdx = name.indexOf("_ClientProxy");
        if (proxyIdx != -1) {
            return name.substring(0, proxyIdx);
        }
        int subclassIdx = name.indexOf("_Subclass");
        if (subclassIdx != -1) {
            return name.substring(0, subclassIdx);
        }
        return name;
    }

    /**
     * Extracts a clean simple name from an object instance.
     * 
     * @param obj the object to get the name for
     * @return the simple name without the proxy suffix
     */
    public static String getCleanName(Object obj) {
        if (obj == null) return "null";
        return getCleanName(obj.getClass());
    }
}
