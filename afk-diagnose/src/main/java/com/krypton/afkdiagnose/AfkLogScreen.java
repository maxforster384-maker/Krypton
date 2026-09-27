package com.krypton.afkdiagnose;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

/** Eigener nicht-pausierender Bildschirm. com.krypton.* ist in Kryptons Screen-Blocklist erlaubt. */
final class AfkLogScreen extends Screen {
    private final DiagnosticLog log;
    private int page;
    private ButtonWidget older, newer;
    private LogPresentation.Layout layout;

    AfkLogScreen(DiagnosticLog log) {
        super(Text.literal("AFK-Diagnose"));
        this.log = log;
    }

    @Override
    protected void init() {
        layout = LogPresentation.layout(width, height);
        int x = layout.left(), w = layout.buttonWidth(), y = layout.buttonY();
        newer = addDrawableChild(ButtonWidget.builder(Text.literal("< Neuere"), button -> changePage(-1))
                .dimensions(x, y, w, 20).build());
        older = addDrawableChild(ButtonWidget.builder(Text.literal("Ältere >"), button -> changePage(1))
                .dimensions(x + w + 8, y, w, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Zurück"), button -> close())
                .dimensions(x + 2 * (w + 8), y, w, 20).build());
        older.setTooltip(Tooltip.of(Text.literal("Eine Seite weiter in die Vergangenheit")));
        newer.setTooltip(Tooltip.of(Text.literal("Zur neueren Seite. Auf Seite 1 siehst du bereits die aktuellsten Einträge.")));
        updateNavigation(log.history().size());
    }

    private void changePage(int direction) {
        page = LogPresentation.clampPage(page + direction, log.history().size(), layout.perPage());
        updateNavigation(log.history().size());
    }

    private void updateNavigation(int count) {
        page = LogPresentation.clampPage(page, count, layout.perPage());
        older.active = page < LogPresentation.pages(count, layout.perPage()) - 1;
        newer.active = page > 0;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical == 0) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        changePage(vertical < 0 ? 1 : -1);
        return true;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, 0xE0080E17);
        int x = layout.left(), right = x + layout.width();
        context.fill(x, 8, right, height - 8, 0xF0101927);
        context.fill(x, 8, right, 10, 0xFF58B8DF);
        context.drawTextWithShadow(textRenderer, title, x + 10, 18, 0xECF4FC);
        drawFitted(context, "Nur Beobachtung • alle 10 Minuten • Radius 16", x + 10, 32, layout.width() - 20, 0x9BACBF);
        List<String> rows = log.history();
        updateNavigation(rows.size());
        String status = log.diskFailed() ? "Speicherfehler: nur RAM!" : "Protokoll aktiv";
        status += " • " + rows.size() + " Einträge";
        if (log.dropped() > 0) status += " • " + log.dropped() + " verworfen";
        drawFitted(context, status, x + 10, 47, layout.width() - 20,
                log.diskFailed() || log.dropped() > 0 ? 0xF3C36B : 0x85D6A3);
        int from = page * layout.perPage(), to = Math.min(rows.size(), from + layout.perPage());
        String hovered = null;
        if (rows.isEmpty() && layout.cardHeight() > 0) {
            drawFitted(context, "Noch keine Einträge. Das Protokoll wird geladen.", x + 10,
                    layout.top() + 10, layout.width() - 20, 0x9BACBF);
        }
        for (int i = from; i < to && layout.cardHeight() > 0; i++) {
            int y = layout.top() + (i - from) * (layout.cardHeight() + LogPresentation.CARD_GAP);
            LogPresentation.Entry entry = LogPresentation.present(rows.get(i));
            boolean hover = mouseX >= x && mouseX < right && mouseY >= y && mouseY < y + layout.cardHeight();
            context.fill(x, y, right, y + layout.cardHeight(), hover ? 0xFF203247 : 0xFF172233);
            context.fill(x, y, x + 3, y + layout.cardHeight(), 0xFF000000 | entry.color());
            drawFitted(context, entry.heading(), x + 10, y + 7, layout.width() - 20, entry.color());
            int visibleLines = Math.max(0, (layout.cardHeight() - 26) / 10);
            for (int line = 0; line < Math.min(visibleLines, entry.summary().size()); line++) {
                drawFitted(context, entry.summary().get(line), x + 10, y + 23 + line * 10,
                        layout.width() - 20, 0xCFDAE6);
            }
            if (hover) hovered = entry.fullText();
        }
        drawCenteredFitted(context, "Seite " + (page + 1) + " / "
                + LogPresentation.pages(rows.size(), layout.perPage()) + " • Neueste zuerst", layout.buttonY() - 27, 0xD8E5F1);
        drawCenteredFitted(context, "Details per Maus • keine Loot-Messung", layout.buttonY() - 13, 0x9BACBF);
        super.render(context, mouseX, mouseY, delta);
        if (hovered != null) drawDetails(context, hovered, mouseX, mouseY);
    }

    private void drawFitted(DrawContext context, String text, int x, int y, int available, int color) {
        if (available <= 0) return;
        if (textRenderer.getWidth(text) > available) {
            text = textRenderer.trimToWidth(text, Math.max(0, available - textRenderer.getWidth("…"))) + "…";
        }
        context.drawTextWithShadow(textRenderer, text, x, y, color);
    }

    private void drawCenteredFitted(DrawContext context, String text, int y, int color) {
        int available = Math.max(0, layout.width() - 20);
        text = textRenderer.trimToWidth(text, available);
        context.drawCenteredTextWithShadow(textRenderer, text, width / 2, y, color);
    }

    private void drawDetails(DrawContext context, String raw, int mouseX, int mouseY) {
        List<OrderedText> wrapped = textRenderer.wrapLines(Text.literal(raw.replace(" | ", "\n").replace("; ", "\n")),
                Math.max(40, Math.min(460, width - 48)));
        int maxLines = Math.max(3, (height - 32) / textRenderer.fontHeight);
        List<OrderedText> lines = new ArrayList<>(wrapped.subList(0, Math.min(maxLines, wrapped.size())));
        if (wrapped.size() > maxLines) {
            lines.set(lines.size() - 1, Text.literal("Mehr Details in der Logdatei").asOrderedText());
        }
        context.drawTooltip(lines, mouseX, mouseY);
    }

    @Override
    public boolean shouldPause() { return false; }
}
