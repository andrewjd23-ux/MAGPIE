package uk.co.magpie.instrument.vision

/** Separate from object detection: adapters must own preprocessing, trained labels and validation. */
interface MaterialClassifier : AutoCloseable {
    val modelName: String?
    fun classify(input: MaterialInput): MaterialResult
    override fun close() {}
}

data class MaterialInput(val argb: IntArray, val width: Int, val height: Int, val region: Box2)
sealed interface MaterialResult {
    data class Unavailable(val reason: String) : MaterialResult
    data class Predictions(val model: String, val scores: List<MaterialScore>, val calibrationValidated: Boolean = false) : MaterialResult
}
data class MaterialScore(val label: String, val score: Float)

/** No material model is bundled. Never substitute COCO object labels for material labels. */
class NoMaterialModel : MaterialClassifier {
    override val modelName: String? = null
    override fun classify(input: MaterialInput): MaterialResult = unavailable()
    fun unavailable() = MaterialResult.Unavailable("No trained material model installed")
}

fun explainEvidence(objectLabel: String?, material: MaterialResult, controlledScan: Boolean = false): String {
    val visual = objectLabel?.let { "Visual object label: $it. " } ?: "No visual object selected. "
    val materialReason = when (material) {
        is MaterialResult.Unavailable -> "Material unknown: ${material.reason}. "
        is MaterialResult.Predictions -> "Material model evidence requires validation; no metal identity is established. "
    }
    return visual + materialReason + if (!controlledScan) {
        "Inconclusive: no controlled magnetic scan. Live field changes are not attributed to this object."
    } else {
        "Inconclusive: magnetic response alone cannot establish material identity or exclude surrounding sources."
    }
}
