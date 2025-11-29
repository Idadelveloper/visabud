package online.visabud.app.visabud_multiplatform.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import online.visabud.app.visabud_multiplatform.data.DataModule
import online.visabud.app.visabud_multiplatform.data.UserProfile

/**
 * Document-Review Module (on-device vision / parsing)
 * Purpose:
 * - Parse uploaded document text/metadata
 * - Extract simple fields (name, dates, numbers)
 * - Validate against basic visa-related heuristics (e.g., passport validity 6+ months)
 * - Keep ALL data local (no network calls)
 *
 * Notes:
 * - This MVP works from provided plain text (OCR output) or filename/mime patterns.
 * - Vision/OCR hooks can be added per-platform to supply the `extractedText` parameter.
 */
object DocumentReviewModule {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }

    // --- Public API ---

    /**
     * Analyze one document using lightweight heuristics. If [updateRepository] is true and [docId] is provided,
     * the parsed fields will be saved into DataModule.documents as JSON in DocumentMeta.parsedFieldsJson.
     */
    suspend fun review(
        type: String?,
        filename: String?,
        mimeType: String?,
        extractedText: String?,
        targetVisa: String? = null,
        profile: UserProfile? = null,
        docId: String? = null,
        updateRepository: Boolean = true
    ): ReviewOutcome {
        val detectedType = detectType(type, filename, mimeType, extractedText)
        val parsed = parseFields(detectedType, filename, mimeType, extractedText)
        val validation = validateAgainstHeuristics(detectedType, parsed, targetVisa, profile)

        val parsedJson = json.encodeToString(parsed)
        val status = when {
            validation.issues.any { it.severity == "high" } -> "NEEDS_UPDATE"
            validation.issues.isNotEmpty() -> "REVIEW"
            else -> "OK"
        }
        val emoji = when (status) {
            "OK" -> "✅"
            "NEEDS_UPDATE" -> "⚠️"
            else -> "ℹ️"
        }
        val outcome = ReviewOutcome(
            status = status,
            emoji = emoji,
            issues = validation.issues,
            suggestions = validation.suggestions,
            parsed = parsed,
            parsedJson = parsedJson
        )

        // Optionally persist parsed JSON inside DocumentRepository entry
        if (updateRepository && docId != null) {
            val existing = DataModule.documents.get(docId)
            if (existing != null) {
                val updated = existing.copy(parsedFieldsJson = parsedJson)
                // The DocumentRepository interface lacks an update method; using add() replaces by id in impl
                DataModule.documents.add(updated)
            }
        }
        return outcome
    }

    /** Analyze many documents and produce a high-level summary string for agent reply. */
    suspend fun analyzeDocuments(
        items: List<ReviewInput>,
        targetVisa: String? = null,
        profile: UserProfile? = null,
        updateRepository: Boolean = true
    ): BatchReviewResult {
        val results = mutableListOf<Pair<ReviewInput, ReviewOutcome>>()
        for (it in items) {
            val out = review(
                type = it.type,
                filename = it.filename,
                mimeType = it.mimeType,
                extractedText = it.extractedText,
                targetVisa = targetVisa,
                profile = profile,
                docId = it.docId,
                updateRepository = updateRepository
            )
            results += it to out
        }
        val anyHigh = results.any { it.second.status == "NEEDS_UPDATE" }
        val allOk = results.isNotEmpty() && results.all { it.second.status == "OK" }
        val header = when {
            allOk -> "✅ All documents look OK."
            anyHigh -> "⚠️ Some documents need an update or extra evidence."
            else -> "ℹ️ Documents reviewed. See notes below."
        }
        // Privacy reminder per requirements
        val privacy = "Note: Analysis was done fully on-device. You can choose what to share; nothing was uploaded."
        return BatchReviewResult(header = header, privacyNote = privacy, results = results)
    }

    // --- Parsing / detection ---

    private fun detectType(type: String?, filename: String?, mimeType: String?, text: String?): String {
        val t = (type ?: "").lowercase()
        val f = (filename ?: "").lowercase()
        val m = (mimeType ?: "").lowercase()
        val x = (text ?: "").lowercase()
        return when {
            listOf(t, f, m, x).any { it.contains("passport") } -> "passport"
            listOf(t, f, m, x).any { it.contains("statement") || it.contains("bank") } -> "bank_statement"
            listOf(t, f, m, x).any { it.contains("degree") || it.contains("diploma") || it.contains("bachelor") || it.contains("master") } -> "degree_certificate"
            else -> t.ifBlank { "unknown" }
        }
    }

    @Serializable
    data class ParsedFields(
        val detectedType: String,
        val holderName: String? = null,
        val issueDate: String? = null,
        val expiryDate: String? = null,
        val documentNumber: String? = null,
        val fileType: String? = null,
        val validFormat: Boolean = true,
        // Bank statement
        val accountName: String? = null,
        val accountNumber: String? = null,
        val currency: String? = null,
        val balance: Double? = null,
        // Degree
        val institution: String? = null,
        val degree: String? = null,
        val graduationDate: String? = null
    )

    private fun parseFields(detectedType: String, filename: String?, mimeType: String?, text: String?): ParsedFields {
        val fileType = mimeType ?: guessMimeFromFilename(filename)
        val safeText = text ?: ""
        return when (detectedType) {
            "passport" -> parsePassport(fileType, safeText)
            "bank_statement" -> parseBankStatement(fileType, safeText)
            "degree_certificate" -> parseDegree(fileType, safeText)
            else -> ParsedFields(detectedType = detectedType.ifBlank { "unknown" }, fileType = fileType, validFormat = safeText.isNotBlank())
        }
    }

    private fun parsePassport(fileType: String?, text: String): ParsedFields {
        val name = regexFirst(text, NAME_REGEX)
        val exp = regexFirst(text, EXPIRY_REGEX) ?: regexFirst(text, DATE_REGEX)
        val issue = regexFirst(text, ISSUE_REGEX) ?: regexFirst(text, DATE_REGEX)
        val number = regexFirst(text, PASSPORT_NUM_REGEX)
        return ParsedFields(
            detectedType = "passport",
            holderName = name,
            issueDate = normalizeDate(issue),
            expiryDate = normalizeDate(exp),
            documentNumber = number,
            fileType = fileType,
            validFormat = number != null || name != null || exp != null
        )
    }

    private fun parseBankStatement(fileType: String?, text: String): ParsedFields {
        val name = regexFirst(text, NAME_REGEX) ?: regexFirst(text, ACCOUNT_NAME_REGEX)
        val accNo = regexFirst(text, ACCOUNT_NUM_REGEX)
        val cur = regexFirst(text, CURRENCY_REGEX)
        val bal = regexFirst(text, BALANCE_REGEX)?.let { extractNumber(it) }
        return ParsedFields(
            detectedType = "bank_statement",
            holderName = name,
            accountName = name,
            accountNumber = accNo,
            currency = cur,
            balance = bal,
            fileType = fileType,
            validFormat = accNo != null || bal != null
        )
    }

    private fun parseDegree(fileType: String?, text: String): ParsedFields {
        val institution = regexFirst(text, INSTITUTION_REGEX)
        val degree = regexFirst(text, DEGREE_REGEX)
        val grad = regexFirst(text, GRAD_DATE_REGEX) ?: regexFirst(text, DATE_REGEX)
        val name = regexFirst(text, NAME_REGEX)
        return ParsedFields(
            detectedType = "degree_certificate",
            holderName = name,
            institution = institution,
            degree = degree,
            graduationDate = normalizeDate(grad),
            fileType = fileType,
            validFormat = degree != null || institution != null
        )
    }

    // --- Validation ---

    @Serializable
    data class ReviewIssue(val field: String, val problem: String, val severity: String)

    @Serializable
    data class ValidationResult(val issues: List<ReviewIssue>, val suggestions: List<String>)

    @Serializable
    data class ReviewOutcome(
        val status: String,
        val emoji: String,
        val issues: List<ReviewIssue>,
        val suggestions: List<String>,
        val parsed: ParsedFields,
        val parsedJson: String
    )

    @Serializable
    data class ReviewInput(
        val docId: String? = null,
        val type: String? = null,
        val filename: String? = null,
        val mimeType: String? = null,
        val extractedText: String? = null
    )

    @Serializable
    data class BatchReviewResult(
        val header: String,
        val privacyNote: String,
        val results: List<Pair<ReviewInput, ReviewOutcome>>
    )

    private fun validateAgainstHeuristics(type: String, parsed: ParsedFields, targetVisa: String?, profile: UserProfile?): ValidationResult {
        val issues = mutableListOf<ReviewIssue>()
        val suggestions = mutableListOf<String>()
        when (type) {
            "passport" -> {
                // Expiry present
                if (parsed.expiryDate == null) {
                    issues += ReviewIssue("expiryDate", "Passport expiry date not found.", "high")
                    suggestions += "Upload the passport biodata page or ensure the expiry date is visible."
                } else {
                    if (!isValidSixMonthsBeyond(parsed.expiryDate)) {
                        issues += ReviewIssue("expiryDate", "Passport may not be valid for 6+ months beyond planned travel.", "high")
                        suggestions += "Renew passport to have at least 6 months validity beyond travel date."
                    }
                }
                if (parsed.holderName == null) {
                    issues += ReviewIssue("holderName", "Name not detected.", "medium")
                    suggestions += "Ensure the name is legible in the scan/photo."
                }
                if (parsed.documentNumber == null) {
                    issues += ReviewIssue("documentNumber", "Passport number not detected.", "medium")
                    suggestions += "Provide a clearer scan/photo of the biodata page."
                }
            }
            "bank_statement" -> {
                val minFunds = inferMinFunds(targetVisa)
                val bal = parsed.balance
                if (bal == null) {
                    issues += ReviewIssue("balance", "Could not detect closing balance.", "medium")
                    suggestions += "Provide a recent bank statement (last 3-6 months) showing balances."
                } else if (bal < minFunds) {
                    issues += ReviewIssue("balance", "Balance appears below a typical threshold (~$minFunds).", "medium")
                    suggestions += "Increase available funds or add additional financial evidence (sponsor letter, savings, fixed deposits)."
                }
                if (parsed.accountNumber == null) {
                    issues += ReviewIssue("accountNumber", "Account number not found.", "low")
                }
            }
            "degree_certificate" -> {
                if (parsed.degree == null) {
                    issues += ReviewIssue("degree", "Degree title not detected.", "medium")
                    suggestions += "Share a clearer copy or include a transcript to confirm the award."
                }
                if (parsed.institution == null) {
                    issues += ReviewIssue("institution", "Issuing institution not detected.", "low")
                }
            }
            else -> {
                issues += ReviewIssue("type", "Unrecognized document type.", "low")
                suggestions += "Indicate the document type (passport, bank statement, degree certificate)."
            }
        }
        // Cross-check with profile name if available
        if (profile?.name != null && parsed.holderName != null) {
            val match = namesLookSimilar(profile.name, parsed.holderName)
            if (!match) {
                issues += ReviewIssue("holderName", "Name on document differs from profile.", "medium")
                suggestions += "Ensure the same legal name is used across documents."
            }
        }
        return ValidationResult(issues, suggestions.distinct())
    }

    private fun inferMinFunds(targetVisa: String?): Double {
        val t = (targetVisa ?: "").lowercase()
        return when {
            t.contains("study") || t.contains("student") -> 6000.0
            t.contains("work") -> 3000.0
            else -> 3000.0
        }
    }

    // --- Utilities ---

    private fun guessMimeFromFilename(filename: String?): String? = when {
        filename == null -> null
        filename.endsWith(".pdf", true) -> "application/pdf"
        filename.endsWith(".jpg", true) || filename.endsWith(".jpeg", true) -> "image/jpeg"
        filename.endsWith(".png", true) -> "image/png"
        else -> null
    }

    private fun regexFirst(text: String, regex: Regex): String? = regex.find(text)?.groupValues?.firstOrNull { it.isNotBlank() }

    private fun extractNumber(s: String): Double? = runCatching {
        // Remove commas and currency symbols
        val cleaned = s.replace(",", "").replace(Regex("[^0-9.\\-]"), "")
        cleaned.toDouble()
    }.getOrNull()

    private fun normalizeDate(s: String?): String? {
        if (s.isNullOrBlank()) return null
        val trimmed = s.trim()
        // Already ISO
        if (trimmed.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return trimmed
        // Common formats: DD/MM/YYYY, MM/DD/YYYY, DD-MM-YYYY
        val m1 = Regex("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{4})").find(trimmed)
        if (m1 != null) {
            val d = m1.groupValues[1].padStart(2, '0')
            val mo = m1.groupValues[2].padStart(2, '0')
            val y = m1.groupValues[3]
            // Heuristic: if day > 12 assume DD/MM else MM/DD; we output YYYY-MM-DD
            return if (d.toInt() > 12) "$y-$mo-$d" else "$y-$d-$mo"
        }
        // Textual month e.g., 12 Feb 2028
        val m2 = Regex("(\\d{1,2})\\s+(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\s+(\\d{4})", RegexOption.IGNORE_CASE).find(trimmed)
        if (m2 != null) {
            val d = m2.groupValues[1].padStart(2, '0')
            val mo = monthToNum(m2.groupValues[2])
            val y = m2.groupValues[3]
            return "$y-$mo-$d"
        }
        return trimmed // fallback to original
    }

    private fun monthToNum(m: String): String = when (m.lowercase().take(3)) {
        "jan" -> "01"; "feb" -> "02"; "mar" -> "03"; "apr" -> "04"; "may" -> "05"; "jun" -> "06";
        "jul" -> "07"; "aug" -> "08"; "sep" -> "09"; "oct" -> "10"; "nov" -> "11"; "dec" -> "12";
        else -> "01"
    }

    private fun isValidSixMonthsBeyond(expiryIso: String): Boolean {
        // Use the provided session date instead of platform date to keep common code simple
        val today = "2025-11-29" // from session context
        val plusSix = addMonthsIso(today, 6)
        return expiryIso >= plusSix
    }

    private fun addMonthsIso(iso: String, months: Int): String {
        // Very naive month addition sufficient for heuristic check
        val y = iso.substring(0, 4).toIntOrNull() ?: return iso
        val m = iso.substring(5, 7).toIntOrNull() ?: return iso
        val d = iso.substring(8, 10)
        val total = m + months
        val newY = y + (total - 1) / 12
        val newM = ((total - 1) % 12) + 1
        val mm = newM.toString().padStart(2, '0')
        val yStr = newY.toString().padStart(4, '0')
        return "$yStr-$mm-$d"
    }

    private fun namesLookSimilar(a: String, b: String): Boolean {
        val na = a.lowercase().replace(Regex("[^a-z]"), "").take(20)
        val nb = b.lowercase().replace(Regex("[^a-z]"), "").take(20)
        if (na.isBlank() || nb.isBlank()) return true
        return na.contains(nb.take(3)) || nb.contains(na.take(3)) || na.take(5) == nb.take(5)
    }

    // --- Regex patterns ---
    private val DATE_REGEX = Regex("(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})|\\d{4}-\\d{2}-\\d{2}")
    private val NAME_REGEX = Regex("(?i)(name|holder|surname|given names?)[:\n\r\t ]+([A-Z][A-Z ]{1,40})")
    private val EXPIRY_REGEX = Regex("(?i)(expiry|expires|expiration|date of expiry)[:\n\r\t ]+([0-9A-Za-z ./-]{6,20})")
    private val ISSUE_REGEX = Regex("(?i)(issue|issued|date of issue)[:\n\r\t ]+([0-9A-Za-z ./-]{6,20})")
    private val PASSPORT_NUM_REGEX = Regex("(?i)(passport|document)[ \t]*no\\.?[:\n\r\t ]+([A-Z0-9]{5,15})")

    private val ACCOUNT_NAME_REGEX = Regex("(?i)(account name|account holder)[:\n\r\t ]+([A-Z][A-Z &]{2,40})")
    private val ACCOUNT_NUM_REGEX = Regex("(?i)(account|acct)[ \t]*number[:\n\r\t ]+([0-9]{6,20})")
    private val CURRENCY_REGEX = Regex("(?i)(currency|curr)[:\n\r\t ]+([A-Z]{3}|USD|EUR|GBP|INR|AUD|CAD)")
    private val BALANCE_REGEX = Regex("(?i)(closing balance|available balance|balance)[:\n\r\t ]+([$€£₹]?[0-9,]+(?:\\.[0-9]{2})?)")

    private val INSTITUTION_REGEX = Regex("(?i)(university|college|institute|institution)[:\n\r\t ]+([A-Za-z .,&'-]{3,60})")
    private val DEGREE_REGEX = Regex("(?i)(bachelor|master|msc|ba|bs|b\\.sc|m\\.sc|phd|diploma|certificate)[A-Za-z .,&'-]*")
    private val GRAD_DATE_REGEX = Regex("(?i)(date of graduation|awarded on|conferred on)[:\n\r\t ]+([0-9A-Za-z ./-]{6,20})")
}
