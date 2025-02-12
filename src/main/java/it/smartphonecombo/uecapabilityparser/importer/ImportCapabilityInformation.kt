package it.smartphonecombo.uecapabilityparser.importer

import it.smartphonecombo.uecapabilityparser.extension.BwMap
import it.smartphonecombo.uecapabilityparser.extension.Mimo
import it.smartphonecombo.uecapabilityparser.extension.fromLiteral
import it.smartphonecombo.uecapabilityparser.extension.getArray
import it.smartphonecombo.uecapabilityparser.extension.getArrayAtPath
import it.smartphonecombo.uecapabilityparser.extension.getInt
import it.smartphonecombo.uecapabilityparser.extension.getObject
import it.smartphonecombo.uecapabilityparser.extension.getString
import it.smartphonecombo.uecapabilityparser.extension.merge
import it.smartphonecombo.uecapabilityparser.extension.mutableListWithCapacity
import it.smartphonecombo.uecapabilityparser.extension.step
import it.smartphonecombo.uecapabilityparser.extension.typedList
import it.smartphonecombo.uecapabilityparser.model.BCS
import it.smartphonecombo.uecapabilityparser.model.BwClass
import it.smartphonecombo.uecapabilityparser.model.Capabilities
import it.smartphonecombo.uecapabilityparser.model.Duplex
import it.smartphonecombo.uecapabilityparser.model.EmptyBCS
import it.smartphonecombo.uecapabilityparser.model.LinkDirection
import it.smartphonecombo.uecapabilityparser.model.Modulation
import it.smartphonecombo.uecapabilityparser.model.Rat
import it.smartphonecombo.uecapabilityparser.model.SingleBCS
import it.smartphonecombo.uecapabilityparser.model.band.BandNrDetails
import it.smartphonecombo.uecapabilityparser.model.band.DuplexBandTable
import it.smartphonecombo.uecapabilityparser.model.bandwidth.BwTableNr
import it.smartphonecombo.uecapabilityparser.model.bandwidth.BwsBitMap
import it.smartphonecombo.uecapabilityparser.model.bandwidth.BwsNr
import it.smartphonecombo.uecapabilityparser.model.combo.ComboEnDc
import it.smartphonecombo.uecapabilityparser.model.combo.ComboLte
import it.smartphonecombo.uecapabilityparser.model.combo.ComboNr
import it.smartphonecombo.uecapabilityparser.model.combo.ComboNrDc
import it.smartphonecombo.uecapabilityparser.model.combo.ICombo
import it.smartphonecombo.uecapabilityparser.model.component.ComponentLte
import it.smartphonecombo.uecapabilityparser.model.component.ComponentNr
import it.smartphonecombo.uecapabilityparser.model.component.IComponent
import it.smartphonecombo.uecapabilityparser.model.feature.FeatureIndex
import it.smartphonecombo.uecapabilityparser.model.feature.FeaturePerCCLte
import it.smartphonecombo.uecapabilityparser.model.feature.FeaturePerCCNr
import it.smartphonecombo.uecapabilityparser.model.feature.FeatureSet
import it.smartphonecombo.uecapabilityparser.model.feature.FeatureSets
import it.smartphonecombo.uecapabilityparser.model.feature.IFeaturePerCC
import it.smartphonecombo.uecapabilityparser.model.json.UEEutraCapabilityJson
import it.smartphonecombo.uecapabilityparser.model.json.UEMrdcCapabilityJson
import it.smartphonecombo.uecapabilityparser.model.json.UENrCapabilityJson
import it.smartphonecombo.uecapabilityparser.model.json.UENrRrcCapabilityJson
import java.io.InputStream
import java.io.InputStreamReader
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

object ImportCapabilityInformation : ImportCapabilities {
    override fun parse(input: InputStream): Capabilities {
        val caBandCombosString = input.reader().use(InputStreamReader::readText)
        val caBandCombosJson =
            try {
                Json.parseToJsonElement(caBandCombosString) as? JsonObject
            } catch (_: SerializationException) {
                null
            }

        val eutraCapability = caBandCombosJson?.get(Rat.EUTRA.toString()) as? JsonObject
        val eutraNrCapability = caBandCombosJson?.get(Rat.EUTRA_NR.toString()) as? JsonObject
        val nrCapability = caBandCombosJson?.get(Rat.NR.toString()) as? JsonObject

        return parse(eutraCapability, eutraNrCapability, nrCapability)
    }

    fun parse(
        eutraCapability: JsonObject?,
        eutraNrCapability: JsonObject?,
        nrCapability: JsonObject?
    ): Capabilities {
        val comboList = Capabilities()
        var lteFeatures: FeatureSets? = null
        var nrFeatures: FeatureSets? = null

        if (eutraCapability != null) {
            val eutra = UEEutraCapabilityJson(eutraCapability)
            val (lteCategoryDL, lteCategoryUL) = getLTECategory(eutra)
            comboList.lteCategoryDL = lteCategoryDL
            comboList.lteCategoryUL = lteCategoryUL

            val bandList = getLteBands(eutra).associateBy({ it.band }, { it })
            comboList.nrNSAbands = getNrBands(eutra, true).sorted()
            comboList.nrSAbands = getNrBands(eutra, false).sorted()

            if (debug) {
                println(
                    "NR NSA bands (from eutra capability): [${comboList.nrNSAbands.joinToString(transform = {it.band.toString()})}]"
                )
                println(
                    "NR SA bands (from eutra capability): [${comboList.nrSAbands.joinToString(transform = {it.band.toString()})}]"
                )
            }

            val listCombo = getBandCombinations(eutra, bandList)
            val listComboAdd = getBandCombinationsAdd(eutra, bandList)
            val listComboReduced = getBandCombinationsReduced(eutra, bandList)
            val totalLteCombos = listCombo + listComboAdd + listComboReduced

            updateLteBandsCapabilities(bandList, totalLteCombos)

            comboList.lteCombos = totalLteCombos
            comboList.lteBands = bandList.values.sorted()

            if (eutraNrCapability != null) {
                // Don't parse lte features if no mrdc capability is available
                lteFeatures = getLteFeatureSet(eutra)
            }
        }

        if (nrCapability != null) {
            val nr = UENrCapabilityJson(nrCapability)
            val bandList = getNrBands(nr).sorted()
            if (debug) {
                bandList.forEach { println(it.bwsToString()) }
            }
            comboList.nrBands = bandList
            nrFeatures = getNRFeatureSet(nr)
            val featureSetCombination = getFeatureSetCombinations(nr)
            val saCombos = getNrBandCombinations(nr).typedList<ComboNr>()
            comboList.nrCombos =
                linkFeaturesAndCarrier(saCombos, featureSetCombination, null, nrFeatures)
                    .typedList()
            val nrDcCombos = getNrDcBandCombinations(nr, saCombos)
            val nrDcComboWithFeatures =
                linkFeaturesAndCarrier(nrDcCombos, featureSetCombination, null, nrFeatures)
                    .typedList<ComboNr>()
            comboList.nrDcCombos =
                nrDcComboWithFeatures.map { combo ->
                    val (fr2, fr1) = combo.masterComponents.partition { it.isFR2 }
                    ComboNrDc(fr1, fr2, combo.featureSet, combo.bcs)
                }
        }

        if (eutraNrCapability != null) {
            val mrdc = UEMrdcCapabilityJson(eutraNrCapability)
            val featureSetCombination = getFeatureSetCombinations(mrdc)
            val nsaCombos = getNrBandCombinations(mrdc)
            comboList.enDcCombos =
                linkFeaturesAndCarrier(nsaCombos, featureSetCombination, lteFeatures, nrFeatures)
                    .typedList()
        }
        return comboList
    }

