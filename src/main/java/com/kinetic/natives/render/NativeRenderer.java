package com.kinetic.natives.render;

import com.kinetic.accessor.VertexBufferAccessor;
import com.kinetic.buffer.NativeBuffer;
import com.kinetic.buffer.annotation.Aligned;
import com.kinetic.buffer.annotation.Borrowed;
import com.kinetic.client.compat.IrisCompat;
import com.kinetic.config.ClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Reference;

public class NativeRenderer {
    public static void render(VertexConsumer vertexConsumer, PoseStack.Pose pose,
                              NativeModelState modelState, int vertexCount,
                              int light, int overlay, int color, RenderContextType contextType) {
        var vb = probeVertexConsumer(vertexConsumer, vertexCount);
        var isUnknownType = vb.type == VertexFormatType.FALLBACK;

        var matBuffer = getMatBuffer(pose);
        var lightAndOverlay = packLightAndOverlay(light, overlay);
        var flags = packFlags(vb.type, contextType);

        Object bufferObj;
        int bufferFlags;
        if (!isUnknownType && vb.region != null) {
            bufferObj = vb.region.nio();
            bufferFlags = vb.region.size();
        } else {
            bufferObj = FallbackVertexWriter.getVertexData(vertexCount);
            bufferFlags = 0;
        }

        final boolean result;
        try {
            result = nRender(bufferObj, bufferFlags, matBuffer.ptr(), modelState.get(),
                    lightAndOverlay, packColor(color), flags, IrisCompat.getEntityId());
        } finally {
            Reference.reachabilityFence(vertexConsumer);
            Reference.reachabilityFence(bufferObj);
            Reference.reachabilityFence(vb.region);
            Reference.reachabilityFence(matBuffer);
            Reference.reachabilityFence(modelState);
        }

        if (result) {
            if (isUnknownType || vb.region == null) {
                FallbackVertexWriter.write(vertexConsumer, vertexCount, overlay);
            } else if (vb.accessor != null) {
                vb.accessor.kinetic$advance(vertexCount);
            }
        }
    }

    @SuppressWarnings("resource")
    private static VertexBuffer probeVertexConsumer(VertexConsumer vertexConsumer, int vertexCount) {
        if (vertexConsumer instanceof VertexBufferAccessor accessor && accessor.kinetic$ok()) {
            if (!ClientConfig.USE_COMPATIBILITY_RENDERER.get()) {
                var format = accessor.kinetic$vertexFormat();
                if (format == DefaultVertexFormat.NEW_ENTITY) {
                    return new VertexBuffer(accessor, VertexFormatType.VANILLA, accessor.kinetic$reserve(vertexCount));
                } else {
                    var irisType = IrisCompat.determineVertexFormatType(format);
                    if (irisType != null) {
                        return new VertexBuffer(accessor, irisType, accessor.kinetic$reserve(vertexCount));
                    }
                }
            }
            // 这里原先还有一次"只预留、不使用"的调用：它不推进任何指针，却同样可能越界，
            // 而且回退写入本来就会让原版自己按顶点扩容。删掉。
        }
        return new VertexBuffer(null, VertexFormatType.FALLBACK, null);
    }

    @Borrowed
    @Aligned(64)
    public static NativeBuffer getMatBuffer(PoseStack.Pose pose) {
        var buffer = MatBufferHolder.BUFFER;
        var buf = buffer.nio();

        var view = RenderSystem.getModelViewMatrix();
        var proj = RenderSystem.getProjectionMatrix();

        pose.pose().get(buf);
        view.get(64, buf);
        proj.get(128, buf);
        pose.normal().get(192, buf);

        return buffer;
    }

    private static long packFlags(VertexFormatType v, RenderContextType c) {
        return ((long) v.id() << 2) | c.id();
    }

    private static long packLightAndOverlay(int lightUv, int overlayOv) {
        return ((long) lightUv << 32) | overlayOv;
    }

    static int packColor(int argb) {
        return ((argb >>> 16) & 0xFF) |
                (argb & 0xFF00) |
                ((argb & 0xFF) << 16) |
                (argb & 0xFF000000);
    }

    static native boolean nRender(Object vertexBuffer, int vertexBufferFlag, long matPtr, long modelStatePtr,
                                          long lightAndOverlay, int color, long flags, long irisEntityId);

    private static final class MatBufferHolder {
        private static final NativeBuffer BUFFER = NativeBuffer.allocate(256, 64);
    }

    private record VertexBuffer(@Nullable VertexBufferAccessor accessor, VertexFormatType type, @Nullable NativeBuffer region){}
}
