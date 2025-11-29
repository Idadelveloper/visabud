package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.compose.resources.ExperimentalResourceApi
import visabud_multiplatform.composeapp.generated.resources.Res

/**
 * Visa Fees Database loader (fully local).
 *
 * Purpose:
 * - Parse the bundled files/visa_fees.json (rooted schema) and expose a small
 *   facade API that CostEstimator can use to compute total costs with a detailed
 *   breakdown (primary + dependents + ancillary + premium).
 *
 * Notes:
 * - The upstream JSON is rich and may evolve. We intentionally parse it using
 *   a tolerant DFS over JsonElement rather than rigid DTOs, extracting only the
 *   fields we need (visaTypeId, base fee, dependent fees, ancillary items, conversion rates).
 * - All processing is on-device; no network calls.
 */
object VisaFeesDb {
    private const val RESOURCE_PATH = "files/visa_fees.json"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Serializable
    data class Money(val amount: Double, val currency: String)

    @Serializable
    data class Ancillary(
        val serviceId: String,
        val serviceName: String,
        val amount: Double,
        val currency: String,
        val mandatory: Boolean = false
    )

    @Serializable
    data class PremiumService(
        val serviceName: String,
        val amount: Double,
        val currency: String
    )

    @Serializable
    data class VisaFeeInfo(
        val countryCode: String?,
        val visaTypeId: String,
        val visaTypeName: String? = null,
        val baseFee: Money,
        val dependentFees: Map<String, Money> = emptyMap(), // spouse, child, etc.
        val ancillaryFees: List<Ancillary> = emptyList(),    // mandatory
        val optionalAncillaryFees: List<Ancillary> = emptyList(),
        val premiumServices: List<PremiumService> = emptyList(),
        val lastVerified: String? = null,
        val currencyHint: String? = null
    )