    private fun getBandCombinations(
        eutraCapability: UEEutraCapabilityJson,
        bandList: Map<Int, ComponentLte>
    ): List<ComboLte> {
        val combinations =
            eutraCapability.eutraCapabilityV1020
                ?.getArrayAtPath("rf-Parameters-v1020.supportedBandCombination-r10")
                ?.mapNotNull { bandCombination ->
                    if (bandCombination is JsonArray) {
                        bandCombination.map { parseBandParameters(it, 10) }
                    } else {
                        null
                    }
                }
                ?: return emptyList()

        // Bands ext
        eutraCapability.eutraCapabilityV1090
            ?.getArrayAtPath("rf-Parameters-v1090.supportedBandCombination-v1090")
            ?.forEachIndexed { i, bandParameters ->
                if (bandParameters is JsonArray) {
                    bandParameters.forEachIndexed { j, bandParameter ->
                        bandParameter.getInt("bandEUTRA-v1090")?.let {
                            combinations[i][j].band = it
                        }
                    }
                }
            }

        // Some devices don't report 4layers in supportedMIMO-CapabilityDL-r10
        // Use CA-MIMO-ParametersDL-v10i0 if we haven't yet found any bands with 4rx or 8rx
        if (!combinations.hasHighMimo()) {
            eutraCapability.eutraCapabilityV10i0
                ?.getArrayAtPath("rf-Parameters-v10i0.supportedBandCombination-v10i0")
                ?.let { parseCaMimoV10i0(it, combinations) }
        }

        // Basic Modulation - set 256/64qam DL or 64/16qam UL from bandList
        setModulationFromBandList(combinations, bandList)

        // Advanced Modulation - set 1024qam DL or 256qam UL per combo
        eutraCapability.eutraCapabilityV1430
            ?.getArrayAtPath("rf-Parameters-v1430.supportedBandCombination-v1430")
            ?.let { set256qamUL(it, combinations) }
        eutraCapability.eutraCapabilityV1530
            ?.getArrayAtPath("rf-Parameters-v1530.supportedBandCombination-v1530")
            ?.let { set1024qam(it, combinations) }

        val supportedBandCombinationExtR10 =
            eutraCapability.eutraCapabilityV1060?.getArrayAtPath(
                "rf-Parameters-v1060.supportedBandCombinationExt-r10"
            )
                ?: emptyList()
        val bcsList =
            supportedBandCombinationExtR10.map { combinationParameters ->
                combinationParameters.getString("supportedBandwidthCombinationSet-r10")?.let {
                    BCS.fromBinaryString(it)
                }
                    ?: SingleBCS(0)
            }

        return combinations.mergeBcs(bcsList)
    }

    private fun getBandCombinationsAdd(
        eutraCapability: UEEutraCapabilityJson,
        bandList: Map<Int, ComponentLte>
    ): List<ComboLte> {
        val combinationsArray =
            eutraCapability.eutraCapabilityV1180?.getArrayAtPath(
                "rf-Parameters-v1180.supportedBandCombinationAdd-r11"
            )
                ?: return emptyList()
        val bcsList = mutableListWithCapacity<BCS>(combinationsArray.size)

        val combinations =
            combinationsArray.mapNotNull { bandCombination ->
                val bcs =
                    bandCombination.getString("supportedBandwidthCombinationSet-r11")
                        ?: "1" // 1 -> only bcs 0
                bcsList.add(BCS.fromBinaryString(bcs))
                val bandParametersList = bandCombination.getArrayAtPath("bandParameterList-r11")

                bandParametersList?.map { parseBandParameters(it, 11) }
            }

        // Some devices don't report 4layers in supportedMIMO-CapabilityDL-r10
        // Use CA-MIMO-ParametersDL-v10i0 if we haven't yet found any bands with 4rx or 8rx
        if (!combinations.hasHighMimo()) {
            eutraCapability.eutraCapabilityV11d0
                ?.getArrayAtPath("rf-Parameters-v11d0.supportedBandCombinationAdd-v11d0")
                ?.let { parseCaMimoV10i0(it, combinations) }
        }

        // Basic Modulation - set 256qam or 64qam from bandList
        setModulationFromBandList(combinations, bandList)

        // Advanced Modulation - set 1024qam DL or 256qam UL per combo
        eutraCapability.eutraCapabilityV1430
            ?.getArrayAtPath("rf-Parameters-v1430.supportedBandCombinationAdd-v1430")
            ?.let { set256qamUL(it, combinations) }
        eutraCapability.eutraCapabilityV1530
            ?.getArrayAtPath("rf-Parameters-v1530.supportedBandCombinationAdd-v1530")
            ?.let { set1024qam(it, combinations) }

        return combinations.mergeBcs(bcsList)
    }

