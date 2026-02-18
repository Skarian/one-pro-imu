package io.onepro.xrprobe

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import io.onepro.xr.HeadOrientationDegrees
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

class OrientationGlSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private data class Point3(val x: Float, val y: Float, val z: Float)
    private data class Point2(val x: Float, val y: Float)
    private data class Segment(val start: Point3, val end: Point3, val color: Int, val strokeWidth: Float)
    private data class CameraBasis(val right: Point3, val up: Point3, val forward: Point3)

    private val stateLock = Any()
    private var relativePitchDeg = 0.0f
    private var relativeYawDeg = 0.0f
    private var relativeRollDeg = 0.0f
    private var cameraSensitivity = 1.0f

    private val backgroundPaint = Paint().apply { color = Color.rgb(16, 20, 28) }
    private val horizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(34, 43, 61)
        strokeWidth = 2.0f
        style = Paint.Style.STROKE
    }
    private val segmentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val sceneSegments = buildSceneSegments()

    fun updateRelativeOrientation(orientation: HeadOrientationDegrees) {
        if (!orientation.pitch.isFinite() || !orientation.yaw.isFinite() || !orientation.roll.isFinite()) {
            return
        }
        synchronized(stateLock) {
            relativePitchDeg = orientation.pitch
            relativeYawDeg = orientation.yaw
            relativeRollDeg = orientation.roll
        }
        postInvalidateOnAnimation()
    }

    fun setSensitivity(sensitivity: Float) {
        synchronized(stateLock) {
            cameraSensitivity = sensitivity.coerceIn(0.1f, 2.0f)
        }
        postInvalidateOnAnimation()
    }

    fun resetCamera() {
        synchronized(stateLock) {
            relativePitchDeg = 0.0f
            relativeYawDeg = 0.0f
            relativeRollDeg = 0.0f
        }
        postInvalidateOnAnimation()
    }

    fun onResume() = Unit

    fun onPause() = Unit

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0.0f, 0.0f, width.toFloat(), height.toFloat(), backgroundPaint)

        if (width <= 0 || height <= 0) {
            return
        }

        val orientation = synchronized(stateLock) {
            floatArrayOf(
                relativePitchDeg,
                relativeYawDeg,
                relativeRollDeg,
                cameraSensitivity
            )
        }

        val pitch = Math.toRadians((orientation[0] * orientation[3]).toDouble()).toFloat()
        val yaw = Math.toRadians((orientation[1] * orientation[3]).toDouble()).toFloat()
        val roll = Math.toRadians((orientation[2] * orientation[3]).toDouble()).toFloat()

        val look = normalize(
            Point3(
                x = sin(yaw) * cos(pitch),
                y = -sin(pitch),
                z = -cos(yaw) * cos(pitch)
            )
        )
        val upSeed = normalize(
            Point3(
                x = -sin(roll) * cos(yaw) - cos(roll) * sin(yaw) * (-sin(pitch)),
                y = cos(roll) * cos(pitch),
                z = -sin(roll) * sin(yaw) + cos(roll) * cos(yaw) * (-sin(pitch))
            )
        )

        val right = normalize(cross(look, upSeed))
        val up = normalize(cross(right, look))
        val basis = CameraBasis(
            right = right,
            up = up,
            forward = look
        )

        drawHorizonGuides(canvas)
        drawScene(canvas, basis)
    }

    private fun drawScene(canvas: Canvas, basis: CameraBasis) {
        sceneSegments.forEach { segment ->
            val start = project(segment.start, basis) ?: return@forEach
            val end = project(segment.end, basis) ?: return@forEach
            segmentPaint.color = segment.color
            segmentPaint.strokeWidth = segment.strokeWidth
            canvas.drawLine(start.x, start.y, end.x, end.y, segmentPaint)
        }
    }

    private fun drawHorizonGuides(canvas: Canvas) {
        val midY = height * 0.58f
        canvas.drawLine(0.0f, midY, width.toFloat(), midY, horizonPaint)
        canvas.drawLine(width * 0.5f, 0.0f, width * 0.5f, height.toFloat(), horizonPaint)
    }

    private fun project(point: Point3, basis: CameraBasis): Point2? {
        val camera = Point3(0.0f, 0.0f, 8.0f)
        val relative = subtract(point, camera)

        val xCam = dot(relative, basis.right)
        val yCam = dot(relative, basis.up)
        val zCam = dot(relative, basis.forward)

        if (zCam <= 0.08f) {
            return null
        }

        val focal = min(width, height) * 0.95f
        return Point2(
            x = width * 0.5f + (xCam * focal / zCam),
            y = height * 0.58f - (yCam * focal / zCam)
        )
    }

    private fun buildSceneSegments(): List<Segment> {
        val segments = mutableListOf<Segment>()

        segments += Segment(Point3(0.0f, 0.0f, 0.0f), Point3(3.0f, 0.0f, 0.0f), Color.rgb(224, 67, 54), 7.0f)
        segments += Segment(Point3(0.0f, 0.0f, 0.0f), Point3(0.0f, 3.0f, 0.0f), Color.rgb(76, 175, 80), 7.0f)
        segments += Segment(Point3(0.0f, 0.0f, 0.0f), Point3(0.0f, 0.0f, 3.0f), Color.rgb(66, 133, 244), 7.0f)

        appendCubeSegments(segments, Point3(0.0f, 0.0f, 0.0f), 1.0f, Color.rgb(255, 159, 67), 3.5f)

        listOf(
            Point3(5.0f, 0.0f, 0.0f),
            Point3(-5.0f, 0.0f, 0.0f),
            Point3(0.0f, 5.0f, 0.0f),
            Point3(0.0f, -5.0f, 0.0f)
        ).forEach {
            appendCubeSegments(segments, it, 0.5f, Color.rgb(100, 181, 246), 2.5f)
        }

        listOf(
            Point3(0.0f, 0.0f, 5.0f) to 0.3f,
            Point3(0.0f, 0.0f, -5.0f) to 0.3f,
            Point3(3.0f, 3.0f, 3.0f) to 0.2f,
            Point3(-3.0f, -3.0f, -3.0f) to 0.2f
        ).forEach {
            appendCubeSegments(segments, it.first, it.second, Color.rgb(165, 214, 167), 2.2f)
        }

        listOf(
            Point3(0.0f, 0.0f, 15.0f) to 0.4f,
            Point3(8.0f, 0.0f, 10.0f) to 0.25f,
            Point3(-8.0f, 0.0f, 10.0f) to 0.25f
        ).forEach {
            appendCubeSegments(segments, it.first, it.second, Color.rgb(255, 213, 79), 2.0f)
        }

        return segments
    }

    private fun appendCubeSegments(
        output: MutableList<Segment>,
        center: Point3,
        halfSize: Float,
        color: Int,
        strokeWidth: Float
    ) {
        val corners = arrayOf(
            Point3(center.x - halfSize, center.y - halfSize, center.z + halfSize),
            Point3(center.x - halfSize, center.y + halfSize, center.z + halfSize),
            Point3(center.x + halfSize, center.y + halfSize, center.z + halfSize),
            Point3(center.x + halfSize, center.y - halfSize, center.z + halfSize),
            Point3(center.x - halfSize, center.y - halfSize, center.z - halfSize),
            Point3(center.x - halfSize, center.y + halfSize, center.z - halfSize),
            Point3(center.x + halfSize, center.y + halfSize, center.z - halfSize),
            Point3(center.x + halfSize, center.y - halfSize, center.z - halfSize)
        )

        val edges = intArrayOf(
            0, 1,
            1, 2,
            2, 3,
            3, 0,
            4, 5,
            5, 6,
            6, 7,
            7, 4,
            0, 4,
            1, 5,
            2, 6,
            3, 7
        )

        var index = 0
        while (index < edges.size) {
            output += Segment(
                start = corners[edges[index]],
                end = corners[edges[index + 1]],
                color = color,
                strokeWidth = strokeWidth
            )
            index += 2
        }
    }

    private fun dot(a: Point3, b: Point3): Float {
        return a.x * b.x + a.y * b.y + a.z * b.z
    }

    private fun cross(a: Point3, b: Point3): Point3 {
        return Point3(
            x = a.y * b.z - a.z * b.y,
            y = a.z * b.x - a.x * b.z,
            z = a.x * b.y - a.y * b.x
        )
    }

    private fun subtract(a: Point3, b: Point3): Point3 {
        return Point3(
            x = a.x - b.x,
            y = a.y - b.y,
            z = a.z - b.z
        )
    }

    private fun normalize(point: Point3): Point3 {
        val magnitude = sqrt(point.x * point.x + point.y * point.y + point.z * point.z)
        if (!magnitude.isFinite() || magnitude < 1e-5f) {
            return Point3(0.0f, 1.0f, 0.0f)
        }
        return Point3(
            x = point.x / magnitude,
            y = point.y / magnitude,
            z = point.z / magnitude
        )
    }
}
