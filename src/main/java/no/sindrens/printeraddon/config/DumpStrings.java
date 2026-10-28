package no.sindrens.printeraddon.config;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.IConfigBase;
import fi.dy.masa.malilib.config.options.ConfigBase;

import java.nio.file.Files;
import java.nio.file.Path;

/** Build tool (gradle dumpConfigStrings): every setting's English name and description, as a lang file. */
public final class DumpStrings {
    private DumpStrings() {
    }

    private static String field(ConfigBase<?> base, String name) throws Exception {
        java.lang.reflect.Field f = ConfigBase.class.getDeclaredField(name);
        f.setAccessible(true);
        Object v = f.get(base);
        return v == null ? "" : v.toString();
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        JsonObject out = new JsonObject();
        java.util.List<IConfigBase> all = new java.util.ArrayList<>(Configs.all());
        all.addAll(Hotkeys.HOTKEY_LIST);
        for (IConfigBase c : all) {
            if (c instanceof ConfigBase<?> base) {
                out.addProperty(Translations.nameKey(c), field(base, "prettyName"));
                out.addProperty(Translations.commentKey(c), field(base, "comment"));
            }
        }
        Files.writeString(Path.of(args[0]), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(out));
    }
}
