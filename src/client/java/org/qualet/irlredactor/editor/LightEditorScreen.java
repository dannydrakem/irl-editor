package org.qualet.irlredactor.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.opengl.GL30;
import org.qualet.irlredactor.client.IRLRedactorClient;
import org.qualet.irlredactor.imgui.ImGuiRuntime;

/**
 * Hosts the ImGui light editor. Two ways in:
 * <ul>
 *   <li><b>Normal world</b> — opened as a real {@link Screen} (frees the cursor,
 *       swallows game input; the world keeps rendering behind it).</li>
 *   <li><b>In a replay</b> — there is no host screen (Replay Mod owns
 *       {@code currentScreen}); the panel is shown as a detached overlay toggled by
 *       {@link #editorVisible}.</li>
 * </ul>
 *
 * <p>The ImGui overlay is not drawn from {@link #extractRenderState}. Minecraft's
 * 26.2 GUI pipeline is deferred, so raw GL there would be overwritten. Instead
 * {@code MinecraftClientImGuiMixin} calls {@link #renderActiveOverlay()} from the
 * OpenGL surface immediately before buffer presentation, after the world and GUI
 * have reached the default framebuffer.</p>
 */
public class LightEditorScreen extends Screen
{
    /**
     * Single shared panel backing BOTH entry paths (host screen and detached
     * overlay), so editor state (scene selection, gizmo) is identical regardless of
     * how it was opened.
     */
    private static final LightEditorPanel PANEL = new LightEditorPanel();

    /**
     * Detached-overlay visibility (the replay path). Toggled from a raw GLFW read in
     * {@link IRLRedactorClient}. Read on the render thread, written on the client
     * thread — volatile keeps the flip visible promptly.
     */
    private static volatile boolean editorVisible;

    public LightEditorScreen()
    {
        super(Component.literal("IRLite Light Editor"));
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta)
    {
        // The host screen draws nothing itself (the dim background comes from the
        // framework's renderBackground). The ImGui panel is overlaid post-blit via
        // renderActiveOverlay() — see the class note.
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta)
    {
        // Keep the world behind the editor clear: no blur and no darkening.
    }

    @Override
    public void removed()
    {
        // Persist edits as soon as the editor is closed (not only on disconnect).
        IRLRedactorClient.saveCurrentWorld();
    }

    // ---- detached overlay (replay path) -----------------------------------

    public static boolean isVisible()
    {
        return editorVisible;
    }

    /** True when the panel is being drawn this frame (detached overlay or host
     *  screen) — used by the input-capture mixins to gate when to swallow input. */
    public static boolean isOverlayActive()
    {
        return editorVisible || Minecraft.getInstance().gui.screen() instanceof LightEditorScreen;
    }

    /** Toggles the detached overlay; saves the scene when hiding. */
    public static void toggleVisible()
    {
        setVisible(!editorVisible);
    }

    public static void setVisible(boolean visible)
    {
        if (editorVisible && !visible)
        {
            IRLRedactorClient.saveCurrentWorld();
        }
        editorVisible = visible;
    }

    // ---- render hook ------------------------------------------------------

    /**
     * Render the editor's ImGui panel onto the default framebuffer. Called from
     * {@code MinecraftClientImGuiMixin} after the frame has been blitted to the
     * screen, so ImGui overlays Minecraft's already-flushed GUI. Renders when the
     * detached overlay is visible OR a host {@link LightEditorScreen} is open; a
     * no-op otherwise.
     */
    public static void renderActiveOverlay()
    {
        Minecraft mc = Minecraft.getInstance();
        if (!editorVisible && !(mc.gui.screen() instanceof LightEditorScreen))
        {
            return;
        }
        ImGuiRuntime runtime = ImGuiRuntime.get();
        if (runtime.isDisabled())
        {
            // Backend already known unusable (conflicting imgui-java, e.g. Axiom) —
            // don't leave the cursor/input trapped behind an overlay that can't draw.
            forceClose(mc);
            return;
        }

        // Target framebuffer 0 (the window) — MC's frame is already there.
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        runtime.frame(PANEL::draw);

        // The first frame may have just discovered the backend is unusable.
        if (runtime.isDisabled())
        {
            forceClose(mc);
        }
    }

    /** Force the editor closed on both entry paths when the ImGui backend is
     *  disabled, so the user isn't stuck with a freed cursor / swallowed input
     *  behind an overlay that renders nothing. */
    private static void forceClose(Minecraft mc)
    {
        editorVisible = false;
        if (mc.gui.screen() instanceof LightEditorScreen)
        {
            mc.gui.setScreen(null);
        }
    }
}
