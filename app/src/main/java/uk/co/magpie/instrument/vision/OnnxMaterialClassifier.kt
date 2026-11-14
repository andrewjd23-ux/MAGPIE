package uk.co.magpie.instrument.vision

import android.content.Context
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.FileInputStream
import java.nio.FloatBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Lazy CPU-only native adapter. All access, initialization and close belong to the material worker. */
class OnnxMaterialClassifier(context: Context) : MaterialClassifier {
    private val assets = context.applicationContext.assets
    private var session: OrtSession? = null
    private var mappedModel: MappedByteBuffer? = null
    private var environment: OrtEnvironment? = null
    private var closed = false
    private val metadata = VerifiedMaterialModel(
        id = MincMaterialContract.MODEL_ID,
        redistributionLicense = "Apache-2.0 (model cards); ONNX Runtime MIT",
        provenance = "manhhuynhsd/Minc-Materials-23-ONNX@273554c59c161c2b787c56136e21bfd64d56ff13; original prithivMLmods/Minc-Materials-23@b21f5bc35e96bfd60ef218088ae6ecba56ffe858",
        validationReport = "third_party/materials23/VALIDATION.md: hash/tensor/preprocessing checks and Android native reference inference; field accuracy and calibration unvalidated",
        materialLabels = MincMaterialContract.families
    )
    override val status: MaterialModelStatus
        get() {
            initialize()
            return MaterialModelStatus.Ready(metadata)
        }

    private fun initialize() {
        check(!closed) { "Material adapter is closed" }
        if (session != null) return
        val labels = assets.open("models/minc_materials23_labels.txt").bufferedReader().use { it.readLines() }
        check(labels == MincMaterialContract.labels) { "Material label order mismatch" }
        val bytes = assets.openFd("models/minc_materials23_int8.onnx").use { fd ->
            check(fd.declaredLength == MincMaterialContract.MODEL_BYTES) { "Invalid material model length" }
            FileInputStream(fd.fileDescriptor).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").apply { update(bytes.asReadOnlyBuffer()) }.digest()
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == MincMaterialContract.MODEL_SHA256) { "Material model integrity check failed" }
        val env = OrtEnvironment.getEnvironment()
        env.setTelemetry(false)
        val nativeSession = OrtSession.SessionOptions().use { options ->
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            options.setIntraOpNumThreads(2)
            options.setInterOpNumThreads(1)
            options.setMemoryPatternOptimization(false)
            options.setCPUArenaAllocator(false)
            // No NNAPI/cloud/Play Services providers. Only the built-in CPU execution provider.
            env.createSession(bytes, options)
        }
        try {
            check(nativeSession.inputNames == setOf("pixel_values") && nativeSession.outputNames == setOf("logits")) { "Unexpected material tensor names" }
            val input = nativeSession.inputInfo.getValue("pixel_values").info as TensorInfo
            val output = nativeSession.outputInfo.getValue("logits").info as TensorInfo
            check(input.type == OnnxJavaType.FLOAT && input.shape.size == 4 && input.shape.drop(1) == listOf(3L, 224L, 224L)) { "Invalid material input tensor" }
            check(output.type == OnnxJavaType.FLOAT && output.shape.size == 2 && output.shape[1] == 23L) { "Invalid material output tensor" }
            session = nativeSession; mappedModel = bytes; environment = env
        } catch (error: Throwable) { nativeSession.close(); throw error }
    }

    override fun classify(input: MaterialInput): MaterialResult {
        initialize()
        val tensor = MincPreprocessor.tensor(input)
        OnnxTensor.createTensor(environment!!, FloatBuffer.wrap(tensor), longArrayOf(1, 3, 224, 224)).use { value ->
            session!!.run(mapOf("pixel_values" to value)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val logits = result.get("logits").orElseThrow { IllegalStateException("Missing material logits") }.value as Array<FloatArray>
                check(logits.size == 1) { "Invalid material batch" }
                return MincMaterialContract.predictions(logits[0])
            }
        }
    }
    override fun close() {
        closed = true
        session?.close(); session = null; mappedModel = null; environment = null
        // OrtEnvironment is process-global and its telemetry remains disabled.
    }
}
