package no.sindrens.printeraddon.config;

import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBase;
import net.minecraft.locale.Language;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings' names and descriptions in the game's language, from assets/printer_addon/lang (keys
 * printer_addon.config.NAME.name and .comment). Applied when the settings open; English stays where a language has no text.
 */
public final class Translations {
    private static final Map<IConfigBase, String[]> ENGLISH = new HashMap<>();
    private static String appliedFor = "";

    private Translations() {
    }

    public static String nameKey(IConfigBase c) {
        return "printer_addon.config." + c.getName() + ".name";
    }

    public static String commentKey(IConfigBase c) {
        return "printer_addon.config." + c.getName() + ".comment";
    }

    private static List<IConfigBase> configs() {
        List<IConfigBase> all = new java.util.ArrayList<>(Configs.all());
        all.addAll(Hotkeys.HOTKEY_LIST);
        return all;
    }

    /** Sets every setting's name and description for the language the game uses now (once per language). */
    public static void apply() {
        String lang;
        try {
            lang = net.minecraft.client.Minecraft.getInstance().getLanguageManager().getSelected();
        } catch (RuntimeException | LinkageError e) {
            return;
        }
        if (lang.equals(appliedFor)) {
            return;
        }
        appliedFor = lang;
        for (IConfigBase c : configs()) {
            if (!(c instanceof ConfigBase<?> base)) {
                continue;
            }
            String[] english = ENGLISH.computeIfAbsent(c, k -> new String[]{base.getPrettyName(), rawComment(base)});
            String name = nameKey(c);
            String comment = commentKey(c);
            // English (en_us) is the source text itself: only other languages are looked up
            boolean other = !lang.startsWith("en_");
            Language language = Language.getInstance();
            base.setPrettyName(other ? language.getOrDefault(name, english[0]) : english[0]);
            base.setComment(other ? language.getOrDefault(comment, english[1]) : english[1]);
        }
    }

    private static String rawComment(ConfigBase<?> base) {
        try {
            java.lang.reflect.Field f = ConfigBase.class.getDeclaredField("comment");
            f.setAccessible(true);
            Object v = f.get(base);
            return v == null ? "" : v.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return base.getComment();
        }
    }
}