    private fun getBandCombinationsReduced(
        eutraCapability: UEEutraCapabilityJson,
        bandList: Map<Int, ComponentLte>
    ): List<ComboLte> {
        val combinationsArray =
            eutraCapability.eutraCapabilityV1310?.getArrayAtPath(
                "rf-Parameters-v1310.supportedBandCombinationReduced-r13"
            )
                ?: return emptyList()
        val bcsList = mutableListWithCapacity<BCS>(combinationsArray.size)
        val combinations =
            combinationsArray.mapNotNull { bandCombination ->
                val bcs =
                    bandCombination.getString("supportedBandwidthCombinationSet-r13")
                        ?: "1" // 1 -> only bcs 0
                bcsList.add(BCS.fromBinaryString(bcs))

                val bandParametersList = bandCombination.getArray("bandParameterList-r13")
                bandParametersList?.map { parseBandParameters(it, 13) }
            }

        // Basic Modulation - set 256qam or 64qam from bandList
        setModulationFromBandList(combinations, bandList)

        // Advanced Modulation - set 1024qam DL or 256qam UL per combo
        eutraCapability.eutraCapabilityV1430
            ?.getArrayAtPath("rf-Parameters-v1430.supportedBandCombinationReduced-v1430")
            ?.let { set256qamUL(it, combinations) }
        eutraCapability.eutraCapabilityV1530
            ?.getArrayAtPath("rf-Parameters-v1530.supportedBandCombinationReduced-v1530")
            ?.let { set1024qam(it, combinations) }

        return combinations.mergeBcs(bcsList)
    }

    private fun setModulationFromBandList(
        combinations: List<List<ComponentLte>>,
        bandList: Map<Int, ComponentLte>
    ) {
        combinations.flatten().forEach {
            it.modDL = bandList[it.band]?.modDL ?: Modulation.NONE
            it.modUL = bandList[it.band]?.modUL ?: Modulation.NONE
        }
    }

    private fun set256qamUL(
        supportedBandCombinationV1430: JsonArray,
        combinations: List<List<ComponentLte>>
    ) {
        supportedBandCombinationV1430.forEachIndexed { i, bandParameterList ->
            bandParameterList.getArray("bandParameterList-v1430")?.forEachIndexed { j, bandParameter
                ->
                if (bandParameter.getString("ul-256QAM-r14") != null) {
                    combinations[i][j].modUL = Modulation.QAM256
                } else {
                    bandParameter
                        .getArray("ul-256QAM-perCC-InfoList-r14")
                        ?.firstOrNull()
                        ?.getString("ul-256QAM-perCC-r14")
                        ?.let {
                            // TODO: Handle modulation per CC
                            combinations[i][j].modUL = Modulation.QAM256
                        }
                }
            }
        }
    }

    private fun set1024qam(
        supportedBandCombinationV1530: JsonArray,
        combinations: List<List<ComponentLte>>
    ) {
        supportedBandCombinationV1530.forEachIndexed { i, bandParameterList ->
            bandParameterList.getArray("bandParameterList-v1530")?.forEachIndexed { j, bandParameter
                ->
                if (bandParameter.getString("dl-1024QAM-r15") != null) {
                    combinations[i][j].modDL = Modulation.QAM1024
                }
            }
        }
    }

    private fun parseCaMimoV10i0(
        supportedBandCombinationV10i0: List<JsonElement>,
        combinations: List<List<ComponentLte>>
    ) {
        supportedBandCombinationV10i0.forEachIndexed { i, bandParameterList ->
            bandParameterList.getArray("bandParameterList-v10i0")?.forEachIndexed { j, it ->
                it.getArray("bandParametersDL-v10i0")
                    ?.get(0)
                    ?.getString("fourLayerTM3-TM4-r10")
                    ?.let { combinations[i][j].mimoDL = 4 }
            }
        }
    }

    private fun updateLteBandsCapabilities(
        bandList: Map<Int, ComponentLte>,
        listCombo: List<ComboLte>
    ) {
        val lteComponents = listCombo.flatMap { it.masterComponents.toList() }.toHashSet()
        lteComponents.forEach {
            val band = bandList[it.band]
            if (band != null) {
                if (it.mimoDL > band.mimoDL) {
                    band.mimoDL = it.mimoDL
                }
                if (it.mimoUL > band.mimoUL) {
                    band.mimoUL = it.mimoUL
                }
                if (it.modDL > band.modDL) {
                    band.modDL = it.modDL
                }
                if (it.modUL > band.modUL) {
                    band.modUL = it.modUL
                }
            }
        }
    }

    private fun getLTECategory(eutraCapability: UEEutraCapabilityJson): Pair<Int, Int> {
        var dlCategory = 0
        var ulCategory = 0

        // ue-Category
        eutraCapability.rootJson.getInt("ue-Category")?.let {
            dlCategory = it
            ulCategory = it
        }

        // ue-Category-v1020
        eutraCapability.eutraCapabilityV1020?.getInt("ue-Category-v1020")?.let {
            dlCategory = it
            ulCategory = it
        }

        // ue-Category-v1170
        eutraCapability.eutraCapabilityV1170?.getInt("ue-Category-v1170")?.let {
            dlCategory = it
            ulCategory = it
        }

        // ue-Category-v11a0
        eutraCapability.eutraCapabilityV11a0?.getInt("ue-Category-v11a0")?.let {
            dlCategory = it
            ulCategory = it
        }

        // ue-Category-v1250
        eutraCapability.eutraCapabilityV1250?.getInt("ue-CategoryDL-r12")?.let { dlCategory = it }
        eutraCapability.eutraCapabilityV1250?.getInt("ue-CategoryUL-r12")?.let { ulCategory = it }

        // ue-Category-v1260
        eutraCapability.eutraCapabilityV1260?.getInt("ue-CategoryDL-v1260")?.let { dlCategory = it }

        // ue-Category-v1310
        eutraCapability.eutraCapabilityV1310?.getString("ue-CategoryDL-v1310")?.let {
            // n17, m1
            dlCategory = it.drop(1).toInt()
        }
        eutraCapability.eutraCapabilityV1310?.getString("ue-CategoryUL-v1310")?.let {
            // n14, m1
            ulCategory = it.drop(1).toInt()
        }

        // ue-Category-v1330
        eutraCapability.eutraCapabilityV1330?.getInt("ue-CategoryDL-v1330")?.let { dlCategory = it }

        // ue-Category-v1340
        eutraCapability.eutraCapabilityV1340?.getInt("ue-CategoryUL-v1340")?.let { ulCategory = it }

        // ue-Category-v1350
        eutraCapability.eutraCapabilityV1350?.getInt("ue-CategoryDL-v1350")?.let { dlCategory = it }
        eutraCapability.eutraCapabilityV1350?.getInt("ue-CategoryUL-v1350")?.let { ulCategory = it }

        // ue-Category-v1430
        eutraCapability.eutraCapabilityV1430?.getString("ue-CategoryDL-v1430")?.let {
            // m2
            dlCategory = it.drop(1).toInt()
        }
        eutraCapability.eutraCapabilityV1430?.getString("ue-CategoryUL-v1430")?.let {
            // n16, n17, n18, n19, n20, m2
            ulCategory = it.drop(1).toInt()
        }
        eutraCapability.eutraCapabilityV1430?.getString("ue-CategoryUL-v1430b")?.let {
            // n21
            ulCategory = it.drop(1).toInt()
        }

        // ue-Category-v1450
        eutraCapability.eutraCapabilityV1450?.getInt("ue-CategoryDL-v1450")?.let { dlCategory = it }

        // ue-Category-v1460
        eutraCapability.eutraCapabilityV1460?.getInt("ue-CategoryDL-v1460")?.let { dlCategory = it }

        // ue-Category-v1530
        eutraCapability.eutraCapabilityV1530?.getInt("ue-CategoryDL-v1530")?.let { dlCategory = it }
        eutraCapability.eutraCapabilityV1530?.getInt("ue-CategoryUL-v1530")?.let { ulCategory = it }

        return Pair(dlCategory, ulCategory)
    }

