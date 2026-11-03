package com.offlinemaps.core.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pins down what an overlay install does to a style: everything missing is added, nothing that is
 * already there is added twice, and clearing a route (which only writes a payload into the existing
 * route source) never asks for another source or layer.
 */
class OverlayLayerPlanTest {

    /** Stands in for the live style: the ids it currently holds. */
    private class FakeStyle(sources: Collection<String> = emptyList(), layers: Collection<String> = emptyList()) {
        val sources = LinkedHashSet(sources)
        val layers = LinkedHashSet(layers)
        var sourceLookups = 0
        var layerLookups = 0

        fun plan(): OverlayLayerPlan.Install = OverlayLayerPlan.missing(
            sourceExists = {
                sourceLookups++
                it in sources
            },
            layerExists = {
                layerLookups++
                it in layers
            },
        )

        /** Applies an install the way the map controller does: sources first, then layers. */
        fun apply(install: OverlayLayerPlan.Install) {
            sources += install.sources
            layers += install.layers.map { it.id }
        }
    }

    @Test
    fun emptyStyleIsGivenEveryOverlay() {
        val install = FakeStyle().plan()

        assertThat(install.sources).containsExactly(
            OverlayLayerPlan.USER_SOURCE,
            OverlayLayerPlan.ROUTE_SOURCE,
            OverlayLayerPlan.DESTINATION_SOURCE,
        ).inOrder()
        assertThat(install.layers.map { it.id }).containsExactly(
            OverlayLayerPlan.ACCURACY_LAYER,
            OverlayLayerPlan.USER_LAYER,
            OverlayLayerPlan.ROUTE_CASING,
            OverlayLayerPlan.ROUTE_LAYER,
            OverlayLayerPlan.DESTINATION_LAYER,
        ).inOrder()
        assertThat(install.isEmpty).isFalse()
        assertThat(install.size).isEqualTo(8)
    }

    @Test
    fun installingTwiceAsksForNothingTheSecondTime() {
        val style = FakeStyle()
        style.apply(style.plan())

        val second = style.plan()

        assertThat(second.isEmpty).isTrue()
        assertThat(second.size).isEqualTo(0)
        assertThat(style.sources).hasSize(3)
        assertThat(style.layers).hasSize(5)
    }

    @Test
    fun aStyleThatKeptOneSourceStillGetsTheOthers() {
        // The old guard returned as soon as the puck source existed, so this style could never
        // draw a route or a destination.
        val style = FakeStyle(sources = listOf(OverlayLayerPlan.USER_SOURCE))

        val install = style.plan()

        assertThat(install.sources).containsExactly(
            OverlayLayerPlan.ROUTE_SOURCE,
            OverlayLayerPlan.DESTINATION_SOURCE,
        ).inOrder()
        assertThat(install.layers.map { it.id }).containsExactly(
            OverlayLayerPlan.ACCURACY_LAYER,
            OverlayLayerPlan.USER_LAYER,
            OverlayLayerPlan.ROUTE_CASING,
            OverlayLayerPlan.ROUTE_LAYER,
            OverlayLayerPlan.DESTINATION_LAYER,
        ).inOrder()
    }

    @Test
    fun aSourceThatIsAlreadyThereIsNeverAddedAgain() {
        val style = FakeStyle(sources = OverlayLayerPlan.sources)

        val install = style.plan()

        assertThat(install.sources).isEmpty()
        assertThat(install.layers.map { it.id }).hasSize(5)
    }

    @Test
    fun aLayerThatIsAlreadyThereIsNeverAddedAgain() {
        val style = FakeStyle(
            sources = OverlayLayerPlan.sources,
            layers = OverlayLayerPlan.layers.map { it.id },
        )

        val install = style.plan()

        assertThat(install.isEmpty).isTrue()
    }

    @Test
    fun clearingAndResettingARouteNeverAsksForAnotherSource() {
        // What a routing session does: install once, then route and clear repeatedly. Neither
        // writes a source or a layer - setRoute only pushes a payload - so the plan stays empty and
        // the style keeps exactly the three sources it started with.
        val style = FakeStyle()
        style.apply(style.plan())

        repeat(20) {
            val plan = style.plan()
            assertThat(plan.isEmpty).isTrue()
            style.apply(plan)
        }

        assertThat(style.sources).hasSize(OverlayLayerPlan.sources.size)
        assertThat(style.layers).hasSize(OverlayLayerPlan.layers.size)
    }

    @Test
    fun aReloadedStyleGetsTheOverlaysAgainExactlyOnce() {
        // A style reload drops the whole old style object, so the plan has to hand out everything
        // again for the new one - and nothing more than once.
        val style = FakeStyle()
        style.apply(style.plan())

        val fresh = FakeStyle()
        val install = fresh.plan()
        fresh.apply(install)

        assertThat(install.size).isEqualTo(8)
        assertThat(fresh.plan().isEmpty).isTrue()
    }

    @Test
    fun declaredIdsAreUniqueAndEveryLayerReadsADeclaredSource() {
        assertThat(OverlayLayerPlan.sources.toSet()).hasSize(OverlayLayerPlan.sources.size)
        assertThat(OverlayLayerPlan.layers.map { it.id }.toSet()).hasSize(OverlayLayerPlan.layers.size)
        OverlayLayerPlan.layers.forEach { layer ->
            assertThat(layer.sourceId).isIn(OverlayLayerPlan.sources)
        }
    }

    @Test
    fun foreignSourcesAndLayersAreLeftAlone() {
        val style = FakeStyle(
            sources = listOf("base-tiles", "terrain-dem"),
            layers = listOf("water", "roads", MapStyleFactory.BUILDING_3D_LAYER_ID),
        )

        val install = style.plan()
        style.apply(install)

        assertThat(style.sources).containsAtLeast("base-tiles", "terrain-dem")
        assertThat(style.layers).containsAtLeast("water", "roads")
        assertThat(style.sources).hasSize(5)
        assertThat(style.layers).hasSize(8)
    }

    @Test
    fun eachIdIsAskedAboutOnce() {
        val style = FakeStyle()

        style.plan()

        assertThat(style.sourceLookups).isEqualTo(OverlayLayerPlan.sources.size)
        assertThat(style.layerLookups).isEqualTo(OverlayLayerPlan.layers.size)
    }
}
