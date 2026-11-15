package com.kinetic.natives.render;

import com.kinetic.buffer.ArrayBuffer;
import com.kinetic.buffer.NativeBuffer;
import com.kinetic.geckolib3.model.AnimatedGeoModel;
import com.kinetic.proto.mixel.asset.model.data.Bone;
import com.kinetic.proto.mixel.asset.model.data.CubeLegacy;
import com.kinetic.proto.mixel.asset.model.data.Cubes;
import com.kinetic.proto.mixel.asset.model.data.GeoModel;
import com.kinetic.proto.mixel.asset.model.data.GeoProperties;
import com.kinetic.testutil.NativeLibraryExtension;
import com.kinetic.util.ProtoUtil;
import java.nio.ByteBuffer;
import org.joml.Vector2f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfEnvironmentVariable(named = "KINETIC_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativeModelStateIntegrationTest {
    @Test
    void tryBakeRejectsMalformedJavaOwnedGeometry() {
        try (var modelData = ArrayBuffer.move(new byte[]{(byte) 0xff});
             var texture = NativeBuffer.allocate(4)) {
            assertFalse(NativeBakedModel.tryBake(
                    modelData, texture, 1, 1, 0, false, false, false));
        }
    }

    @Test
    void extractsQuadUv() throws Exception {
        var cube = CubeLegacy.newBuilder()
                .setFaceCount(1)
                .addPos(0).addPos(0).addPos(0).addPos(1).addPos(0).addPos(0)
                .addPos(1).addPos(1).addPos(0).addPos(0).addPos(1).addPos(0)
                .addPosIndices(0).addPosIndices(1).addPosIndices(2).addPosIndices(3)
                .addUv(0).addUv(0).addUv(0.5f).addUv(0)
                .addUv(0.5f).addUv(0.5f).addUv(0).addUv(0.5f)
                .addUvIndices(0).addUvIndices(1).addUvIndices(2).addUvIndices(3)
                .addNormal(0).addNormal(0).addNormal(1)
                .build();
        var cubes = Cubes.newBuilder().addCubesLegacy(cube).build();
        var model = GeoModel.newBuilder()
                .addBones(Bone.newBuilder()
                        .setName("root")
                        .addPivot(0).addPivot(0).addPivot(0)
                        .addRotate(0).addRotate(0).addRotate(0)
                        .setCubeCount(1).build())
                .setProperties(properties())
                .setCubes(ByteBuffer.wrap(ProtoUtil.serializeToArray(cubes)))
                .build();

        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize());
             var texture = NativeBuffer.allocate(16)) {
            model.writeTo(ProtoUtil.sink(modelData));
            texture.nio().putLong(0, -1L).putLong(8, -1L);
            var baked = NativeBakedModel.bake(modelData, 1, texture,
                    2, 2, 29, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, 1);
                try (var bakedModel = read.bakedModel();
                     var state = NativeModelState.create()) {
                    var attributes = new float[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
                    attributes[6] = 1;
                    attributes[7] = 1;
                    attributes[8] = 1;
                    attributes[13] = 0xFFFF;
                    assertTrue(state.extract(bakedModel, attributes));
                    var firstPoseView = state.getBonePoses();
                    var firstRenderBoneView = state.getRenderBoneIndices();
                    assertEquals(1, firstRenderBoneView.capacity());
                    firstRenderBoneView.position(1);

                    var uv = bakedModel.getCubeData(0, 1, 0, 1)[0]
                            .quads()[0].uv();
                    assertArrayEquals(new Vector2f[]{
                            new Vector2f(0, 0), new Vector2f(0.5f, 0),
                            new Vector2f(0.5f, 0.5f), new Vector2f(0, 0.5f)
                    }, uv);

                    assertTrue(state.extract(bakedModel, attributes));
                    assertSame(firstPoseView, state.getBonePoses());
                    assertSame(firstRenderBoneView, state.getRenderBoneIndices());
                    assertEquals(0, state.getRenderBoneIndices().position());
                }
            }
        }
    }

    @Test
    void extractsBorrowedViewsAndCountsWithNoRenderBones() throws Exception {
        var model = GeoModel.newBuilder()
                .addBones(Bone.newBuilder()
                        .setName("root")
                        .addRotate(0).addRotate(0).addRotate(0)
                        .addPivot(0).addPivot(0).addPivot(0).build())
                .setProperties(properties())
                .setCubes(ByteBuffer.allocate(0))
                .build();

        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize());
             var texture = NativeBuffer.allocate(4)) {
            model.writeTo(ProtoUtil.sink(modelData));
            var baked = NativeBakedModel.bake(modelData, 1, texture,
                    1, 1, 0, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, 1);
                try (var bakedModel = read.bakedModel();
                     var state = NativeModelState.create()) {
                    var attributes = new float[AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
                    attributes[6] = 1;
                    attributes[7] = 1;
                    attributes[8] = 1;
                    attributes[13] = 0xFFFF;

                    assertTrue(state.extract(bakedModel, attributes));
                    var originalBonePoseView = state.getBonePoses();
                    assertEquals(1, originalBonePoseView.getBoneCount());
                    assertEquals(0, state.getRenderBoneIndices().capacity());
                    assertTrue(state.getLocatorBoneIndices().isEmpty());
                    assertEquals(0, state.getTotalVertexCount());
                    assertEquals(0, state.getTranslucentVertexCount());

                    assertFalse(state.extract(bakedModel, new float[0]));
                    assertFalse(state.isValid());
                    assertNull(state.getBonePoses());

                    assertTrue(state.extract(bakedModel, attributes));
                    assertSame(originalBonePoseView, state.getBonePoses());

                    state.close();
                    assertFalse(state.isValid());
                    assertNull(state.getBonePoses());
                    assertNull(state.getRenderBoneIndices());
                    assertThrows(IllegalStateException.class, state::get);
                }
            }
        }
    }

    private static GeoProperties properties() {
        return GeoProperties.newBuilder()
                .setTextureHeight(0)
                .setTextureWidth(0)
                .build();
    }
}
