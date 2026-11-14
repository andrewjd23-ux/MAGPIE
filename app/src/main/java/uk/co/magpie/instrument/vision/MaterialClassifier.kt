package uk.co.magpie.instrument.vision

/** Adapters must be licensed, trained for materials, initialized and tested before Ready. */
interface MaterialClassifier : AutoCloseable {
    val status: MaterialModelStatus
    /** Called once per acquisition, with only the selected crop, on the material executor. */
    fun classify(input: MaterialInput): MaterialResult
    override fun close() {}
}

/** Pixels are the selected upright RGB crop, not a full camera frame or COCO category. */
data class MaterialInput(val argb: IntArray, val width: Int, val height: Int) {
    init { require(width > 0 && height > 0 && argb.size.toLong() == width.toLong() * height) }
}
enum class MaterialFamily { METAL, NONMETAL, UNSPECIFIED }
enum class MaterialDecision { METAL_SUSPECTED, NONMETAL_SUSPECTED, UNKNOWN }

/** Heuristic score/margin gates, never calibrated probabilities of composition. */
data class MaterialDecisionPolicy(val minimumScore: Float = .70f, val minimumMargin: Float = .15f) {
    init { require(minimumScore in 0f..1f && minimumMargin in 0f..1f) }
}
data class VerifiedMaterialModel(
    val id: String,
    val redistributionLicense: String,
    val provenance: String,
    val validationReport: String,
    val materialLabels: Map<String, MaterialFamily>,
    val policy: MaterialDecisionPolicy = MaterialDecisionPolicy()
) {
    init {
        require(listOf(id, redistributionLicense, provenance, validationReport).all { it.isNotBlank() })
        require(materialLabels.isNotEmpty())
    }
}
sealed interface MaterialModelStatus {
    data class Unavailable(val reason: String) : MaterialModelStatus
    /** Adapter authors must verify license, label contract and real on-device inference. */
    data class Ready(val model: VerifiedMaterialModel) : MaterialModelStatus
}
sealed interface MaterialResult {
    data object Pending : MaterialResult
    data class Unavailable(val reason: String) : MaterialResult
    data class Predictions(val model: String, val scores: List<MaterialScore>, val calibrationValidated: Boolean = false) : MaterialResult
}
data class MaterialScore(val label: String, val score: Float)

class NoMaterialModel : MaterialClassifier {
    override val status = MaterialModelStatus.Unavailable("Material model unavailable — no verified trained material model installed")
    override fun classify(input: MaterialInput): MaterialResult = MaterialResult.Unavailable(status.reason)
}

/** Fail closed if an adapter returns a mismatched tensor/label contract or invalid scores. */
fun validateMaterialResult(result: MaterialResult, status: MaterialModelStatus): MaterialResult {
    val model = (status as? MaterialModelStatus.Ready)?.model
        ?: return MaterialResult.Unavailable((status as? MaterialModelStatus.Unavailable)?.reason ?: "Material model unavailable")
    if (result is MaterialResult.Unavailable) return result
    val predictions = result as? MaterialResult.Predictions
        ?: return MaterialResult.Unavailable("Material model unavailable: inference returned no predictions")
    if (predictions.model != model.id || predictions.scores.isEmpty() ||
        predictions.scores.map { it.label }.distinct().size != predictions.scores.size ||
        predictions.scores.any { !it.score.isFinite() || it.score !in 0f..1f || it.label !in model.materialLabels }) {
        return MaterialResult.Unavailable("Material model unavailable: invalid model result or label contract")
    }
    return predictions
}

/** Only a verified material model's own trained labels can contribute to the decision. */
fun decideMaterial(result: MaterialResult, status: MaterialModelStatus): MaterialDecision {
    val model = (status as? MaterialModelStatus.Ready)?.model ?: return MaterialDecision.UNKNOWN
    val predictions = result as? MaterialResult.Predictions ?: return MaterialDecision.UNKNOWN
    if (predictions.model != model.id || predictions.scores.size < 2) return MaterialDecision.UNKNOWN
    if (predictions.scores.map { it.label }.distinct().size != predictions.scores.size ||
        predictions.scores.any { !it.score.isFinite() || it.score !in 0f..1f || it.label !in model.materialLabels }) return MaterialDecision.UNKNOWN
    val sorted = predictions.scores.sortedByDescending { it.score }
    val best = sorted.first()
    if (best.score + 1e-6f < model.policy.minimumScore || best.score - sorted[1].score + 1e-6f < model.policy.minimumMargin) return MaterialDecision.UNKNOWN
    return when (model.materialLabels[best.label]) {
        MaterialFamily.METAL -> MaterialDecision.METAL_SUSPECTED
        MaterialFamily.NONMETAL -> MaterialDecision.NONMETAL_SUSPECTED
        else -> MaterialDecision.UNKNOWN
    }
}

fun explainTarget(decision: MaterialDecision, material: MaterialResult, scan: GuidedScanResult? = null): String {
    val visual = when (decision) {
        MaterialDecision.METAL_SUSPECTED -> "METAL_SUSPECTED: a visible-surface model suggestion; composition and alloy identity are not established. A guided scan is offered."
        MaterialDecision.NONMETAL_SUSPECTED -> "NONMETAL_SUSPECTED: visible-surface suggestion only; hidden or mixed materials remain unknown. Magnetic fusion is skipped."
        MaterialDecision.UNKNOWN -> if (material is MaterialResult.Unavailable) "UNKNOWN: ${material.reason}. Inconclusive; an optional manual scan is available."
            else "UNKNOWN: material evidence is pending, ambiguous or insufficient. Inconclusive; an optional manual scan is available."
    }
    if (decision == MaterialDecision.NONMETAL_SUSPECTED) return visual
    return visual + " " + (scan?.let(::interpretScan) ?: "Live field changes are not attributed to the target.")
}

data class MaterialAnalysis(val result: MaterialResult, val decision: MaterialDecision)

/** Called only by a user acquisition. Missing models do not even request crop pixels. */
fun analyzeSelectedCrop(classifier: MaterialClassifier, input: () -> MaterialInput): MaterialAnalysis {
    try {
        val status = classifier.status
        val result = when (status) {
            is MaterialModelStatus.Unavailable -> MaterialResult.Unavailable(status.reason)
            is MaterialModelStatus.Ready -> validateMaterialResult(classifier.classify(input()), status)
        }
        return MaterialAnalysis(result, decideMaterial(result, status))
    } catch (error: Exception) { return failedMaterialAnalysis(error) }
    catch (error: LinkageError) { return failedMaterialAnalysis(error) }
    catch (error: OutOfMemoryError) { return failedMaterialAnalysis(error) }
}
private fun failedMaterialAnalysis(error: Throwable) = MaterialAnalysis(
    MaterialResult.Unavailable("Material model unavailable: ${error.message ?: error.javaClass.simpleName}"), MaterialDecision.UNKNOWN)
