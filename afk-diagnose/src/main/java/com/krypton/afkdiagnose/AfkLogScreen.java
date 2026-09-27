package com.krypton.afkdiagnose;

import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Eigener nicht-pausierender Bildschirm. com.krypton.* ist in Kryptons Screen-Blocklist erlaubt. */
final class AfkLogScreen extends Screen {
    private final DiagnosticLog log;
    private int page;
    private ButtonWidget older, newer;

    AfkLogScreen(DiagnosticLog log) {
        super(Text.literal("AFK LOG – nur lokale Diagnose"));
        this.log = log;
    }

    private int perPage() { return Math.max(1, (height - 125) / 42); }

    @Override
    protected void init() {
        int center = width / 2;
        older = addDrawableChild(ButtonWidget.builder(Text.literal("Aeltere"), button -> page++)
                .dimensions(center - 155, height - 28, 90, 20).build());
        newer = addDrawableChild(ButtonWidget.builder(Text.literal("Neuere"), button -> page--)
                .dimensions(center - 60, height - 28, 90, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Neueste"), button -> page = 0)
                .dimensions(center + 35, height - 28, 75, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Zu"), button -> close())
                .dimensions(center + 115, height - 28, 40, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xE0101420);
        context.drawCenteredTextWithShadow(textRenderer, title, width / 2, 12, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, "10-Minuten-Messung, Radius 16; KEINE Loot-/Produktionsmessung", width / 2, 27, 0xFFCC66);
        context.drawCenteredTextWithShadow(textRenderer, "Datei: krypton_afk_diagnose.txt im Spielordner", width / 2, 40, 0xAABBCD);
        List<String> rows = log.history();
        int pageSize = perPage();
        int pages = Math.max(1, (rows.size() + pageSize - 1) / pageSize);
        page = Math.clamp(page, 0, pages - 1);
        String status = log.diskFailed() ? "Speicherfehler: nur RAM-Protokoll!" : "Protokollierung aktiv";
        context.drawCenteredTextWithShadow(textRenderer, status + " | Seite " + (page + 1) + "/" + pages
                + " | Verworfene Eintraege: " + log.dropped(), width / 2, 53, log.diskFailed() ? 0xFF6666 : 0x99CCAA);
        int from = page * pageSize, to = Math.min(rows.size(), from + pageSize);
        for (int i = from; i < to; i++) {
            int y = 73 + (i - from) * 42;
            var wrapped = textRenderer.wrapLines(Text.literal(rows.get(i)), Math.max(20, width - 32));
            for (int line = 0; line < Math.min(3, wrapped.size()); line++) {
                context.drawTextWithShadow(textRenderer, wrapped.get(line), 16, y + line * 11, 0xDDE5EF);
            }
        }
        older.active = page < pages - 1;
        newer.active = page > 0;
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldPause() { return false; }
}