    private fun getLteBands(eutraCapability: UEEutraCapabilityJson): List<ComponentLte> {
        val supportedBandListEutra =
            eutraCapability.rootJson.getArrayAtPath("rf-Parameters.supportedBandListEUTRA")

        val lteBands =
            supportedBandListEutra?.mapNotNull {
                it.getInt("bandEUTRA")?.let { band ->
                    val duplex = DuplexBandTable.getLteDuplex(band)
                    val defaultModUL =
                        if (duplex == Duplex.SDL) Modulation.NONE else Modulation.QAM16
                    val mimoUl = if (duplex == Duplex.SDL) 0 else 1

                    ComponentLte(
                        band,
                        mimoDL = 2,
                        mimoUL = mimoUl,
                        modDL = Modulation.QAM64,
                        modUL = defaultModUL
                    )
                }
            }
                ?: return emptyList()

        eutraCapability.eutraCapabilityV9e0
            ?.getArrayAtPath("rf-Parameters-v9e0.supportedBandListEUTRA-v9e0")
            ?.forEachIndexed { i, it ->
                it.getInt("bandEUTRA-v9e0")?.let { band -> lteBands[i].band = band }
            }

        eutraCapability.eutraCapabilityV1250
            ?.getArrayAtPath("rf-Parameters-v1250.supportedBandListEUTRA-v1250")
            ?.forEachIndexed { i, it ->
                if (it.getString("dl-256QAM-r12") != null) {
                    lteBands[i].modDL = Modulation.QAM256
                }
                if (it.getString("ul-64QAM-r12") != null) {
                    lteBands[i].modUL = Modulation.QAM64
                }
            }

        return lteBands
    }

    private fun getNrBands(
        eutraCapability: UEEutraCapabilityJson,
        endc: Boolean
    ): List<BandNrDetails> {

        val supportedBandListNR =
            if (endc) {
                eutraCapability.eutraCapabilityV1510?.getArrayAtPath(
                    "irat-ParametersNR-r15.supportedBandListEN-DC-r15"
                )
            } else {
                eutraCapability.eutraCapabilityV1540?.getArrayAtPath(
                    "irat-ParametersNR-v1540.supportedBandListNR-SA-r15"
                )
            }

        return supportedBandListNR?.mapNotNull {
            it.getInt("bandNR-r15")?.let { band -> BandNrDetails(band) }
        }
            ?: emptyList()
    }

    private fun List<List<ComponentLte>>.hasHighMimo() = any { bands ->
        bands.any { it.mimoDL > 2 }
    }

    private fun List<List<ComponentLte>>.mergeBcs(bcsList: List<BCS>) =
        zip(bcsList) { bands, bcs -> ComboLte(bands.sortedDescending(), bcs) }

    private fun linkFeaturesAndCarrier(
        combos: List<ICombo>,
        featureSetCombinations: List<List<List<FeatureIndex>>>,
        lteFeatures: FeatureSets?,
        nrFeatures: FeatureSets?
    ): List<ICombo> {
        val list = mutableListWithCapacity<ICombo>(combos.size)

        for (combo in combos) {
            val featureSetsPerBand = featureSetCombinations.getOrNull(combo.featureSet) ?: continue
            val indices = featureSetsPerBand.firstOrNull()?.indices ?: IntRange.EMPTY
            val mergedCombos =
                indices.map { index ->
                    mergeComboNrAndIndexedFeature(
                        combo,
                        featureSetsPerBand,
                        index,
                        nrFeatures,
                        lteFeatures
                    )
                }
            list.addAll(mergedCombos)
        }
        return list
    }

    private fun mergeComboNrAndIndexedFeature(
        combo: ICombo,
        featureSetsPerBand: List<List<FeatureIndex>>,
        index: Int,
        nrFeatures: FeatureSets?,
        lteFeatures: FeatureSets?
    ): ICombo {
        val newNrComponents: MutableList<ComponentNr>
        val newLteComponents: MutableList<ComponentLte>

        val lteIterator: Iterator<ComponentLte>
        val nrIterator: Iterator<ComponentNr>

        when (combo) {
            is ComboEnDc -> {
                lteIterator = combo.componentsLte.iterator()
                nrIterator = combo.componentsNr.iterator()
                newLteComponents = mutableListWithCapacity(combo.componentsLte.size)
                newNrComponents = mutableListWithCapacity(combo.componentsNr.size)
            }
            is ComboNr -> {
                lteIterator = emptyList<ComponentLte>().iterator()
                nrIterator = combo.componentsNr.iterator()
                newLteComponents = mutableListOf()
                newNrComponents = mutableListWithCapacity(combo.componentsNr.size)
            }
            else -> {
                throw IllegalArgumentException("Unsupported combo type ${combo.javaClass}")
            }
        }

        for (featureSetBand in featureSetsPerBand) {
            val featureSet = featureSetBand[index]
            val oldComponent: IComponent
            val features: FeatureSets?

            if (featureSet.isNR) {
                oldComponent = nrIterator.next()
                features = nrFeatures
            } else {
                oldComponent = lteIterator.next()
                features = lteFeatures
            }

            val newComponent = mergeComponentAndFeature(featureSet, oldComponent, features)

            if (newComponent is ComponentLte) {
                newLteComponents.add(newComponent)
            } else if (newComponent is ComponentNr) {
                newNrComponents.add(newComponent)
            }
        }

        newNrComponents.sortDescending()

        return if (newLteComponents.isNotEmpty()) {
            newLteComponents.sortDescending()
            val comboEnDc = combo as ComboEnDc
            val (bcsNr, bcsEutra, bcsIntraEnDc) =
                mergeAndSplitEnDcBCS(
                    newLteComponents,
                    newNrComponents,
                    comboEnDc.bcsNr,
                    comboEnDc.bcsEutra,
                    combo.bcsIntraEnDc
                )
            combo.copy(
                masterComponents = newLteComponents,
                secondaryComponents = newNrComponents,
                bcsNr = bcsNr,
                bcsEutra = bcsEutra,
                bcsIntraEnDc = bcsIntraEnDc
            )
        } else {
            (combo as ComboNr).copy(masterComponents = newNrComponents)
        }
    }

