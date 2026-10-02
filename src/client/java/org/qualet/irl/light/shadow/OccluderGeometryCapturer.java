package org.qualet.irl.light.shadow;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Quaternionf;

import java.util.List;

/**
 * Captures the REAL model geometry of a shadow occluder into a flat CPU triangle
 * buffer, so {@link ShadowRenderer} can rasterize a faithful silhouette into its
 * depth maps — the 1.21.11 equivalent of the {@code dispatcher.render(...,
 * Immediate, ...)} path used on the 1.20.4 / 1.21.1 redactor lines.
 *
 * <p><b>Why this exists.</b> 1.21.11 (post 1.21.5 RenderPipeline + 1.21.9
 * {@code EntityRenderState}) no longer rasterizes entities through an immediate
 * {@code VertexConsumerProvider}; entity renderers instead <i>submit deferred
 * commands</i> to a {@link SubmitNodeCollector} that MC later executes
 * against its {@code GpuDevice}. There is no reachable immediate-to-FBO path, so
 * the earlier port fell back to an AABB box occluder. But the queue is an
 * <i>interface</i>, and MC's executor simply does {@code model.setAngles(state);
 * model.render(matrices, vertexConsumer, ...)}. So a capturing queue can run the
 * real entity render and redirect its geometry into a position-recording
 * {@link VertexConsumer}, reconstructing the true model silhouette — no GL is
 * issued here (the consumer just records floats); {@link ShadowRenderer} draws
 * the captured triangles itself.</p>
 *
 * <p><b>Coordinate space.</b> The entity is rendered at its lerped world position
 * MINUS the pass's light-relative anchor {@code A = round(lightPos)}
 * ({@link ShadowRenderer#currentOriginX()}), so captured entity vertices come out in
 * anchor-relative {@code world - A} space — matching the anchor-relative light view
 * the depth bake uses (eye {@code = L - A}), the same {@code A} the core block /
 * cutout arms subtract. The subtraction is done in DOUBLE before {@code
 * EntityRenderDispatcher.submit}'s matrix narrows to float, keeping sub-block precision
 * at large world coordinates. {@code ModelPart}/{@code quad} hand the consumer
 * positions already transformed by the matrix, so the consumer applies no matrix of
 * its own. Cutout block capture lives in core {@code ShadowRenderer}.</p>
 *
 * <p>Single-threaded (render thread); one shared {@link Capture} + {@link CaptureQueue}.</p>
 */
public final class OccluderGeometryCapturer
{
    private OccluderGeometryCapturer()
    {}

    private static final Capture CAPTURE = new Capture();
    private static final CaptureQueue QUEUE = new CaptureQueue(CAPTURE);
    /** Reused, so a held armor-stand / mob silhouette pose is deterministic. */
    private static final CameraRenderState CAMERA_STATE = new CameraRenderState();
    /** Entity ids whose render threw hard inside MC's entity pipeline (which wraps
     *  the failure in a CrashReport + CrashException). Skipped on later bakes so a
     *  consistently-broken (e.g. buggy modded) entity is not re-attempted — and its
     *  crash report rebuilt — every frame. Per-entity granularity; a healthy entity
     *  is never skipped. (Id reuse after despawn could skip a new entity sharing the
     *  id; benign — at worst one missing shadow, vs. per-frame crash-report spam.) */
    private static final IntOpenHashSet failedEntities = new IntOpenHashSet();

    private static final float[] EMPTY = new float[0];

