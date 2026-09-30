/* SPDX-License-Identifier: LGPL-3.0-or-later */
package baritone.launch.privacy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Resolves editable sign strings as if Baritone translation resources were absent.
 * Minecraft 26.3 uses unobfuscated names. Reflection keeps this small release overlay
 * independent of Minecraft's build dependencies; the normal Gradle build also works.
 * This never changes the language table, bindings, packets, or original components.
 */
public final class SignTextPrivacy {
    private SignTextPrivacy() {}

    public static String getString(Object component) {
        try {
            return ApiHolder.API.render(component);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Minecraft 26.3 sign text API changed", e);
        }
    }

    private static final class ApiHolder {
        static final Api API = load();

        private static Api load() {
            try {
                return new Api();
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    private static final class Api {
        private final Class<?> component = Class.forName("net.minecraft.network.chat.Component");
        private final Class<?> translation = Class.forName("net.minecraft.network.chat.contents.TranslatableContents");
        private final Method getContents = component.getMethod("getContents");
        private final Method getSiblings = component.getMethod("getSiblings");
        private final Method plainCopy = component.getMethod("plainCopy");
        private final Method getString = component.getMethod("getString");
        private final Method getKey = translation.getMethod("getKey");
        private final Method getFallback = translation.getMethod("getFallback");
        private final Method getArgs = translation.getMethod("getArgs");
        private final Constructor<?> newTranslation = translation.getConstructor(String.class, String.class, Object[].class);
        private final Method create = Class.forName("net.minecraft.network.chat.MutableComponent")
                .getMethod("create", Class.forName("net.minecraft.network.chat.ComponentContents"));

        Api() throws ReflectiveOperationException {}

        String render(Object text) throws ReflectiveOperationException {
            Object contents = getContents.invoke(text);
            String own;
            if (translation.isInstance(contents)) {
                String key = (String) getKey.invoke(contents);
                String fallback = (String) getFallback.invoke(contents);
                Object[] originalArgs = (Object[]) getArgs.invoke(contents);
                Object[] args = originalArgs.clone();
                for (int i = 0; i < args.length; i++) {
                    if (component.isInstance(args[i])) args[i] = render(args[i]);
                }
                if (key.startsWith("baritone.")) {
                    own = formatFallback(fallback == null ? key : fallback, args);
                } else {
                    // A fresh translation avoids reusing a cached result whose arguments
                    // previously resolved Baritone strings during ordinary rendering.
                    Object fresh = newTranslation.newInstance(key, fallback, args);
                    own = (String) getString.invoke(create.invoke(null, fresh));
                }
            } else {
                // plainCopy excludes siblings; keybinds still use Minecraft's resolver.
                own = (String) getString.invoke(plainCopy.invoke(text));
            }
            StringBuilder result = new StringBuilder(own);
            for (Object sibling : (List<?>) getSiblings.invoke(text)) result.append(render(sibling));
            return result.toString();
        }
    }

    /** Minecraft translation placeholders: %s, %N$s and %%; invalid templates remain literal. */
    static String formatFallback(String template, Object[] args) {
        StringBuilder out = new StringBuilder();
        int sequential = 0;
        for (int i = 0; i < template.length();) {
            char ch = template.charAt(i++);
            if (ch != '%') {
                out.append(ch);
                continue;
            }
            if (i < template.length() && template.charAt(i) == '%') {
                out.append('%');
                i++;
                continue;
            }
            int start = i;
            while (i < template.length() && template.charAt(i) >= '0' && template.charAt(i) <= '9') i++;
            int index;
            if (i > start) {
                if (i >= template.length() || template.charAt(i++) != '$') return template;
                try {
                    index = Integer.parseInt(template.substring(start, i - 1)) - 1;
                } catch (NumberFormatException e) {
                    return template;
                }
            } else {
                index = sequential++;
            }
            if (i >= template.length() || template.charAt(i++) != 's' || index < 0 || index >= args.length) return template;
            out.append(String.valueOf(args[index]));
        }
        return out.toString();
    }
}