    private fun mergeAndSplitEnDcBCS(
        lteComponents: List<ComponentLte>,
        nrComponents: List<ComponentNr>,
        bcsNr: BCS,
        bcsEutra: BCS,
        bcsIntraEnDc: BCS
    ): Triple<BCS, BCS, BCS> {
        val intraBandEnDC =
            nrComponents.any { nr -> lteComponents.any { lte -> nr.band == lte.band } }
        val interBandLte =
            !intraBandEnDC ||
                lteComponents.drop(1).any { it.band != lteComponents.firstOrNull()?.band }
        val interBandNr =
            !intraBandEnDC ||
                nrComponents.drop(1).any { it.band != nrComponents.firstOrNull()?.band }

        return if (!intraBandEnDC) {
            // Don't set bcsIntraEnDc for ENDC Combos without any intraEnDc component
            Triple(bcsNr, bcsEutra, EmptyBCS)
        } else if (!interBandLte && !interBandNr) {
            // intraBandEnDc without additional interBand only has intraEnDc bcs
            // Set it to the max between bcsNr and bcsIntraEnDc to handle cases
            // where bcsIntraEnDc is missing
            Triple(EmptyBCS, EmptyBCS, maxOf(bcsIntraEnDc, bcsNr))
        } else {
            // interBand + intraBand, set all BCS available
            Triple(bcsNr, bcsEutra, bcsIntraEnDc)
        }
    }

    private fun mergeComponentAndFeature(
        featureSet: FeatureIndex,
        component: IComponent,
        features: FeatureSets?
    ): IComponent? {
        val dlIndex = featureSet.downlinkIndex - 1
        val ulIndex = featureSet.uplinkIndex - 1

        if (dlIndex < 0 && ulIndex < 0) {
            // Fallback combination
            return null
        }

        // TODO: Features per CC
        val dlFeature =
            if (dlIndex >= 0) features?.downlink?.get(dlIndex)?.featureSetsPerCC?.first() else null
        val ulFeature =
            if (ulIndex >= 0) features?.uplink?.get(ulIndex)?.featureSetsPerCC?.first() else null

        if (
            features == null ||
                dlFeature == null && dlIndex >= 0 ||
                ulFeature == null && ulIndex >= 0
        ) {
            // Return a copy without any editing
            return component.clone()
        }

        return if (featureSet.isNR) {
            mergeComponentNrAndFeature(
                component as ComponentNr,
                dlFeature as? FeaturePerCCNr,
                ulFeature as? FeaturePerCCNr
            )
        } else {
            mergeComponentLteAndFeature(component as ComponentLte, dlFeature, ulFeature)
        }
    }
    private fun mergeComponentLteAndFeature(
        component: ComponentLte,
        dlFeature: IFeaturePerCC?,
        ulFeature: IFeaturePerCC?
    ): ComponentLte {
        val componentLte = component.copy()

        if (dlFeature != null) {
            componentLte.mimoDL = dlFeature.mimo
        } else {
            // only UL
            componentLte.classDL = BwClass.NONE
            componentLte.mimoDL = 0
        }

        if (ulFeature != null) {
            componentLte.mimoUL = ulFeature.mimo
            componentLte.modUL = ulFeature.qam
        } else {
            // only DL
            componentLte.classUL = BwClass.NONE
            componentLte.mimoUL = 0
        }
        return componentLte
    }

    private fun mergeComponentNrAndFeature(
        component: ComponentNr,
        dlFeature: FeaturePerCCNr?,
        ulFeature: FeaturePerCCNr?
    ): ComponentNr {
        val componentNr = component.copy()
        if (dlFeature != null) {
            componentNr.mimoDL = dlFeature.mimo
            componentNr.modDL = dlFeature.qam
            componentNr.maxBandwidth = dlFeature.bw
            componentNr.channelBW90mhz = dlFeature.bw >= 80 && dlFeature.channelBW90mhz
            componentNr.scs = dlFeature.scs
        } else {
            // only UL
            componentNr.classDL = BwClass.NONE
            componentNr.mimoDL = 0
        }

        if (ulFeature != null) {
            componentNr.mimoUL = ulFeature.mimo
            componentNr.modUL = ulFeature.qam
        } else {
            // only DL
            componentNr.classUL = BwClass.NONE
            componentNr.mimoUL = 0
        }
        return componentNr
    }

    private fun getFeatureSetCombinations(
        nrCapability: UENrRrcCapabilityJson
    ): List<List<List<FeatureIndex>>> {
        val featureSetCombinations = nrCapability.rootJson.getArray("featureSetCombinations")
        val list =
            featureSetCombinations?.mapNotNull { featureSetCombination ->
                (featureSetCombination as? JsonArray)?.mapNotNull { featureSetsPerBand ->
                    (featureSetsPerBand as? JsonArray)?.mapNotNull { featureSet ->
                        val eutra = featureSet.getObject("eutra")
                        val nr = featureSet.getObject("nr")
                        if (nr != null) {
                            val dl = nr.getInt("downlinkSetNR") ?: 0
                            val ul = nr.getInt("uplinkSetNR") ?: 0

                            FeatureIndex(true, dl, ul)
                        } else if (eutra != null) {
                            val dl = eutra.getInt("downlinkSetEUTRA") ?: 0
                            val ul = eutra.getInt("uplinkSetEUTRA") ?: 0

                            FeatureIndex(false, dl, ul)
                        } else {
                            null
                        }
                    }
                }
            }
        return list ?: emptyList()
    }

