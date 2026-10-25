package no.sindrens.printeraddon.gui;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.GuiTextInputBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import net.minecraft.client.Minecraft;
import no.sindrens.printeraddon.build.AutoBuild;
import no.sindrens.printeraddon.build.CostEstimate;
import no.sindrens.printeraddon.shop.ShopText;
import no.sindrens.printeraddon.util.Chat;

/**
 * Asked when auto build starts (with buying on): the most it may spend in the shop on this build, or no limit.
 * Shows what the rest of the build costs (from the shop scan) and the balance, to choose from.
 */
public class SpendingLimitGui extends GuiTextInputBase {
    /** What was written last time (offered again). */
    private static String lastText = "";

    public SpendingLimitGui() {
        super(20, "Spending limit for this build: $ (50000, 50k or 1.5m)", lastText, null);
    }

    @Override
    public void initGui() {
        super.initGui();
        Minecraft mc = Minecraft.getInstance();
        Double estimate = CostEstimate.estimate(mc);
        Double balance = CostEstimate.balance(mc);
        String info = (estimate != null ? String.format(java.util.Locale.ROOT, "The rest of the build costs about $%,.0f", estimate) : "No shop scan yet (J+S): no cost estimate")
                + (balance != null ? String.format(java.util.Locale.ROOT, ", your balance is $%,.0f", balance) : "");
        this.addLabel(this.dialogLeft, this.dialogTop - 14, this.dialogWidth, 10, 0xFFFFFFFF, info);
        ButtonGeneric noLimit = new ButtonGeneric(this.dialogLeft, this.dialogTop + this.dialogHeight + 4, this.dialogWidth, 20,
                "No limit: start");
        this.addButton(noLimit, (button, mouse) -> {
            GuiBase.openGui(null);
            AutoBuild.INSTANCE.startWithLimit(mc, -1);
        });
    }

    @Override
    protected boolean applyValue(String text) {
        Double value = ShopText.parseNumber(text.replace("$", "").replace(" ", "").trim());
        if (value == null || value <= 0) {
            Chat.warn("Write an amount like 50000, 50k or 1.5m, or click 'No limit'.");
            return false;
        }
        lastText = text.trim();
        AutoBuild.INSTANCE.startWithLimit(Minecraft.getInstance(), value);
        return true;
    }
}