    // Conversion rates map: currency code -> USD per 1 unit of currency
    private var usdPerUnit: Map<String, Double> = emptyMap()
    private var lastLoadedOk: Boolean = false
    private var cachedText: String? = null

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun ensureLoaded() {
        if (lastLoadedOk && cachedText != null) return
        val text = Res.readBytes(RESOURCE_PATH).decodeToString()
        cachedText = text
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val conv = root["conversionRates"]?.jsonObject
            val rates = conv?.get("rates")?.jsonObject
            usdPerUnit = rates?.mapValues { (it.value as? JsonPrimitive)?.content?.toDoubleOrNull() ?: 0.0 }?.filterValues { it > 0.0 } ?: emptyMap()
            lastLoadedOk = true
        } catch (_: Throwable) {
            usdPerUnit = emptyMap()
            lastLoadedOk = true // still usable for heuristic fallbacks
        }
    }

    suspend fun getConversionRate(from: String, to: String): Double? {
        ensureLoaded()
        val f = from.uppercase()
        val t = to.uppercase()
        if (f == t) return 1.0
        // We store USD per unit. Convert via USD as pivot.
        val fToUsd = usdPerUnit[f] ?: return null
        val tToUsd = usdPerUnit[t] ?: return null
        // 1 unit of FROM equals fToUsd USD; to convert USD->TO we divide by tToUsd
        return fToUsd / tToUsd
    }

    suspend fun convertToUsd(amount: Double, currency: String): Double? {
        ensureLoaded()
        val rate = usdPerUnit[currency.uppercase()] ?: return null
        return amount * rate
    }

    suspend fun convertFromUsd(usd: Double, targetCurrency: String): Double? {
        ensureLoaded()
        val rate = usdPerUnit[targetCurrency.uppercase()] ?: return null
        return if (rate == 0.0) null else usd / rate
    }

    /** Depth-first search for all JsonObjects that contain key==value. */
    private fun findObjectsWithKeyValue(element: JsonElement, key: String, value: String, out: MutableList<JsonObject>) {
        when (element) {
            is JsonObject -> {
                val hit = element[key]?.let { it is JsonPrimitive && it.content.equals(value, ignoreCase = true) } == true
                if (hit) out += element
                element.values.forEach { findObjectsWithKeyValue(it, key, value, out) }
            }
            is JsonArray -> element.forEach { findObjectsWithKeyValue(it, key, value, out) }
            else -> {}
        }
    }

    /** Try to infer ISO2/3 destination code from the nearest parent fields. */
    private fun inferCountryCode(context: JsonObject?): String? {
        if (context == null) return null
        // Try common field names present in dataset examples
        val countryCode = context["countryCode"] ?: context["destination_code"]
        if (countryCode is JsonPrimitive) return countryCode.content.uppercase()
        // Walk up one level: search nested objects
        for ((_, v) in context) if (v is JsonObject) {
            val code = v["countryCode"] ?: v["destination_code"]
            if (code is JsonPrimitive) return code.content.uppercase()
        }
        return null
    }

    private fun pickMoney(obj: JsonObject, keyAmount: String, defaultCurrency: String? = null): Money? {
        // Accept patterns: amount_usd, amount_gbp, amount_aud; or fee_usd/fee_gbp
        val keys = listOf("amount_usd","amount_gbp","amount_eur","amount_aud","amount_cad","fee_usd","fee_gbp","fee_eur","fee_aud","fee_cad","amount")
        for (k in keys) {
            val v = obj[k] ?: obj[keyAmount]
            if (v is JsonPrimitive) {
                val num = v.content.toDoubleOrNull()
                if (num != null) {
                    val currency = when {
                        k.endsWith("_usd") || keyAmount.endsWith("_usd") -> "USD"
                        k.endsWith("_gbp") || keyAmount.endsWith("_gbp") -> "GBP"
                        k.endsWith("_eur") || keyAmount.endsWith("_eur") -> "EUR"
                        k.endsWith("_aud") || keyAmount.endsWith("_aud") -> "AUD"
                        k.endsWith("_cad") || keyAmount.endsWith("_cad") -> "CAD"
                        else -> defaultCurrency ?: obj["currency"]?.jsonPrimitive?.content ?: "USD"
                    }
                    return Money(num, currency)
                }
            }
        }
        return null
    }

    private fun parseAncillaryArray(arr: JsonArray?, defaultCurrency: String?): List<Ancillary> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Ancillary>()
        for (el in arr) if (el is JsonObject) {
            val id = el["type"]?.jsonPrimitive?.content
                ?: el["serviceId"]?.jsonPrimitive?.content
                ?: (el["item"]?.jsonPrimitive?.content?.lowercase()?.replace(" ", "_") ?: "service")
            val name = el["serviceName"]?.jsonPrimitive?.content
                ?: el["item"]?.jsonPrimitive?.content
                ?: id.replace("_"," ").replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            val currency = el["currency"]?.jsonPrimitive?.content ?: defaultCurrency
            val amount = when {
                el["amount"] is JsonPrimitive -> (el["amount"] as JsonPrimitive).content.toDoubleOrNull()
                el["amount_usd"] is JsonPrimitive -> (el["amount_usd"] as JsonPrimitive).content.toDoubleOrNull()
                el["amount_gbp"] is JsonPrimitive -> (el["amount_gbp"] as JsonPrimitive).content.toDoubleOrNull()
                el["amount_aud"] is JsonPrimitive -> (el["amount_aud"] as JsonPrimitive).content.toDoubleOrNull()
                else -> null
            }
            if (amount != null && currency != null) {
                out += Ancillary(id, name, amount, currency, mandatory = el["mandatory"]?.jsonPrimitive?.booleanOrNull == true)
            }
        }
        return out
    }

    @OptIn(ExperimentalResourceApi::class)
    suspend fun loadVisaFee(countryCode: String, visaTypeId: String): VisaFeeInfo? {
        ensureLoaded()
        val text = cachedText ?: return null
        val root = try { json.parseToJsonElement(text) } catch (e: Throwable) { return null }
        val matches = mutableListOf<JsonObject>()
        findObjectsWithKeyValue(root, "visaTypeId", visaTypeId, matches)
        if (matches.isEmpty()) {
            // Fallback: tolerate common aliases for IDs (e.g., H-1B under USA)
            val altId = visaTypeId.replace("H1B","H-1B", ignoreCase = true)
            if (altId != visaTypeId) {
                findObjectsWithKeyValue(root, "visaTypeId", altId, matches)
            }
            if (matches.isEmpty()) return null
        }
        // Pick the first hit with a numeric fee present
        val hit = matches.firstOrNull { m ->
            val baseObj = m["baseFee"] as? JsonObject
            val directUsd = baseObj?.get("amount")?.jsonPrimitive?.doubleOrNull
                ?: baseObj?.get("fee_usd")?.jsonPrimitive?.doubleOrNull
            directUsd != null || m["breakdown"] is JsonArray || m["dependentFee"] is JsonObject
        } ?: matches.first()

        val ctx = hit
        val visaName = ctx["visaTypeName"]?.jsonPrimitive?.content
            ?: ctx["visaType"]?.jsonPrimitive?.content
        val defaultCurrency = ctx["baseFee"]?.jsonObject?.get("currency")?.jsonPrimitive?.content

        // Base fee: try baseFee object, else infer from breakdown item named Main Applicant / Application fee
        var base: Money? = (ctx["baseFee"] as? JsonObject)?.let { b ->
            val amt = b["amount"]?.jsonPrimitive?.doubleOrNull
                ?: b["fee_usd"]?.jsonPrimitive?.doubleOrNull
                ?: b.entries.firstOrNull { it.key.startsWith("fee_") || it.key.startsWith("amount_") }?.value?.jsonPrimitive?.doubleOrNull
            val cur = b["currency"]?.jsonPrimitive?.content
                ?: b.entries.firstOrNull { it.key.startsWith("fee_") || it.key.startsWith("amount_") }?.key?.substringAfter('_')?.uppercase()
            if (amt != null && cur != null) Money(amt, cur) else null
        }
        if (base == null) {
            val breakdown = ctx["breakdown"] as? JsonArray
            val main = breakdown?.firstOrNull { it is JsonObject && (it["item"]?.jsonPrimitive?.content?.contains("Main", true) == true || it["category"]?.jsonPrimitive?.content == "primary") } as? JsonObject
            base = main?.let { m ->
                val money = pickMoney(m, "amount_usd", defaultCurrency)
                val currency = when {
                    m["amount_gbp"] != null -> "GBP"
                    m["amount_aud"] != null -> "AUD"
                    m["amount_eur"] != null -> "EUR"
                    m["amount_cad"] != null -> "CAD"
                    m["amount_usd"] != null -> "USD"
                    else -> defaultCurrency ?: "USD"
                }
                val amt = money?.amount ?: m["amount_gbp"]?.jsonPrimitive?.doubleOrNull
                    ?: m["amount_aud"]?.jsonPrimitive?.doubleOrNull
                    ?: m["amount_eur"]?.jsonPrimitive?.doubleOrNull
                    ?: m["amount_cad"]?.jsonPrimitive?.doubleOrNull
                    ?: m["amount_usd"]?.jsonPrimitive?.doubleOrNull
                if (amt != null) Money(amt, currency) else null
            }
        }
        val baseMoney = base ?: return null

        // Dependents
        val depMap = mutableMapOf<String, Money>()
        val dependentFeeObj = ctx["dependentFee"] as? JsonObject
        if (dependentFeeObj != null) {
            dependentFeeObj["spouse_partner"]?.jsonPrimitive?.doubleOrNull?.let { depMap["spouse"] = Money(it, dependentFeeObj["currency"]?.jsonPrimitive?.content ?: defaultCurrency ?: "USD") }
            dependentFeeObj["spouse"]?.jsonPrimitive?.doubleOrNull?.let { depMap["spouse"] = Money(it, dependentFeeObj["currency"]?.jsonPrimitive?.content ?: defaultCurrency ?: "USD") }
            dependentFeeObj["child"]?.jsonPrimitive?.doubleOrNull?.let { depMap["child"] = Money(it, dependentFeeObj["currency"]?.jsonPrimitive?.content ?: defaultCurrency ?: "USD") }
            dependentFeeObj["child_under_18"]?.jsonPrimitive?.doubleOrNull?.let { depMap["child"] = Money(it, dependentFeeObj["currency"]?.jsonPrimitive?.content ?: defaultCurrency ?: "USD") }
        } else {
            // Try breakdown items
            val breakdown = ctx["breakdown"] as? JsonArray
            breakdown?.forEach { el ->
                if (el is JsonObject) {
                    val label = el["item"]?.jsonPrimitive?.content?.lowercase()
                    if (label != null && (label.contains("spouse") || label.contains("partner"))) {
                        val m = pickMoney(el, "amount_usd", defaultCurrency)
                        if (m != null) depMap["spouse"] = Money(m.amount, m.currency)
                    }
                    if (label != null && label.contains("child")) {
                        val m = pickMoney(el, "amount_usd", defaultCurrency)
                        if (m != null) depMap["child"] = Money(m.amount, m.currency)
                    }
                }
            }
        }

        // Ancillary (mandatory + optional)
        val ancMandatoryList = parseAncillaryArray((ctx["ancillaryFees"] as? JsonArray), defaultCurrency).toMutableList()
        val optionalAncList = parseAncillaryArray((ctx["optionalAncillaryFees"] as? JsonArray), defaultCurrency).toMutableList()
        // Some examples encode everything under breakdown with category flags
        val breakdown = ctx["breakdown"] as? JsonArray
        if (breakdown != null) {
            for (el in breakdown) if (el is JsonObject) {
                val cat = el["category"]?.jsonPrimitive?.content
                val mandatory = el["mandatory"]?.jsonPrimitive?.booleanOrNull == true
                if (cat == "ancillary") {
                    val m = pickMoney(el, "amount_usd", defaultCurrency) ?: continue
                    val name = el["item"]?.jsonPrimitive?.content ?: "Service"
                    val id = name.lowercase().replace(" ", "_")
                    val anc = Ancillary(id, name, m.amount, m.currency, mandatory)
                    if (mandatory) {
                        if (ancMandatoryList.none { it.serviceName == anc.serviceName }) ancMandatoryList.add(anc)
                    } else {
                        if (optionalAncList.none { it.serviceName == anc.serviceName }) optionalAncList.add(anc)
                    }
                }
            }
        }

        val lastVerified = ctx["last_verified"]?.jsonPrimitive?.content
            ?: ctx["lastUpdated"]?.jsonPrimitive?.content

        val country = inferCountryCode(ctx)
        return VisaFeeInfo(
            countryCode = country ?: countryCode.uppercase(),
            visaTypeId = visaTypeId,
            visaTypeName = visaName,
            baseFee = baseMoney,
            dependentFees = depMap.toMap(),
            ancillaryFees = ancMandatoryList,
            optionalAncillaryFees = optionalAncList,
            premiumServices = emptyList(),
            lastVerified = lastVerified,
            currencyHint = defaultCurrency ?: baseMoney.currency
        )
    }
}