    private fun getNrBandCombinations(nrCapability: UENrRrcCapabilityJson): List<ICombo> {
        val endc = nrCapability is UEMrdcCapabilityJson
        val bandCombinationsPath =
            if (endc) {
                "rf-ParametersMRDC.supportedBandCombinationList"
            } else {
                "rf-Parameters.supportedBandCombinationList"
            }
        val bandCombinationsListv1590 =
            nrCapability.rootJson
                .getArrayAtPath("rf-ParametersMRDC.supportedBandCombinationList-v1590")
                ?.iterator()
        val bandCombinationsList = nrCapability.rootJson.getArrayAtPath(bandCombinationsPath)
        val list =
            bandCombinationsList?.mapNotNull { bandCombination ->
                val bandList = bandCombination.getArray("bandList") ?: return@mapNotNull null
                val lteBands = mutableListWithCapacity<ComponentLte>(bandList.size)
                val nrBands = mutableListWithCapacity<ComponentNr>(bandList.size)
                for (bandParameters in bandList) {
                    val component = parse5gBandParameters(bandParameters)
                    if (component is ComponentNr) {
                        nrBands.add(component)
                    } else if (component is ComponentLte) {
                        lteBands.add(component)
                    }
                }
                val featureSetCombination = bandCombination.getInt("featureSetCombination") ?: 0
                val bcsString = bandCombination.getString("supportedBandwidthCombinationSet") ?: "1"
                val bcs = BCS.fromBinaryString(bcsString)
                if (endc) {
                    val bcsEutraString =
                        bandCombination
                            .getObject("ca-ParametersEUTRA")
                            ?.getString("supportedBandwidthCombinationSetEUTRA-v1530")
                            ?: "1"
                    val bcsEutra = BCS.fromBinaryString(bcsEutraString)
                    val bcsIntraEnDcString =
                        bandCombinationsListv1590
                            ?.next()
                            ?.getString("supportedBandwidthCombinationSetIntraENDC")
                            ?: "1"
                    val bcsIntraEnDc = BCS.fromBinaryString(bcsIntraEnDcString)
                    ComboEnDc(lteBands, nrBands, featureSetCombination, bcs, bcsEutra, bcsIntraEnDc)
                } else {
                    ComboNr(nrBands, featureSetCombination, bcs)
                }
            }
        return list ?: emptyList()
    }

    private fun parse5gBandParameters(bandParameters: JsonElement): IComponent? {
        val nr = bandParameters.getObject("nr")
        if (nr != null) {
            val band = nr.getInt("bandNR") ?: 0
            val dlClass = BwClass.valueOf(nr.getString("ca-BandwidthClassDL-NR"))
            val ulClass = BwClass.valueOf(nr.getString("ca-BandwidthClassUL-NR"))
            return ComponentNr(band, dlClass, ulClass)
        }

        val lte = bandParameters.getObject("eutra")
        if (lte != null) {
            val band = lte.getInt("bandEUTRA") ?: 0
            val dlClass = BwClass.valueOf(lte.getString("ca-BandwidthClassDL-EUTRA"))
            val ulClass = BwClass.valueOf(lte.getString("ca-BandwidthClassUL-EUTRA"))
            return ComponentLte(band, dlClass, ulClass)
        }
        return null
    }

    private fun getNrDcBandCombinations(
        nrCapability: UENrCapabilityJson,
        nrCombos: List<ComboNr>
    ): List<ComboNr> {
        val bandCombinationsPath = "rf-Parameters.supportedBandCombinationList-v1560"
        val bandCombinationsList = nrCapability.rootJson.getArrayAtPath(bandCombinationsPath)

        val list =
            bandCombinationsList?.mapIndexedNotNull { i, bandCombination ->
                val nrCombo = nrCombos.getOrNull(i)
                val featureSet =
                    bandCombination
                        .getObject("ca-ParametersNRDC")
                        ?.getInt("featureSetCombinationDC")

                if (nrCombo == null || featureSet == null) {
                    return@mapIndexedNotNull null
                }
                nrCombo.copy(featureSet = featureSet)
            }
                ?: emptyList()
        return list
    }

    private fun getNrBands(nrCapability: UENrCapabilityJson): List<BandNrDetails> {
        return nrCapability.rootJson
            .getArrayAtPath("rf-Parameters.supportedBandListNR")
            ?.mapNotNull { supportedBandNr ->
                val componentNr =
                    supportedBandNr.getInt("bandNR")?.let { BandNrDetails(it) }
                        ?: return@mapNotNull null

                val duplex = DuplexBandTable.getNrDuplex(componentNr.band)
                if (componentNr.isFR2 && supportedBandNr.getString("pdsch-256QAM-FR2") == null) {
                    componentNr.modDL = Modulation.QAM64
                } else if (duplex != Duplex.SUL) { // this is ok because No fr2 is SUL
                    componentNr.modDL = Modulation.QAM256
                }

                if (supportedBandNr.getString("pusch-256QAM") != null) {
                    componentNr.modUL = Modulation.QAM256
                } else if (duplex != Duplex.SDL) {
                    componentNr.modUL = Modulation.QAM64
                }

                supportedBandNr.getString("ue-PowerClass")?.removePrefix("pc")?.toInt()?.let {
                    componentNr.powerClass = it
                }

                if (supportedBandNr.getString("rateMatchingLTE-CRS") != null) {
                    componentNr.rateMatchingLteCrs = true
                }

                val maxUplinkDutyCycleKey =
                    if (componentNr.isFR2) {
                        "maxUplinkDutyCycle-FR2"
                    } else {
                        "maxUplinkDutyCycle-PC2-FR1"
                    }
                supportedBandNr.getString(maxUplinkDutyCycleKey)?.removePrefix("n")?.toInt()?.let {
                    componentNr.maxUplinkDutyCycle = it
                }

                parseNRChannelBWs(supportedBandNr, componentNr)

                componentNr
            }
            ?.toList()
            ?: emptyList()
    }