    /**
     * Capture one entity's model as world-space POSITION triangles (3 floats per
     * vertex, 6 vertices per quad). Runs MC's real entity render through the
     * capturing queue. Returns an empty array on any failure (degrade to no
     * shadow for that entity — never crash the bake).
     */
    public static float[] captureEntityTris(Entity entity, float tickDelta)
    {
        if (entity == null || failedEntities.contains(entity.getId()))
        {
            return EMPTY;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null)
        {
            return EMPTY;
        }
        EntityRenderDispatcher mgr = mc.getEntityRenderDispatcher();
        if (mgr == null)
        {
            return EMPTY;
        }

        try
        {
            double wx = Mth.lerp(tickDelta, entity.xOld, entity.getX());
            double wy = Mth.lerp(tickDelta, entity.yOld, entity.getY());
            double wz = Mth.lerp(tickDelta, entity.zOld, entity.getZ());

            EntityRenderState state = mgr.extractEntity(entity, tickDelta);
            if (state == null)
            {
                return EMPTY;
            }

            // Orientation/pos for any camera-aligned sub-render; bodies of living
            // entities pose from the render state itself. Default (identity) is
            // safe when the camera is not yet configured.
            Camera cam = mgr.camera;
            if (cam != null)
            {
                CAMERA_STATE.pos = cam.position();
                CAMERA_STATE.orientation.set(cam.rotation());
                CAMERA_STATE.initialized = true;
            }

            CAPTURE.reset();
            // Subtract the pass's light-relative anchor A = round(lightPos) (worldPos
            // - A) in DOUBLE before EntityRenderDispatcher.submit's PoseStack.translate
            // narrows to float, so captured vertices come out in anchor-relative
            // (world - A) space — matching the anchor-relative view eye (L - A) and the
            // same A the core block / cutout arms subtract. Without it the ~1e5
            // absolute coords fall outside the light frustum and the entity casts no
            // shadow. The caster (RedactorEntityCasterSource) records this A alongside
            // the cached geometry so a later light in a different cell can re-base it.
            double ox = ShadowRenderer.currentOriginX();
            double oy = ShadowRenderer.currentOriginY();
            double oz = ShadowRenderer.currentOriginZ();
            mgr.submit(state, CAMERA_STATE, wx - ox, wy - oy, wz - oz, new PoseStack(), QUEUE);
            return CAPTURE.toTris(false);
        }
        catch (Throwable t)
        {
            // A single bad entity must not abort the bake. MC wraps a render
            // failure in a CrashReport + CrashException, so skip this entity on
            // later bakes instead of rebuilding that report every frame.
            failedEntities.add(entity.getId());
            return EMPTY;
        }
    }

    // --- Position(+UV) recording VertexConsumer -------------------------------

    /**
     * Records committed vertices as (x, y, z, u, v) and triangulates the implied
     * QUADS (every 4 vertices -> 2 triangles). Positions arrive already
     * transformed (see {@link ModelPart.Cuboid#renderCuboid} and the default
     * {@link VertexConsumer#quad}); only POSITION (+ UV for cutout) is kept, every
     * other element is dropped. A vertex is committed when the next {@link
     * #addVertex(float, float, float)} starts or {@link #toTris} flushes the last.
     */
    private static final class Capture implements VertexConsumer
    {
        private final FloatArrayList verts = new FloatArrayList(2048);
        private float cx, cy, cz, cu, cv;
        private boolean pending;

        void reset()
        {
            verts.clear();
            pending = false;
        }

        private void commit()
        {
            if (pending)
            {
                verts.add(cx);
                verts.add(cy);
                verts.add(cz);
                verts.add(cu);
                verts.add(cv);
                pending = false;
            }
        }

        /** Flush the last pending vertex and triangulate. {@code withUv} keeps the
         *  UV pair (cutout, stride 5) else POSITION only (entities, stride 3). */
        float[] toTris(boolean withUv)
        {
            commit();
            int n = verts.size() / 5;       // committed vertices
            int quads = n / 4;              // 4 verts per quad (model + block + item)
            if (quads == 0)
            {
                return EMPTY;
            }
            int per = withUv ? 5 : 3;
            float[] out = new float[quads * 6 * per];
            float[] v = verts.elements();
            int w = 0;
            for (int q = 0; q < quads; q++)
            {
                int b = q * 4 * 5; // base float index of this quad's first vertex
                // triangle (0,1,2) then (0,2,3)
                w = put(out, w, v, b, 0, withUv);
                w = put(out, w, v, b, 1, withUv);
                w = put(out, w, v, b, 2, withUv);
                w = put(out, w, v, b, 0, withUv);
                w = put(out, w, v, b, 2, withUv);
                w = put(out, w, v, b, 3, withUv);
            }
            return out;
        }

        private static int put(float[] out, int w, float[] v, int quadBase, int corner, boolean withUv)
        {
            int s = quadBase + corner * 5;
            out[w++] = v[s];
            out[w++] = v[s + 1];
            out[w++] = v[s + 2];
            if (withUv)
            {
                out[w++] = v[s + 3];
                out[w++] = v[s + 4];
            }
            return w;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z)
        {
            commit();
            cx = x; cy = y; cz = z; cu = 0f; cv = 0f;
            pending = true;
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v)
        {
            cu = u; cv = v;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha)
        {
            return this;
        }

        @Override
        public VertexConsumer setColor(int argb)
        {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z)
        {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width)
        {
            return this;
        }
    }

    // --- Capturing render-command queue ---------------------------------------

