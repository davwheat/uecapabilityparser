package it.smartphonecombo.uecapabilityparser.util

import com.ericsson.mts.asn1.ASN1Converter
import com.ericsson.mts.asn1.ASN1Translator
import com.ericsson.mts.asn1.KotlinJsonFormatWriter
import com.ericsson.mts.asn1.PERTranslatorFactory
import com.ericsson.mts.asn1.converter.AbstractConverter
import it.smartphonecombo.uecapabilityparser.extension.decodeHex
import it.smartphonecombo.uecapabilityparser.extension.getArrayAtPath
import it.smartphonecombo.uecapabilityparser.extension.getString
import it.smartphonecombo.uecapabilityparser.extension.mutableListWithCapacity
import it.smartphonecombo.uecapabilityparser.extension.preformatHex
import it.smartphonecombo.uecapabilityparser.importer.ImportCapabilities
import it.smartphonecombo.uecapabilityparser.model.Capabilities
import it.smartphonecombo.uecapabilityparser.model.Rat
import it.smartphonecombo.uecapabilityparser.model.combo.ComboEnDc
import it.smartphonecombo.uecapabilityparser.model.combo.ComboNr
import it.smartphonecombo.uecapabilityparser.model.combo.ComboNrDc
import java.io.*
import kotlinx.serialization.json.*

/** The Class Utility. */
object Utility {

    fun split0xB826hex(input: String): List<String> {
        fun String.emptyLineIndex(): Int {
            return Regex("^\\s*$", RegexOption.MULTILINE).find(this)?.range?.first ?: this.length
        }

        fun String.notHexLineIndex(): Int {
            return Regex("[G-Z]", RegexOption.IGNORE_CASE).find(this)?.range?.first ?: this.length
        }

        return if (input.contains("Payload:")) {
            input.split("Payload:").drop(1).map {
                it.substring(0, minOf(it.emptyLineIndex(), it.notHexLineIndex()))
            }
        } else {
            input.split(Regex("^\\s*$", RegexOption.MULTILINE))
        }
    }

    fun multipleParser(input: String, split: Boolean, importer: ImportCapabilities): Capabilities {
        val inputArray =
            if (split) {
                split0xB826hex(input)
            } else {
                listOf(input)
            }
        val list = mutableListWithCapacity<Capabilities>(inputArray.size)
        inputArray.forEach {
            try {
                val inputStream = it.preformatHex().decodeHex().inputStream()
                list.add(importer.parse(inputStream))
            } catch (err: IllegalArgumentException) {
                val errMessage = "Invalid hexdump"
                val multiHelp =
                    if (!split)
                        "Use flag '--multiple0xB826' if you are parsing multiple 0xB826 hexdumps."
                    else ""
                throw IllegalArgumentException(errMessage + multiHelp, err)
            }
        }
        val enDcCombos =
            list.fold(mutableListOf<ComboEnDc>()) { sum, x ->
                x.enDcCombos?.let { sum.addAll(it) }
                sum
            }
        val nrCombos =
            list.fold(mutableListOf<ComboNr>()) { sum, x ->
                x.nrCombos?.let { sum.addAll(it) }
                sum
            }
        val nrDcCombos =
            list.fold(mutableListOf<ComboNrDc>()) { sum, x ->
                x.nrDcCombos?.let { sum.addAll(it) }
                sum
            }

        return Capabilities().also {
            it.enDcCombos = enDcCombos
            it.nrCombos = nrCombos
            it.nrDcCombos = nrDcCombos
        }
    }

    private fun getResourceAsStream(path: String): InputStream? =
        object {}.javaClass.getResourceAsStream(path)

    val asn1TranslatorLte by lazy {
        val definition = getResourceAsStream("/definition/EUTRA-RRC-Definitions.asn")!!
        ASN1Translator(PERTranslatorFactory(false), listOf(definition))
    }

    val asn1TranslatorNr by lazy {
        val definition = getResourceAsStream("/definition/NR-RRC-Definitions.asn")!!
        ASN1Translator(PERTranslatorFactory(false), listOf(definition))
    }

    fun getAsn1Converter(rat: Rat, converter: AbstractConverter): ASN1Converter {
        val definition =
            if (rat == Rat.EUTRA) {
                getResourceAsStream("/definition/EUTRA-RRC-Definitions.asn")!!
            } else {
                getResourceAsStream("/definition/NR-RRC-Definitions.asn")!!
            }
        return ASN1Converter(converter, listOf(definition))
    }

    private fun ratContainerToJson(rat: Rat, bytes: ByteArray): JsonObject {
        val jsonWriter = KotlinJsonFormatWriter()
        val json = buildJsonObject {
            when (rat) {
                Rat.EUTRA -> {
                    asn1TranslatorLte.decode(
                        rat.ratCapabilityIdentifier,
                        bytes.inputStream(),
                        jsonWriter
                    )
                    jsonWriter.jsonNode?.let { put(rat.toString(), it) }
                }
                Rat.EUTRA_NR -> {
                    asn1TranslatorNr.decode(
                        rat.ratCapabilityIdentifier,
                        bytes.inputStream(),
                        jsonWriter
                    )
                    jsonWriter.jsonNode?.let { put(rat.toString(), it) }
                }
                Rat.NR -> {
                    asn1TranslatorNr.decode(
                        rat.ratCapabilityIdentifier,
                        bytes.inputStream(),
                        jsonWriter
                    )
                    jsonWriter.jsonNode?.let { put(rat.toString(), it) }
                }
                else -> {}
            }
        }
        return json
    }

    fun getUeCapabilityJsonFromHex(defaultRat: Rat, hexString: String): JsonObject {
        val data = hexString.preformatHex().decodeHex()

        if (data.isEmpty()) {
            return buildJsonObject {}
        }

        val isLteCapInfo = data[0] in 0x38.toByte()..0x3E.toByte()
        val isNrCapInfo = data[0] in 0x48.toByte()..0x4E.toByte()

        if (!isLteCapInfo && !isNrCapInfo) {
            return ratContainerToJson(defaultRat, data)
        }

        val jsonWriter = KotlinJsonFormatWriter()
        val translator: ASN1Translator
        val ratContainerListPath: String
        val octetStringKey: String

        if (isLteCapInfo) {
            translator = asn1TranslatorLte
            ratContainerListPath =
                "message.c1.ueCapabilityInformation.criticalExtensions.c1.ueCapabilityInformation-r8.ue-CapabilityRAT-ContainerList"
            octetStringKey = "ueCapabilityRAT-Container"
        } else {
            translator = asn1TranslatorNr
            ratContainerListPath =
                "message.c1.ueCapabilityInformation.criticalExtensions.ueCapabilityInformation.ue-CapabilityRAT-ContainerList"
            octetStringKey = "ue-CapabilityRAT-Container"
        }

        translator.decode("UL-DCCH-Message", data.inputStream(), jsonWriter)

        val ueCap = jsonWriter.jsonNode?.getArrayAtPath(ratContainerListPath)
        val map = mutableMapOf<String, JsonElement>()
        if (ueCap != null) {
            for (ueCapContainer in ueCap) {
                val ratType = Rat.of(ueCapContainer.getString("rat-Type"))
                val octetString = ueCapContainer.getString(octetStringKey)
                if (ratType != null && octetString != null) {
                    map += ratContainerToJson(ratType, octetString.decodeHex())
                }
            }
        }
        return JsonObject(map)
    }
}
