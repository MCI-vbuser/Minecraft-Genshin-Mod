package com.vbuser.database.gui;

import com.vbuser.database.sql.SqlApi;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SqlConsoleGui extends GuiScreen {
    private GuiTextField inputField;
    private final List<LineEntry> lines = new ArrayList<>();
    private int scrollOffset = 0;
    private int maxLinesDisplayed;
    private static final int LINE_HEIGHT = 12;
    private static final int MARGIN = 10;

    private final Queue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();

    private static class LineEntry {
        String text;
        int color;
        LineEntry(String text, int color) {
            this.text = text;
            this.color = color;
        }
    }

    public SqlConsoleGui() {
        addLine("Hello SqlBoy", 0x55FF55);
        addLine("", 0xFFFFFF);
    }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        int inputY = this.height - 25;
        inputField = new GuiTextField(0, this.fontRenderer, MARGIN, inputY, this.width - 2 * MARGIN, 20);
        inputField.setFocused(true);
        inputField.setTextColor(0xFFFFFF);
        inputField.setDisabledTextColour(0xFFFFFF);
        inputField.setEnableBackgroundDrawing(true);
        inputField.setMaxStringLength(1000);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        inputField.updateCursorCounter();
        while (!pendingTasks.isEmpty()) {
            Runnable task = pendingTasks.poll();
            if (task != null) task.run();
        }
        int totalLines = lines.size();
        maxLinesDisplayed = (height - 40) / LINE_HEIGHT;
        int maxScroll = Math.max(0, totalLines - maxLinesDisplayed);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;
        if (scrollOffset < 0) scrollOffset = 0;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }

        if (keyCode == Keyboard.KEY_RETURN) {
            String rawInput = inputField.getText().trim();
            if (!rawInput.isEmpty()) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).text.isEmpty()) {
                    addLine("", 0xFFFFFF);
                }
                addLine("> " + rawInput, 0xFFFFFF);
                executeSql(rawInput);
                inputField.setText("");
            }
            return;
        }

        if (keyCode == Keyboard.KEY_UP) {
            scrollOffset = Math.max(0, scrollOffset - 1);
            return;
        }

        if (keyCode == Keyboard.KEY_DOWN) {
            scrollOffset = Math.min(lines.size() - maxLinesDisplayed, scrollOffset + 1);
            return;
        }

        if (GuiScreen.isKeyComboCtrlV(keyCode)) {
            String clip = GuiScreen.getClipboardString();
            if (!clip.isEmpty()) {
                String normalized = clip
                        .replace("\r\n", " ")
                        .replace('\r', ' ')
                        .replace('\n', ' ')
                        .replace('\t', ' ');
                inputField.writeText(normalized);
            }
            return;
        }

        inputField.textboxKeyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        inputField.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            int delta = (wheel > 0) ? -1 : 1;
            scrollOffset += delta;
            int maxScroll = Math.max(0, lines.size() - maxLinesDisplayed);
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        }
    }

    private void executeSql(String sql) {
        SqlApi.execute(sql, Collections.emptyList(), result -> pendingTasks.add(() -> {
            if (result.isSuccess()) {
                if (result.getRows() != null) {
                    addLine(TextFormatting.GREEN + "Successfully got " + result.getRows().size() + " line(s)", 0x55FF55);
                } else {
                    addLine(TextFormatting.GREEN + "Successfully operated " + result.getUpdateCount(), 0x55FF55);
                }
            } else {
                addLine(TextFormatting.RED + "Error: " + result.getErrorMessage(), 0xFF5555);
            }
            addLine("", 0xFFFFFF);
            inputField.setFocused(true);
        }));
    }

    private void addLine(String text, int color) {
        lines.add(new LineEntry(text, color));
        int total = lines.size();
        scrollOffset = Math.max(0, total - maxLinesDisplayed);
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        GlStateManager.enableBlend();
        drawRect(0, 0, width, height, 0x88000000);

        int endY = height - 40;
        int lineIndex = scrollOffset;
        int y = MARGIN;
        while (lineIndex < lines.size() && y + LINE_HEIGHT <= endY) {
            LineEntry entry = lines.get(lineIndex);
            fontRenderer.drawString(entry.text, MARGIN, y, entry.color);
            y += LINE_HEIGHT;
            lineIndex++;
        }

        inputField.drawTextBox();
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}