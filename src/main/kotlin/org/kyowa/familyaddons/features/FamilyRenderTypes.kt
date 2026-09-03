package org.kyowa.familyaddons.features

import com.mojang.blaze3d.pipeline.DepthStencilState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.CompareOp
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.OutputTarget
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.resources.Identifier
import org.kyowa.familyaddons.FamilyAddons

object FamilyRenderTypes {

    val LINES: RenderType by lazy {
        RenderType.create(
            "familyaddons_lines",
            RenderSetup.builder(RenderPipelines.LINES)
                .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .setOutputTarget(OutputTarget.MAIN_TARGET)
                .createRenderSetup()
        )
    }

    /**
     * True depth-test-disabled clone of the vanilla LINES pipeline. 26.2 keeps
     * the shader snippets private, but every getter needed to rebuild the
     * pipeline is public, so we copy it field-by-field and swap the depth
     * state for ALWAYS_PASS. Falls back to the old DEPTH_BIAS approximation
     * if a future version changes the pipeline shape.
     */
    private val LINES_NO_DEPTH_PIPELINE: RenderPipeline by lazy {
        try {
            val base = RenderPipelines.LINES
            val builder = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("familyaddons", "pipeline/lines_no_depth"))
                .withVertexShader(base.vertexShader)
                .withFragmentShader(base.fragmentShader)
                .withPrimitiveTopology(base.primitiveTopology)
                .withCull(base.isCull)
                .withPolygonMode(base.polygonMode)
                .withDepthStencilState(DepthStencilState(CompareOp.ALWAYS_PASS, false))
            base.vertexFormatBindings.forEachIndexed { i, fmt -> fmt?.let { builder.withVertexBinding(i, it) } }
            base.colorTargetStates.forEachIndexed { i, s -> s?.let { builder.withColorTargetState(i, it) } }
            base.bindGroupLayouts.forEach { builder.withBindGroupLayout(it) }
            base.shaderDefines.flags().forEach { builder.withShaderDefine(it) }
            base.shaderDefines.values().forEach { (k, v) ->
                v.toIntOrNull()?.let { builder.withShaderDefine(k, it) }
                    ?: v.toFloatOrNull()?.let { builder.withShaderDefine(k, it) }
            }
            builder.build()
        } catch (e: Exception) {
            FamilyAddons.LOGGER.warn("Couldn't build no-depth line pipeline, falling back to depth bias", e)
            RenderPipelines.LINES_DEPTH_BIAS
        }
    }

    val LINES_NO_DEPTH: RenderType by lazy {
        RenderType.create(
            "familyaddons_lines_no_depth",
            RenderSetup.builder(LINES_NO_DEPTH_PIPELINE)
                .setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
                .setOutputTarget(OutputTarget.MAIN_TARGET)
                .createRenderSetup()
        )
    }

    // Solid-color translucent quad layer for beacon-beam columns. 26.1 dropped the
    // public textured beacon-beam RenderType factory, so the beam is drawn as
    // position+color quads (no texture) via the DEBUG_QUADS pipeline.
    val BEAM: RenderType by lazy {
        RenderType.create(
            "familyaddons_beam",
            RenderSetup.builder(RenderPipelines.DEBUG_QUADS)
                .setOutputTarget(OutputTarget.MAIN_TARGET)
                .createRenderSetup()
        )
    }
}