    private fun parseNRChannelBWs(supportedBandNr: JsonElement, componentNr: BandNrDetails) {
        val channelBWsDL = supportedBandNr.getObject("channelBWs-DL")
        val channelBWsUL = supportedBandNr.getObject("channelBWs-UL")
        val channelBWsDlV1590 = supportedBandNr.getObject("channelBWs-DL-v1590")
        val channelBWsUlV1590 = supportedBandNr.getObject("channelBWs-UL-v1590")

        val bandwidthsDL = parseNrBw(channelBWsDL, componentNr, false)
        val bandwidthsUL = parseNrBw(channelBWsUL, componentNr, false)

        val scsRange =
            if (componentNr.isFR2) {
                60..120 step { it * 2 }
            } else {
                15..60 step { it * 2 }
            }

        /*
         * According to TS 38.306 v16.6.0, the UE can omit channelBWs or specific SCS in channelBWs
         * if it supports all BWs defined in 38.101-1 and 38.101-2 v15.7.0.
         * So we add default BWs here when a specific SCS is missing.
         */
        for (scs in scsRange) {
            val bws = BwTableNr.getDLBws(componentNr.band, scs)
            if (!bandwidthsDL.containsKey(scs)) {
                bandwidthsDL[scs] = bws.bwsDL
            }
            if (!bandwidthsUL.containsKey(scs)) {
                bandwidthsUL[scs] = bws.bwsUL
            }
        }

        bandwidthsDL.merge(parseNrBw(channelBWsDlV1590, componentNr, true))
        bandwidthsUL.merge(parseNrBw(channelBWsUlV1590, componentNr, true))

        // Sort bws array and add to bwsList
        val bwsList = mutableListWithCapacity<BwsNr>(scsRange.count())
        for (scs in scsRange) {
            val bwsDl = bandwidthsDL[scs] ?: intArrayOf()
            var bwsUl = bandwidthsUL[scs] ?: intArrayOf()

            /* Don't add empty bws */
            if (bwsDl.isEmpty() && bwsUl.isEmpty()) {
                continue
            }

            /* if equal, preserve only one array */
            if (bwsDl.contentEquals(bwsUl)) {
                bwsUl = bwsDl
                bwsDl.sortDescending()
            } else {
                bwsDl.sortDescending()
                bwsUl.sortDescending()
            }

            bwsList.add(BwsNr(scs, bwsDl, bwsUl))
        }

        componentNr.bandwidths = bwsList.toTypedArray()
    }

    private fun parseNrBw(
        channelBWs: JsonObject?,
        componentNr: BandNrDetails,
        isV1590: Boolean = false
    ): BwMap {
        val freqRange = if (componentNr.isFR2) "fr2" else "fr1"

        val bandWidthMap = mutableMapOf<Int, IntArray>()
        channelBWs?.getObject(freqRange)?.forEach { (scsKey, element) ->
            val scs = scsKey.removePrefix("scs-").removeSuffix("kHz").toInt()
            (element as? JsonPrimitive)?.contentOrNull?.let { bwString ->
                bandWidthMap[scs] = BwsBitMap(bwString, componentNr.band, scs, isV1590).bws
            }
        }
        return bandWidthMap
    }

    private fun getLteFeatureSet(eutraCapability: UEEutraCapabilityJson): FeatureSets {
        var downlink = emptyList<FeatureSet>()
        var uplink = emptyList<FeatureSet>()

        eutraCapability.eutraCapabilityV1510?.getObject("featureSetsEUTRA-r15")?.let { featureSets
            ->
            val downlinkPerCC =
                featureSets.getArray("featureSetsDL-PerCC-r15")?.map {
                    val qam = Modulation.QAM256
                    val mimoLayers = it.getString("supportedMIMO-CapabilityDL-MRDC-r15")
                    val mimo = maxOf(Mimo.fromLiteral(mimoLayers), 2)
                    FeaturePerCCLte(mimo = mimo, qam = qam)
                }

            downlink =
                featureSets.getArray("featureSetsDL-r15")?.mapNotNull { featureSetPerCCList ->
                    val list =
                        featureSetPerCCList.getArray("featureSetPerCC-ListDL-r15")?.mapNotNull {
                            index ->
                            (index as? JsonPrimitive)?.intOrNull?.let {
                                downlinkPerCC?.getOrNull(it)
                            }
                        }
                    if (list != null) {
                        FeatureSet(list, LinkDirection.DOWNLINK)
                    } else {
                        null
                    }
                }
                    ?: downlink

            val uplinkPerCC =
                featureSets.getArray("featureSetsUL-PerCC-r15")?.map {
                    val qam =
                        if (it.getString("ul-256QAM-r15") != null) {
                            Modulation.QAM256
                        } else {
                            Modulation.QAM64
                        }
                    val mimoLayers = it.getString("supportedMIMO-CapabilityUL-r15")
                    val mimo = maxOf(Mimo.fromLiteral(mimoLayers), 1)
                    FeaturePerCCLte(LinkDirection.UPLINK, mimo = mimo, qam = qam)
                }

            // featureSets.getArray("featureSetsDL-v1550") - Never seen on the wild
            uplink =
                featureSets.getArray("featureSetsUL-r15")?.mapNotNull { featureSetPerCCList ->
                    val list =
                        featureSetPerCCList.getArray("featureSetPerCC-ListUL-r15")?.mapNotNull {
                            index ->
                            (index as? JsonPrimitive)?.intOrNull?.let { uplinkPerCC?.getOrNull(it) }
                        }
                    if (list != null) {
                        FeatureSet(list, LinkDirection.UPLINK)
                    } else {
                        null
                    }
                }
                    ?: uplink
        }

        if (debug) {
            println("\nLTE FeatureSets")
            println(downlink.joinToString(separator = "\n"))
            println(uplink.joinToString(separator = "\n"))
        }
        return FeatureSets(downlink, uplink)
    }