    /** A submit-node collector that renders geometry immediately into Capture. */
    private static final class CaptureQueue implements SubmitNodeCollector
    {
        private final Capture capture;

        CaptureQueue(Capture capture)
        {
            this.capture = capture;
        }

        @Override
        public OrderedSubmitNodeCollector order(int order)
        {
            return this; // no batching: we render straight into the capture
        }

        @Override
        public <S> void submitModel(Model<? super S> model, S state, PoseStack matrices, RenderType renderLayer,
                                    int light, int overlay, int tintedColor, TextureAtlasSprite sprite, int outlineColor,
                                    ModelFeatureRenderer.CrumblingOverlay crumblingOverlay)
        {
            if (model == null)
            {
                return;
            }
            try
            {
                model.setupAnim(state);
                model.renderToBuffer(matrices, capture, light, overlay, tintedColor);
            }
            catch (Throwable ignored)
            {
                // skip one bad model; the silhouette degrades, the bake survives
            }
        }

        @Override
        public void submitItem(PoseStack matrices, ItemDisplayContext displayContext, int light, int overlay,
                               int outlineColor, int[] tintLayers, List<BakedQuad> quads,
                               ItemStackRenderState.FoilType foilType)
        {
            if (quads == null || quads.isEmpty())
            {
                return;
            }
            try
            {
                PoseStack.Pose pose = matrices.last();
                for (int qi = 0, n = quads.size(); qi < n; qi++)
                {
                    BakedQuad q = quads.get(qi);
                    if (q != null)
                    {
                        captureQuad(pose, q);
                    }
                }
            }
            catch (Throwable ignored)
            {
            }
        }

        // --- no-op submits (not part of an occluder silhouette) ---------------

        @Override
        public void submitShadow(PoseStack matrices, float shadowRadius, List<EntityRenderState.ShadowPiece> shadowPieces)
        {
        }

        @Override
        public void submitNameTag(PoseStack matrices, Vec3 nameLabelPos, int y, Component label,
                                  boolean seeThrough, int light, CameraRenderState cameraState)
        {
        }

        @Override
        public void submitText(PoseStack matrices, float x, float y, FormattedCharSequence text, boolean dropShadow,
                               Font.DisplayMode layerType, int light, int color, int backgroundColor, int outlineColor)
        {
        }

        @Override
        public void submitFlame(PoseStack matrices, EntityRenderState renderState, Quaternionf rotation)
        {
        }

        @Override
        public void submitLeash(PoseStack matrices, EntityRenderState.LeashState leashData)
        {
        }

        @Override
        public void submitMovingBlock(PoseStack matrices, MovingBlockRenderState state, int outlineColor)
        {
        }

        @Override
        public void submitBlockModel(PoseStack matrices, RenderType renderType,
                                     List<BlockStateModelPart> parts, int[] tintLayers,
                                     int light, int overlay, int outlineColor)
        {
            captureParts(matrices.last(), parts);
        }

        @Override
        public void submitBreakingBlockModel(PoseStack matrices, List<BlockStateModelPart> parts, int progress)
        {
            captureParts(matrices.last(), parts);
        }

        @Override
        public void submitShapeOutline(PoseStack matrices, VoxelShape shape, RenderType renderType,
                                       int color, float width, boolean afterTerrain)
        {
        }

        @Override
        public void submitCustomGeometry(PoseStack matrices, RenderType renderType,
                                         SubmitNodeCollector.CustomGeometryRenderer customRenderer)
        {
            try
            {
                customRenderer.render(matrices.last(), capture);
            }
            catch (Throwable ignored)
            {
            }
        }

        @Override
        public void submitQuadParticleGroup(QuadParticleRenderState particles)
        {
        }

        @Override
        public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group,
                                          CameraRenderState camera, boolean onTop)
        {
        }

        private void captureParts(PoseStack.Pose pose, List<BlockStateModelPart> parts)
        {
            if (parts == null)
            {
                return;
            }
            for (BlockStateModelPart part : parts)
            {
                for (Direction direction : Direction.values())
                {
                    for (BakedQuad quad : part.getQuads(direction))
                    {
                        captureQuad(pose, quad);
                    }
                }
                for (BakedQuad quad : part.getQuads(null))
                {
                    captureQuad(pose, quad);
                }
            }
        }

        private void captureQuad(PoseStack.Pose pose, BakedQuad quad)
        {
            for (int i = 0; i < 4; i++)
            {
                capture.addVertex(pose, quad.position(i));
            }
        }
    }
}
