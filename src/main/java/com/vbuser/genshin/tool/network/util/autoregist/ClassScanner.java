package com.vbuser.genshin.tool.network.util.autoregist;

import com.google.common.reflect.ClassPath;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;

@SuppressWarnings("all")
public class ClassScanner {
    public static Set<Class<?>> findSubclasses(String packageName, Class<?> parentClass) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            ClassPath classPath = ClassPath.from(cl);
            Set<Class<?>> result = new HashSet<>();
            for (ClassPath.ClassInfo info : classPath.getTopLevelClassesRecursive(packageName)) {
                try {
                    Class<?> clazz = info.load();
                    if (!clazz.isInterface() && !Modifier.isAbstract(clazz.getModifiers())
                            && parentClass.isAssignableFrom(clazz)) {
                        result.add(clazz);
                    }
                } catch (Throwable t) {
                    // System.err.println("Skipping class " + info.getName() + " due to: " + t);
                    // What we need is only skip, but something should be written here.
                }
            }
            return result;
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan package: " + packageName, e);
        }
    }
}
