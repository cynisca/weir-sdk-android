package studio.aldric.weir.engine

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import studio.aldric.weir.bridge.WeirJson

class FlowConfigDecodeTest {
    private val allScreensJson = """
        {
          "specVersion":4,"id":"decode_all","name":"Decode all",
          "variables":[
            {"id":"s","type":"string","default":"hello"},
            {"id":"n","type":"number","default":2.5},
            {"id":"b","type":"boolean","default":true},
            {"id":"a","type":"stringArray","default":["x","y"]},
            {"id":"e","type":"enum","enumValues":["x","y"]}
          ],
          "theme":{"colors":{"background":"#fff"},"roles":{"kicker":{"text":"#123456"}},"motion":{"particle":{}}},
          "screens":[
            {"id":"w","type":"welcome","title":"Welcome","image":"welcome-hero","socialProof":{"rating":4.9},"demo":{"messages":[{"from":"app","text":"Hi"}]},"cta":{"label":"Go"},"gating":{"fallback":"skip"}},
            {"id":"ss","type":"singleSelect","title":"One","variable":"s","options":[{"id":"a","label":"A","value":"a"}],"gating":{"fallback":"substitute","substituteScreen":"w"}},
            {"id":"ms","type":"multiSelect","title":"Many","variable":"a","options":[{"id":"a","label":"A"}],"gating":{"fallback":"serveOlderConfig"}},
            {"id":"sl","type":"slider","title":"Slide","variable":"n","min":0,"max":10,"default":4},
            {"id":"ni","type":"numberInput","title":"Number","variable":"n","unitToggle":{"options":[{"label":"kg","unit":"kg","factor":1}]}},
            {"id":"ti","type":"textInput","title":"Text","variable":"s"},
            {"id":"lo","type":"loader","messages":["Loading"]},
            {"id":"sp","type":"socialProof","testimonials":[{"quote":"Great","author":"A"}],"cta":{"label":"Next"}},
            {"id":"pp","type":"permissionPrime","title":"Notify","permission":"notifications","primeCta":{"label":"Allow"}},
            {"id":"pw","type":"paywall","headline":"Upgrade","products":[{"id":"p","label":"Monthly"}],"cta":{"label":"Buy"},"footnote":["Terms","Privacy"]},
            {"id":"mo","type":"moment","headline":"Reveal","image":"custom:hero","citation":{"text":"Text","source":"Source"},"cta":{"label":"Continue"}},
            {"id":"hc","type":"holdToCommit","title":"Commit","variable":"s","cta":{"label":"Hold"}},
            {"id":"de","type":"demo","messages":[{"from":"user","text":"Hello","sources":["one"]}],"cta":{"label":"Done"}},
            {"id":"cu","type":"custom","component":"my.component","props":{"nested":{"ok":true},"count":3}}
          ]
        }
    """.trimIndent()

    private fun decode() = WeirJson.decodeFromString(FlowConfig.serializer(), allScreensJson)

    @Test fun decodesEveryStockScreenAndCustomDiscriminator() {
        val config = decode()
        assertEquals(
            listOf("welcome", "singleSelect", "multiSelect", "slider", "numberInput", "textInput", "loader", "socialProof", "permissionPrime", "paywall", "moment", "holdToCommit", "demo", "custom"),
            config.screens.map { it.typeName },
        )
        val custom = config.screens.last() as CustomScreenConfig
        assertEquals(true, (custom.props.getValue("nested") as JsonObject).getValue("ok").jsonPrimitive.content.toBoolean())
        assertEquals("hero", ((config.screens[10] as MomentScreenConfig).image as ImageRef.Custom).id)
    }

    @Test fun appliesEveryNonObviousDecodeDefault() {
        val config = decode()
        val single = config.screens[1] as SingleSelectScreenConfig
        assertTrue(single.required); assertTrue(single.autoAdvance)
        assertEquals(OptionsLayout.stack, single.optionsLayout); assertEquals(SelectionStyle.affordance, single.selectionStyle)
        val multi = config.screens[2] as MultiSelectScreenConfig
        assertEquals(OptionsLayout.stack, multi.optionsLayout); assertEquals(SelectionStyle.affordance, multi.selectionStyle)
        assertEquals(1.0, (config.screens[3] as SliderScreenConfig).step, 0.0)
        val number = config.screens[4] as NumberInputScreenConfig
        assertEquals(NumberInputStyle.defaultStyle, number.style); assertEquals(NumberInputDisplay.number, number.display)
        assertEquals(1.0, number.step, 0.0); assertEquals(0, number.unitToggle!!.defaultIndex)
        assertEquals(0.0, number.unitToggle.options.single().offset, 0.0)
        assertEquals(2500, (config.screens[6] as LoaderScreenConfig).durationMs)
        val paywall = config.screens[9] as PaywallScreenConfig
        assertEquals(PaywallFeatureStyle.plain, paywall.featureStyle); assertEquals(PaywallProductsLayout.stack, paywall.productsLayout)
        assertEquals("Restore", paywall.restoreLabel); assertEquals(PaywallContentAlign.left, paywall.contentAlign); assertTrue(paywall.dismissable)
        val hold = config.screens[11] as HoldToCommitScreenConfig
        assertTrue(hold.allowCustom); assertEquals(1500, hold.holdMs)
        assertEquals("system-ui", config.theme.fontFamily); assertEquals(16.0, config.theme.spacing, 0.0)
        assertEquals(HeadlineMotion.none, config.theme.motion.headline); assertEquals(AmbientMotion.none, config.theme.motion.ambient)
        assertEquals(TransitionMotion.fade, config.theme.motion.transition)
        assertEquals(ParticleSprite.leaf, config.theme.motion.particle!!.sprite); assertEquals(0.4, config.theme.motion.particle.density, 0.0)
    }

    @Test fun decodesUnionsBranchingExperimentsAndAllGatingFallbacks() {
        val json = """
          {"specVersion":4,"id":"unions","name":"Unions",
           "experiments":[{"id":"exp","variants":[{"id":"a"}],"targeting":{}}],
           "screens":[
             {"id":"a","type":"welcome","title":"A","cta":{"label":"Go"},"next":{"branches":[
               {"when":{"all":[{"var":"x","op":"in","value":["a",2,true]},{"not":{"var":"x","op":"includes","value":"z"}}]},"goto":"b"}
             ],"default":"c"}},
             {"id":"b","type":"welcome","title":"B","cta":{"label":"Go"},"gating":{"fallback":"skip"}},
             {"id":"c","type":"welcome","title":"C","cta":{"label":"Go"},"gating":{"fallback":"substitute"}},
             {"id":"d","type":"welcome","title":"D","cta":{"label":"Go"},"gating":{"fallback":"serveOlderConfig"}}
           ]}
        """.trimIndent()
        val config = WeirJson.decodeFromString(FlowConfig.serializer(), json)
        assertEquals(listOf(ScreenGating.Fallback.skip, ScreenGating.Fallback.substitute, ScreenGating.Fallback.serveOlderConfig), config.screens.drop(1).map { it.base.gating!!.fallback })
        val next = config.screens.first().base.next as NextTransition.Conditional
        assertTrue(next.branches.first().`when` is Condition.All)
        assertEquals(1.0, config.experiments.single().variants.single().weight, 0.0)
        assertEquals(0.0, config.experiments.single().holdout, 0.0)
        assertEquals(ExperimentTargeting.NewVsReturning.both, config.experiments.single().targeting!!.newVsReturning)
        assertFalse(config.theme.colors.asDictionary().containsKey("accent"))
    }
}