    private fun getNRFeatureSet(nrCapability: UENrCapabilityJson): FeatureSets {
        var downlink = emptyList<FeatureSet>()
        var uplink = emptyList<FeatureSet>()

        nrCapability.rootJson.getObject("featureSets")?.let { featureSets ->
            val downlinkPerCC =
                featureSets.getArray("featureSetsDownlinkPerCC")?.map {
                    val scs =
                        it.getString("supportedSubcarrierSpacingDL")
                            ?.removePrefix("kHz")
                            ?.split("-")
                            ?.first()
                            ?.toIntOrNull()
                            ?: 0
                    val supportedBandwidthDL = it.getObject("supportedBandwidthDL")
                    val bwFr1OrFr2 =
                        supportedBandwidthDL?.getString("fr1")
                            ?: supportedBandwidthDL?.getString("fr2")
                    val bw = bwFr1OrFr2?.removePrefix("mhz")?.toIntOrNull() ?: 0
                    val channelBW90mhz = it.getString("channelBW-90mhz") == "true"
                    val mimoLayers = it.getString("maxNumberMIMO-LayersPDSCH")
                    val mimo = maxOf(Mimo.fromLiteral(mimoLayers), 2)
                    val qam = Modulation.of(it.getString("supportedModulationOrderDL"))

                    FeaturePerCCNr(
                        mimo = mimo,
                        qam = qam,
                        scs = scs,
                        bw = bw,
                        channelBW90mhz = channelBW90mhz
                    )
                }

            downlink =
                featureSets.getArray("featureSetsDownlink")?.mapNotNull { featureSetPerCCList ->
                    val list =
                        featureSetPerCCList.getArray("featureSetListPerDownlinkCC")?.mapNotNull {
                            index ->
                            (index as? JsonPrimitive)?.intOrNull?.let {
                                // NR PerCC-ID is 1..1024 while LTE PerCC-ID is 0..32
                                downlinkPerCC?.getOrNull(it - 1)
                            }
                        }
                    if (list != null) {
                        FeatureSet(list, LinkDirection.DOWNLINK)
                    } else {
                        null
                    }
                }
                    ?: downlink

            val uplinkPerCC =
                featureSets.getArray("featureSetsUplinkPerCC")?.map {
                    val scs =
                        it.getString("supportedSubcarrierSpacingUL")
                            ?.removePrefix("kHz")
                            ?.split("-")
                            ?.first()
                            ?.toIntOrNull()
                            ?: 0
                    val supportedBandwidthUL = it.getObject("supportedBandwidthUL")
                    val bwFr1OrFr2 =
                        supportedBandwidthUL?.getString("fr1")
                            ?: supportedBandwidthUL?.getString("fr2")
                    val bw = bwFr1OrFr2?.removePrefix("mhz")?.toIntOrNull() ?: 0
                    val channelBW90mhz = it.getString("channelBW-90mhz") == "true"

                    val mimoCbLayers =
                        it.getObject("mimo-CB-PUSCH")?.getString("maxNumberMIMO-LayersCB-PUSCH")
                    val mimoNonCbLayers = it.getString("maxNumberMIMO-LayersNonCB-PUSCH")

                    val mimo =
                        maxOf(Mimo.fromLiteral(mimoCbLayers), Mimo.fromLiteral(mimoNonCbLayers), 1)
                    val qam = Modulation.of(it.getString("supportedModulationOrderUL"))
                    FeaturePerCCNr(
                        type = LinkDirection.UPLINK,
                        mimo = mimo,
                        qam = qam,
                        scs = scs,
                        bw = bw,
                        channelBW90mhz = channelBW90mhz
                    )
                }

            uplink =
                featureSets.getArray("featureSetsUplink")?.mapNotNull { featureSetPerCCList ->
                    val list =
                        featureSetPerCCList.getArray("featureSetListPerUplinkCC")?.mapNotNull {
                            index ->
                            (index as? JsonPrimitive)?.intOrNull?.let {
                                // NR PerCC-ID is 1..1024 while LTE PerCC-ID is 0..32
                                uplinkPerCC?.getOrNull(it - 1)
                            }
                        }
                    if (list != null) {
                        FeatureSet(list, LinkDirection.UPLINK)
                    } else {
                        null
                    }
                }
                    ?: uplink
        }

        if (debug) {
            println("\nNr FeatureSets")
            println(downlink.joinToString(separator = "\n"))
            println(uplink.joinToString(separator = "\n"))
        }
        return FeatureSets(downlink, uplink)
    }

    private fun parseBandParametersDL(
        bandParameters: JsonElement,
        release: Int
    ): Pair<BwClass, Int> {
        val bandParametersDL =
            if (release == 13) {
                bandParameters.getObject("bandParametersDL-r13")
            } else {
                bandParameters.getArrayAtPath("bandParametersDL-r$release")?.first()
            }

        if (bandParametersDL == null) {
            return Pair(BwClass.NONE, 0)
        }

        // both r10 and r11 uses ca-BandwidthClassDL/supportedMIMO-CapabilityDL -r10
        val subRelease = if (release == 13) "13" else "10"
        val dlClassString = bandParametersDL.getString("ca-BandwidthClassDL-r$subRelease")
        val dlClass = BwClass.valueOf(dlClassString)
        val mimoLayers = bandParametersDL.getString("supportedMIMO-CapabilityDL-r$subRelease")
        var dlMimo = Mimo.fromLiteral(mimoLayers)

        // Some devices only reports fourLayerTM3-TM4-rXX or only reports 4rx in
        // fourLayerTM3-TM4-rXX
        // For r11 and r10 the check is done in parseCaMimoV10i0()
        if (release == 13 && dlMimo < 4) {
            bandParametersDL.getString("fourLayerTM3-TM4-r13")?.let { dlMimo = 4 }
        }

        // Some devices don't report supportedMIMO-CapabilityDL-rXX for twoLayers
        if (dlClass != BwClass.NONE && dlMimo == 0) {
            dlMimo = 2
        }

        return Pair(dlClass, dlMimo)
    }

    private fun parseBandParametersUL(
        bandParameters: JsonElement,
        release: Int
    ): Pair<BwClass, Int> {
        val bandParametersUL =
            if (release == 13) {
                bandParameters.getObject("bandParametersUL-r13")
            } else {
                bandParameters.getArrayAtPath("bandParametersUL-r$release")?.first()
            }

        if (bandParametersUL == null) {
            return Pair(BwClass.NONE, 0)
        }

        // both r10 and r11 uses ca-BandwidthClassUL/supportedMIMO-CapabilityUL -r10
        val subRelease = if (release == 13) "13" else "10"

        val ulClassString = bandParametersUL.getString("ca-BandwidthClassUL-r10")
        val ulClass = BwClass.valueOf(ulClassString)
        val mimoLayers = bandParametersUL.getString("supportedMIMO-CapabilityUL-r$subRelease")
        var ulMimo = Mimo.fromLiteral(mimoLayers)
        if (ulMimo == 0) {
            // supportedMIMO-CapabilityUL isn't reported if ulMimo = 1
            ulMimo = 1
        }

        return Pair(ulClass, ulMimo)
    }

    private fun parseBandParameters(bandParameters: JsonElement, release: Int): ComponentLte {
        val band = bandParameters.getInt("bandEUTRA-r$release") ?: 0
        val (dlClass, dlMimo) = parseBandParametersDL(bandParameters, release)
        val (ulClass, ulMimo) = parseBandParametersUL(bandParameters, release)
        return ComponentLte(band, dlClass, ulClass, dlMimo, ulMimo)
    }
}
