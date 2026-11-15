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
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.lwjgl.system.MemoryUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Measures the per-frame cost of the native extract step
 * ({@link NativeModelState#extract(NativeBakedModel, float[])}).
 *
 * <p>Opt-in on purpose: the 200k-iteration loops must never slow down the ordinary test
 * suite. The class is gated behind {@code KINETIC_NATIVE_PATH} (native library available)
 * <em>and</em> {@code KINETIC_BENCH_EXTRACT=1}; without the latter every test is skipped.
 * Run it with:
 *
 * <pre>
 * KINETIC_BENCH_EXTRACT=1 KINETIC_BENCH_DIAGNOSTIC=1 KINETIC_BENCH_RENDER=1
 * ./gradlew test --offline --tests '*NativeExtractCostBenchmarkTest*'
 * </pre>
 *
 * <p>Set {@code KINETIC_BENCH_RENDER=1} to include direct JNI fallback-output timing.
 */
@EnabledIfEnvironmentVariable(named = "KINETIC_NATIVE_PATH", matches = ".+")
@ExtendWith(NativeLibraryExtension.class)
class NativeExtractCostBenchmarkTest {
    private static final String BENCHMARK_ENV = "KINETIC_BENCH_EXTRACT";
    private static final String DIAGNOSTIC_ENV = "KINETIC_BENCH_DIAGNOSTIC";
    private static final String RENDER_ENV = "KINETIC_BENCH_RENDER";
    private static final int WARMUP_ITERATIONS = 5_000;
    private static final int MEASURE_ITERATIONS = 200_000;
    private static final int RENDER_WARMUP_ITERATIONS = 500;
    private static final int RENDER_ITERATIONS_PER_BATCH = 2_000;
    private static final int RENDER_BATCHES = 5;
    private static final int OUTPUT_INTS_PER_VERTEX = 8;
    private static final int GETTER_ITERATIONS = 20_000_000;
    private static final int TEXTURE_SIZE = 64;
    private static final int ORIGIN_VERSION = 29;
    private static final int MODEL_UNITS_PER_BLOCK = 16;
    private static final int VERTICES_PER_CUBE = 24;
    private static final int CULLED_VERTICES_PER_CUBE = 12;

    @Test
    void measuresNativeExtractCost() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv(BENCHMARK_ENV)),
                "set " + BENCHMARK_ENV + "=1 to run the native extract cost benchmark");

        System.out.println("=== KINETIC native extract cost benchmark ===");
        System.out.println("cpu             = " + cpuModel());
        System.out.println("java            = " + System.getProperty("java.version"));
        System.out.println("os              = " + System.getProperty("os.name") + " "
                + System.getProperty("os.version") + " / " + System.getProperty("os.arch"));
        System.out.println("warmup          = " + WARMUP_ITERATIONS + " iterations");
        System.out.println("measure         = " + MEASURE_ITERATIONS + " iterations");
        System.out.println("attribute count = " + AnimatedGeoModel.BONE_ATTRIBUTE_COUNT
                + " floats/bone");
        System.out.println();

        for (var spec : specs()) {
            measureModel(spec);
        }
        measureGetters();
    }

    private static List<ModelSpec> specs() {
        return List.of(ModelSpec.tiny(), ModelSpec.small(), ModelSpec.mid(),
                ModelSpec.large(), ModelSpec.player());
    }

    private static void measureModel(ModelSpec spec) throws IOException {
        var attributes = spec.newAttributes();
        var model = spec.build();
        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize());
             var texture = NativeBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4)) {
            model.writeTo(ProtoUtil.sink(modelData));
            MemoryUtil.memSet(texture.ptr(), 0xff, texture.size());
            var baked = NativeBakedModel.bake(modelData, spec.boneCount(), texture,
                    TEXTURE_SIZE, TEXTURE_SIZE, ORIGIN_VERSION, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, spec.boneCount());
                try (var bakedModel = read.bakedModel();
                     var state = NativeModelState.create()) {
                    assertTrue(state.extract(bakedModel, attributes),
                            spec.name() + ": extract failed");

                    var bonePoses = state.getBonePoses();
                    var renderBoneIndices = state.getRenderBoneIndices();
                    var totalVertexCount = state.getTotalVertexCount();
                    var translucentVertexCount = state.getTranslucentVertexCount();

                    assertEquals(spec.boneCount(), bonePoses.getBoneCount(),
                            spec.name() + ": bone count");
                    assertEquals(spec.scheduledVertexCount(), totalVertexCount,
                            spec.name() + ": total vertex count");
                    assertEquals(spec.expectedRenderBoneCount(), renderBoneIndices.capacity(),
                            spec.name() + ": render bone count");
                    assertEquals(0, translucentVertexCount,
                            spec.name() + ": translucent vertex count");
                    assertTrue(state.getLocatorBoneIndices().isEmpty(),
                            spec.name() + ": locator bones");

                    for (var i = 0; i < WARMUP_ITERATIONS; i++) {
                        state.extract(bakedModel, attributes);
                    }

                    var allocatedBefore = currentThreadAllocatedBytes();
                    var start = System.nanoTime();
                    var success = true;
                    for (var i = 0; i < MEASURE_ITERATIONS; i++) {
                        success &= state.extract(bakedModel, attributes);
                    }
                    var elapsed = System.nanoTime() - start;
                    var allocatedAfter = currentThreadAllocatedBytes();

                    assertTrue(success, spec.name() + ": extract failed during measurement");
                    assertEquals(spec.expectedRenderBoneCount(), renderBoneIndices.capacity(),
                            spec.name() + ": render bone count after measurement");

                    var nanosPerExtract = (double) elapsed / MEASURE_ITERATIONS;
                    System.out.printf(
                            "%-26s bones=%2d cubes=%2d fullVerts=%4d schedVerts=%4d "
                                    + "renderBones=%2d | total=%9.3f ms | %9.1f ns/extract "
                                    + "| %11.0f extracts/s | %6.1f ns/bone | %6.1f B/extract%n",
                            spec.name(), spec.boneCount(), spec.cubeCount(),
                            spec.fullVertexCount(), totalVertexCount,
                            renderBoneIndices.capacity(), elapsed / 1_000_000.0,
                            nanosPerExtract, 1_000_000_000.0 / nanosPerExtract,
                            nanosPerExtract / spec.boneCount(),
                            bytesPerOperation(allocatedBefore, allocatedAfter, MEASURE_ITERATIONS));
                    if (isDiagnostic()) {
                        System.out.println("  baked partitions: " + describeBaked(bakedModel));
                        System.out.println("  batch spread:     " + batchSpread(state, bakedModel,
                                attributes));
                    }
                    if ("1".equals(System.getenv(RENDER_ENV))) {
                        measureNativeRender(spec.name(), state, totalVertexCount);
                    }
                }
            }
        }
    }

    private static void measureNativeRender(String name, NativeModelState state,
                                            int vertexCount) {
        var output = new int[Math.multiplyExact(vertexCount, OUTPUT_INTS_PER_VERTEX)];
        try (var matrices = NativeBuffer.allocate(256, 64)) {
            MemoryUtil.memSet(matrices.ptr(), 0, matrices.size());
            for (var matrix = 0; matrix < 4; matrix++) {
                var base = matrices.ptr() + matrix * 64L;
                for (var diagonal = 0; diagonal < 4; diagonal++) {
                    MemoryUtil.memPutFloat(base + diagonal * 20L, 1.0f);
                }
            }

            var success = true;
            for (var i = 0; i < RENDER_WARMUP_ITERATIONS; i++) {
                success &= NativeRenderer.nRender(output, 0, matrices.ptr(), state.get(),
                        0L, -1, 0L, 0L);
            }

            var allocatedBefore = currentThreadAllocatedBytes();
            var batches = new double[RENDER_BATCHES];
            for (var batch = 0; batch < RENDER_BATCHES; batch++) {
                var start = System.nanoTime();
                for (var i = 0; i < RENDER_ITERATIONS_PER_BATCH; i++) {
                    success &= NativeRenderer.nRender(output, 0, matrices.ptr(), state.get(),
                            0L, -1, 0L, 0L);
                }
                batches[batch] = (double) (System.nanoTime() - start)
                        / RENDER_ITERATIONS_PER_BATCH;
            }
            var allocatedAfter = currentThreadAllocatedBytes();
            assertTrue(success, name + ": native render failed during measurement");

            var sorted = batches.clone();
            Arrays.sort(sorted);
            var batchValues = new StringBuilder();
            var average = 0.0;
            for (var value : batches) {
                average += value / batches.length;
                batchValues.append(batchValues.isEmpty() ? "" : ", ")
                        .append(String.format("%.1f", value));
            }
            System.out.printf("  native render   = %-26s verts=%4d | %8.1f ns/draw"
                            + " | %6.2f ns/vertex | batch min/median/max %.1f/%.1f/%.1f"
                            + " ns | %6.1f B/draw | batches [%s]%n",
                    name, vertexCount, average, average / vertexCount,
                    sorted[0], sorted[sorted.length / 2], sorted[sorted.length - 1],
                    bytesPerOperation(allocatedBefore, allocatedAfter,
                            (long) RENDER_BATCHES * RENDER_ITERATIONS_PER_BATCH), batchValues);
        }
    }

    /**
     * The per-frame getters are pure Java field reads (see {@link NativeModelState}); this
     * measures them so the report can state that with numbers rather than by reading code.
     */
    private static void measureGetters() throws IOException {
        var spec = ModelSpec.player();
        var attributes = spec.newAttributes();
        var model = spec.build();
        try (var modelData = ArrayBuffer.allocate(model.getSerializedSize());
             var texture = NativeBuffer.allocate(TEXTURE_SIZE * TEXTURE_SIZE * 4)) {
            model.writeTo(ProtoUtil.sink(modelData));
            MemoryUtil.memSet(texture.ptr(), 0xff, texture.size());
            var baked = NativeBakedModel.bake(modelData, spec.boneCount(), texture,
                    TEXTURE_SIZE, TEXTURE_SIZE, ORIGIN_VERSION, false);
            try (var bakedData = baked.bakedData()) {
                var read = NativeBakedModel.read(bakedData, spec.boneCount());
                try (var bakedModel = read.bakedModel();
                     var state = NativeModelState.create()) {
                    state.extract(bakedModel, attributes);

                    var sink = 0;
                    var start = System.nanoTime();
                    for (var i = 0; i < GETTER_ITERATIONS; i++) {
                        sink += state.getTotalVertexCount();
                        sink += state.getTranslucentVertexCount();
                        sink += state.getBonePoses().getBoneCount();
                        sink += state.getRenderBoneIndices().capacity();
                        sink += state.getLocatorBoneIndices().size();
                    }
                    var elapsed = System.nanoTime() - start;
                    System.out.println();
                    System.out.printf(
                            "getters x5 (vertexCount+translucent+bonePoses+renderBones+locators)"
                                    + " | total=%8.3f ms | %8.2f ns per getter group | sink=%d%n",
                            elapsed / 1_000_000.0,
                            (double) elapsed / GETTER_ITERATIONS, sink);
                }
            }
        }
    }

    private static double bytesPerOperation(long before, long after, long operations) {
        return before < 0 || after < before
                ? Double.NaN
                : (double) (after - before) / operations;
    }

    private static long currentThreadAllocatedBytes() {
        var bean = ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) {
            return -1;
        }
        try {
            if (!allocationBean.isThreadAllocatedMemoryEnabled()) {
                allocationBean.setThreadAllocatedMemoryEnabled(true);
            }
            return allocationBean.getThreadAllocatedBytes(Thread.currentThread().getId());
        } catch (SecurityException | UnsupportedOperationException ignored) {
            return -1;
        }
    }

    /** Per-batch spread of the same measurement, to show how noisy the number is. */
    private static String batchSpread(NativeModelState state, NativeBakedModel bakedModel,
                                      float[] attributes) {
        var batches = 5;
        var perBatch = MEASURE_ITERATIONS / batches;
        var nanosPerExtract = new double[batches];
        for (var batch = 0; batch < batches; batch++) {
            var start = System.nanoTime();
            for (var i = 0; i < perBatch; i++) {
                state.extract(bakedModel, attributes);
            }
            nanosPerExtract[batch] = (double) (System.nanoTime() - start) / perBatch;
        }
        var sorted = nanosPerExtract.clone();
        Arrays.sort(sorted);
        var all = new StringBuilder();
        for (var value : nanosPerExtract) {
            all.append(all.isEmpty() ? "" : ", ").append(String.format("%.1f", value));
        }
        return String.format(
                "min=%.1f median=%.1f max=%.1f ns/extract over %d x %d (all: [%s])",
                sorted[0], sorted[batches / 2], sorted[batches - 1], batches, perBatch, all);
    }

    /** Per-partition cube/vertex totals straight out of the native baker. */
    private static String describeBaked(NativeBakedModel bakedModel) {
        var boneCount = bakedModel.getInfo().boneCount();
        var cutoutCubes = 0;
        var cutoutVertices = 0;
        var noCullingCubes = 0;
        var noCullingVertices = 0;
        var translucentCubes = 0;
        var translucentVertices = 0;
        var translucentCullingCubes = 0;
        var translucentCullingVertices = 0;
        for (var bone : bakedModel.getBoneInfo(0, boneCount)) {
            cutoutCubes += bone.cutout().cubeCount();
            cutoutVertices += bone.cutout().vertexCount();
            noCullingCubes += bone.cutoutNoCulling().cubeCount();
            noCullingVertices += bone.cutoutNoCulling().vertexCount();
            translucentCubes += bone.translucent().cubeCount();
            translucentVertices += bone.translucent().vertexCount();
            translucentCullingCubes += bone.translucentCulling().cubeCount();
            translucentCullingVertices += bone.translucentCulling().vertexCount();
        }
        return String.format(
                "cutout=%d cubes/%d verts, cutoutNoCulling=%d/%d, "
                        + "translucent=%d/%d, translucentCulling=%d/%d",
                cutoutCubes, cutoutVertices, noCullingCubes, noCullingVertices,
                translucentCubes, translucentVertices, translucentCullingCubes,
                translucentCullingVertices);
    }

    private static boolean isDiagnostic() {
        return "1".equals(System.getenv(DIAGNOSTIC_ENV));
    }

    private static String cpuModel() {
        var processorIdentifier = System.getenv("PROCESSOR_IDENTIFIER");
        if (processorIdentifier != null && !processorIdentifier.isBlank()) {
            return processorIdentifier;
        }
        try {
            for (var line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
                if (line.startsWith("model name")) {
                    return line.substring(line.indexOf(':') + 1).trim();
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return System.getProperty("os.arch");
    }

    /** A synthetic model shaped like a real one: a bone tree plus cubes spread over it. */
    private record ModelSpec(String name, int boneCount, int cubeCount,
                             int expectedRenderBoneCount, int fullVertexCount,
                             int scheduledVertexCount,
                             List<BoneSpec> bones, int[] cubesPerBone) {
        static ModelSpec tiny() {
            return build("tiny (1 bone/1 cube)", 1, 1);
        }

        static ModelSpec small() {
            return build("small (6 bones/6 cubes)", 6, 6);
        }

        static ModelSpec mid() {
            return build("mid (16 bones/16 cubes)", 16, 16);
        }

        static ModelSpec large() {
            return build("large (31 bones/25 cubes)", 31, 25);
        }

        /** 61 bones, 46 cubes, 1104 full vertices - the real built-in player model shape. */
        static ModelSpec player() {
            return build("player (61 bones/46 cubes)", 61, 46);
        }

        /**
         * Builds a player-like tree ({@code root} + {@code torso*} chain + {@code head*} +
         * two arms with palms and 5 three-bone fingers each + two legs) truncated to
         * {@code boneCount} bones, then gives the first {@code cubeCount} bones one cube
         * each so the cube total is exact. Bone index i's parent is always < i.
         */
        private static ModelSpec build(String name, int boneCount, int cubeCount) {
            var blueprint = new ArrayList<String[]>();
            blueprint.add(new String[]{"root", null});
            var torso = 10;
            for (var i = 0; i < torso && blueprint.size() < boneCount; i++) {
                blueprint.add(new String[]{"torso" + i, i == 0 ? "root" : "torso" + (i - 1)});
            }
            if (blueprint.size() < boneCount) {
                blueprint.add(new String[]{"head", "torso" + (torso - 1)});
            }
            if (blueprint.size() < boneCount) {
                blueprint.add(new String[]{"head_top", "head"});
            }
            for (var side = 0; side < 2 && blueprint.size() < boneCount; side++) {
                var s = side == 0 ? "l" : "r";
                var chain = List.of("arm_" + s + "_upper", "arm_" + s + "_lower",
                        "arm_" + s + "_hand", "palm_" + s, "palm_" + s + "_top");
                String parent = "torso" + (torso - 1);
                for (var bone : chain) {
                    if (blueprint.size() >= boneCount) {
                        break;
                    }
                    blueprint.add(new String[]{bone, parent});
                    parent = bone;
                }
                for (var finger = 0; finger < 5 && blueprint.size() < boneCount; finger++) {
                    var fingerParent = "palm_" + s;
                    for (var segment = 0; segment < 3; segment++) {
                        if (blueprint.size() >= boneCount) {
                            break;
                        }
                        var boneName = "finger_" + s + finger + "_" + segment;
                        blueprint.add(new String[]{boneName, fingerParent});
                        fingerParent = boneName;
                    }
                }
            }
            for (var side = 0; side < 2 && blueprint.size() < boneCount; side++) {
                var s = side == 0 ? "l" : "r";
                var chain = List.of("leg_" + s + "_upper", "leg_" + s + "_lower",
                        "foot_" + s, "foot_" + s + "_top");
                String parent = "root";
                for (var bone : chain) {
                    if (blueprint.size() >= boneCount) {
                        break;
                    }
                    blueprint.add(new String[]{bone, parent});
                    parent = bone;
                }
            }
            assertEquals(boneCount, blueprint.size(), name + ": generated bone count");

            var bones = new ArrayList<BoneSpec>(boneCount);
            for (var index = 0; index < blueprint.size(); index++) {
                var entry = blueprint.get(index);
                bones.add(new BoneSpec(entry[0], entry[1], 0, index * 0.5f, 0));
            }

            var cubesPerBone = new int[boneCount];
            var bonesWithCubes = 0;
            for (var index = 0; index < boneCount && bonesWithCubes < cubeCount; index++) {
                cubesPerBone[index] = 1;
                bonesWithCubes++;
            }
            assertEquals(cubeCount, bonesWithCubes, name + ": cube count");

            // Every cube is a full 6-face box: the baker keeps 24 vertices per cube, but the
            // render schedule counts only 3 quads (12 vertices) per cube for the cutout
            // partition, so getTotalVertexCount() reports the smaller, culled number.
            return new ModelSpec(name, boneCount, cubeCount, cubeCount,
                    cubeCount * VERTICES_PER_CUBE, cubeCount * CULLED_VERTICES_PER_CUBE,
                    bones, cubesPerBone);
        }

        GeoModel build() throws IOException {
            var cubes = Cubes.newBuilder();
            for (var index = 0; index < cubesPerBone.length; index++) {
                for (var cube = 0; cube < cubesPerBone[index]; cube++) {
                    cubes.addCubesLegacy(box(index));
                }
            }
            var model = GeoModel.newBuilder()
                    .setProperties(GeoProperties.newBuilder()
                            .setTextureWidth(TEXTURE_SIZE)
                            .setTextureHeight(TEXTURE_SIZE)
                            .build())
                    .setCubes(ByteBuffer.wrap(ProtoUtil.serializeToArray(cubes.build())));
            for (var index = 0; index < bones.size(); index++) {
                var bone = bones.get(index);
                var builder = Bone.newBuilder()
                        .setName(bone.name())
                        .addPivot(bone.pivotX()).addPivot(bone.pivotY()).addPivot(bone.pivotZ())
                        .addRotate(0).addRotate(0).addRotate(0)
                        .setCubeCount(cubesPerBone[index]);
                if (bone.parent() != null) {
                    builder.setParent(bone.parent());
                }
                model.addBones(builder.build());
            }
            return model.build();
        }

        /**
         * A valid attribute set for every bone: zero rotation, zero position, unit scale,
         * nothing hidden, no locator, opaque white, no glow - so every cube renders.
         */
        float[] newAttributes() {
            var attributes = new float[boneCount * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT];
            for (var index = 0; index < boneCount; index++) {
                var offset = index * AnimatedGeoModel.BONE_ATTRIBUTE_COUNT;
                attributes[offset + 6] = 1;
                attributes[offset + 7] = 1;
                attributes[offset + 8] = 1;
                attributes[offset + 12] = 0xFFFFFF;
                attributes[offset + 13] = 0xFFFF;
            }
            return attributes;
        }
    }

    private record BoneSpec(String name, String parent, float pivotX, float pivotY,
                            float pivotZ) {
    }

    private static final int[][] FACE_VERTICES = {
            // west, east, north, south, up, down (same winding as the production builder)
            {4, 3, 1, 2},
            {7, 8, 6, 5},
            {3, 7, 5, 1},
            {8, 4, 2, 6},
            {4, 8, 7, 3},
            {1, 5, 6, 2},
    };
    private static final int[][] FACE_NORMALS = {
            {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}, {0, 1, 0}, {0, -1, 0},
    };

    /** One 1-3 unit box with 6 per-face UV quads, mirroring the production cube layout. */
    private static CubeLegacy box(int index) {
        var sizeX = 2 + index % 2;
        var sizeY = 2 + (index / 2) % 2;
        var sizeZ = 1 + index % 3;
        var originX = -(sizeX / 2f);
        var originY = 0;
        var originZ = -(sizeZ / 2f);
        var x = sizeX / (float) MODEL_UNITS_PER_BLOCK;
        var y = sizeY / (float) MODEL_UNITS_PER_BLOCK;
        var z = sizeZ / (float) MODEL_UNITS_PER_BLOCK;

        // Minecraft box-UV corners, in the same vertex order as FACE_VERTICES.
        var u = (index * 3) % 32;
        var v = (index * 5) % 32;
        var uvCorners = new float[][]{
                {u + z, v + z + y},
                {u + z, v + z},
                {u + z + x, v + z + y},
                {u + z + x, v + z},
                {u, v + z + y},
                {u, v + z},
                {u + z + x, v},
                {u + z, v},
        };

        var positions = new float[8 * 3];
        for (var vertex = 0; vertex < 8; vertex++) {
            var px = originX + ((vertex & 1) != 0 ? sizeX : 0);
            var py = originY + ((vertex & 2) != 0 ? sizeY : 0);
            var pz = originZ + ((vertex & 4) != 0 ? sizeZ : 0);
            positions[vertex * 3] = px / MODEL_UNITS_PER_BLOCK;
            positions[vertex * 3 + 1] = py / MODEL_UNITS_PER_BLOCK;
            positions[vertex * 3 + 2] = pz / MODEL_UNITS_PER_BLOCK;
        }
        for (var value : positions) {
            assertTrue(Float.isFinite(value), "cube position must be finite");
        }

        var cube = CubeLegacy.newBuilder().setFaceCount(6);
        for (var value : positions) {
            cube.addPos(value);
        }
        for (var face = 0; face < 6; face++) {
            for (var corner = 0; corner < 4; corner++) {
                var vertex = FACE_VERTICES[face][corner] - 1;
                cube.addPosIndices(vertex);
                cube.addUv(uvCorners[vertex][0] / TEXTURE_SIZE);
                cube.addUv(uvCorners[vertex][1] / TEXTURE_SIZE);
                cube.addUvIndices(vertex);
            }
            cube.addNormal(FACE_NORMALS[face][0]);
            cube.addNormal(FACE_NORMALS[face][1]);
            cube.addNormal(FACE_NORMALS[face][2]);
        }
        return cube.build();
    }
}
