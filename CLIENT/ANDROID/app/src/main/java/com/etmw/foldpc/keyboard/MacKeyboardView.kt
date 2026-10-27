package com.etmw.foldpc.keyboard

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.etmw.foldpc.R
import kotlin.math.min
import kotlin.math.roundToInt

class MacKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onCharacter(text: String, modifiers: Modifiers)
        fun onBackspace(modifiers: Modifiers)
        fun onDeleteForward(modifiers: Modifiers)
        fun onEnter(modifiers: Modifiers)
        fun onTab(modifiers: Modifiers)
        fun onEscape(modifiers: Modifiers)
        fun onArrow(direction: ArrowDirection, modifiers: Modifiers)
        fun onFunction(number: Int, modifiers: Modifiers)
        fun onFunctionRowToggled(visible: Boolean)
    }

    var listener: Listener? = null

    var showFunctionRow: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A424F")
        strokeWidth = dp(0.7f)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#050608")
    }
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1FFFFFFF")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.7f)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A84FF")
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#330A84FF")
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val leftTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }

    private var hits: List<KeyHit> = emptyList()
    private var desiredHeight = 0

    private var shiftLatched = false
    private var capsLock = false
    private var optionLatched = false
    private var controlLatched = false
    private var commandLatched = false

    private var pressedId: String? = null
    private var pressProgress = 0f
    private var flashId: String? = null
    private var flashProgress = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    private var pressAnimator: ValueAnimator? = null
    private var flashAnimator: ValueAnimator? = null
    private var backgroundShader: LinearGradient? = null

    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatKeyId: String? = null
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = hits.firstOrNull { it.spec.id == repeatKeyId }?.spec
            if (key == null || pressedId != key.id) return
            emitAction(key, currentModifiers(), repeat = true)
            repeatHandler.postDelayed(this, 48L)
        }
    }

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        isClickable = false
        isHapticFeedbackEnabled = true
        contentDescription = "Mac keyboard"
    }

    fun desiredHeightForWidth(width: Int): Int {
        return calculateLayout(width).desiredHeight
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val heightMode = MeasureSpec.getMode(heightMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        val layout = calculateLayout(width)
        desiredHeight = layout.desiredHeight
        val measuredHeight = if (heightMode == MeasureSpec.EXACTLY) {
            heightSize
        } else {
            min(desiredHeight, heightSize)
        }
        buildGeometry(width, layout)
        setMeasuredDimension(width, measuredHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        backgroundShader = if (w > 0 && h > 0) {
            LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                Color.parseColor("#1B1F26"),
                Color.parseColor("#0B0D11"),
                Shader.TileMode.CLAMP
            )
        } else {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        backgroundPaint.shader = backgroundShader
        if (backgroundShader == null) {
            backgroundPaint.color = Color.parseColor("#111419")
        }
        canvas.drawRect(0f, 0f, w, h, backgroundPaint)
        backgroundPaint.shader = null
        canvas.drawLine(0f, 0f, w, 0f, topLinePaint)

        hits.forEach { hit ->
            drawKey(canvas, hit)
        }
    }

    private fun drawKey(canvas: Canvas, hit: KeyHit) {
        val spec = hit.spec
        val pressed = spec.id == pressedId
        val flash = spec.id == flashId
        val progress = if (pressed) pressProgress else 0f
        val inset = dp(1.4f) * progress
        val sink = dp(1.2f) * progress
        val rect = RectF(hit.rect)
        rect.inset(inset, inset)
        rect.offset(0f, sink)

        val active = isActive(spec)
        val isModifier = spec.type in setOf(
            KeyType.SHIFT, KeyType.CAPS_LOCK, KeyType.OPTION,
            KeyType.CONTROL, KeyType.COMMAND, KeyType.FN
        )
        val isFunction = spec.type == KeyType.FUNCTION || spec.type == KeyType.ESCAPE
        val radius = dp(6.5f)
        val shadowOffset = dp(2.4f) * (1f - progress * 0.55f)

        shadowPaint.alpha = (210 - progress * 80f).roundToInt().coerceIn(80, 220)
        canvas.drawRoundRect(
            RectF(rect.left, rect.top + shadowOffset, rect.right, rect.bottom + shadowOffset),
            radius, radius, shadowPaint
        )

        facePaint.color = when {
            pressed -> Color.parseColor("#4A5568")
            active -> Color.parseColor("#244867")
            isModifier -> Color.parseColor("#242932")
            isFunction -> Color.parseColor("#20252C")
            else -> Color.parseColor("#303641")
        }
        canvas.drawRoundRect(rect, radius, radius, facePaint)

        borderPaint.strokeWidth = dp(0.8f)
        borderPaint.color = when {
            pressed -> Color.parseColor("#6EA9E0")
            active -> Color.parseColor("#0A84FF")
            else -> Color.parseColor("#49515F")
        }
        canvas.drawRoundRect(rect, radius, radius, borderPaint)

        if (progress > 0f || flash) {
            val alpha = if (progress > 0f) (50 * progress).roundToInt() else (40 * flashProgress).roundToInt()
            glowPaint.alpha = alpha.coerceIn(0, 70)
            glowPaint.strokeWidth = dp(2.2f)
            canvas.drawRoundRect(rect, radius, radius, glowPaint)
            facePaint.color = Color.argb((30 * progress).roundToInt(), 90, 170, 255)
            canvas.drawRoundRect(rect, radius, radius, facePaint)
        }

        canvas.drawLine(
            rect.left + radius * 0.8f,
            rect.top + dp(1.2f),
            rect.right - radius * 0.8f,
            rect.top + dp(1.2f),
            highlightPaint
        )

        drawKeyLabel(canvas, spec, rect, active, isModifier, isFunction)
    }

    private fun drawKeyLabel(
        canvas: Canvas,
        spec: KeySpec,
        rect: RectF,
        active: Boolean,
        isModifier: Boolean,
        isFunction: Boolean
    ) {
        val primaryColor = if (active) Color.parseColor("#8FC7FF") else Color.parseColor("#E9EDF4")
        val secondaryColor = if (active) Color.parseColor("#6EB8F5") else Color.parseColor("#99A3B3")
        val keyHeight = rect.height()

        textPaint.color = primaryColor
        smallTextPaint.color = secondaryColor
        leftTextPaint.color = secondaryColor

        when {
            spec.type == KeyType.SPACE -> {
                smallTextPaint.textSize = sp(9.5f)
                val baseline = rect.centerY() - (smallTextPaint.ascent() + smallTextPaint.descent()) / 2f
                canvas.drawText(spec.label, rect.centerX(), baseline, smallTextPaint)
            }

            spec.compact && isFunction -> {
                smallTextPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                smallTextPaint.textSize = min(sp(10.5f), keyHeight * 0.34f)
                val baseline = rect.centerY() - (smallTextPaint.ascent() + smallTextPaint.descent()) / 2f
                canvas.drawText(spec.label, rect.centerX(), baseline, smallTextPaint)
                smallTextPaint.typeface = Typeface.SANS_SERIF
            }

            spec.secondary != null -> {
                textPaint.textSize = min(sp(if (isModifier) 16f else 17f), keyHeight * 0.43f)
                smallTextPaint.textSize = min(sp(8.4f), keyHeight * 0.22f)
                val symbolBaseline = rect.top + keyHeight * 0.47f -
                    (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(spec.label, rect.centerX(), symbolBaseline, textPaint)
                val secondaryBaseline = rect.bottom - keyHeight * 0.14f
                canvas.drawText(spec.secondary, rect.centerX(), secondaryBaseline, smallTextPaint)
            }

            spec.shiftLabel != null -> {
                textPaint.textSize = min(sp(14.5f), keyHeight * 0.34f)
                smallTextPaint.textSize = min(sp(9.2f), keyHeight * 0.23f)
                val primaryBaseline = rect.bottom - keyHeight * 0.20f
                canvas.drawText(spec.label, rect.centerX(), primaryBaseline, textPaint)
                leftTextPaint.textSize = smallTextPaint.textSize
                val shiftBaseline = rect.top + keyHeight * 0.31f
                canvas.drawText(spec.shiftLabel, rect.left + dp(5.5f), shiftBaseline, leftTextPaint)
                if (spec.shiftLabel.length > 1) {
                    leftTextPaint.textAlign = Paint.Align.CENTER
                    canvas.drawText(spec.shiftLabel, rect.centerX(), rect.top + keyHeight * 0.28f, leftTextPaint)
                    leftTextPaint.textAlign = Paint.Align.LEFT
                }
            }

            else -> {
                textPaint.textSize = min(sp(15f), keyHeight * 0.38f)
                val baseline = rect.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f
                canvas.drawText(spec.label, rect.centerX(), baseline, textPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val pointerId = event.getPointerId(0)
                val key = findKey(event.x, event.y) ?: return false
                activePointerId = pointerId
                performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
                triggerKey(key)
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointerId) {
                    releaseKey()
                    activePointerId = MotionEvent.INVALID_POINTER_ID
                }
                performClick()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelKey()
                activePointerId = MotionEvent.INVALID_POINTER_ID
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_MOVE -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun triggerKey(key: KeySpec) {
        pressedId = key.id
        flashId = null
        repeatKeyId = null
        repeatHandler.removeCallbacks(repeatRunnable)
        animatePress(1f, 62L)

        when (key.type) {
            KeyType.SHIFT -> {
                shiftLatched = !shiftLatched
                invalidate()
            }
            KeyType.CAPS_LOCK -> {
                capsLock = !capsLock
                invalidate()
            }
            KeyType.OPTION -> {
                optionLatched = !optionLatched
                invalidate()
            }
            KeyType.CONTROL -> {
                controlLatched = !controlLatched
                invalidate()
            }
            KeyType.COMMAND -> {
                commandLatched = !commandLatched
                invalidate()
            }
            KeyType.FN -> {
                showFunctionRow = !showFunctionRow
                listener?.onFunctionRowToggled(showFunctionRow)
                requestLayout()
                invalidate()
            }
            else -> {
                emitAction(key, currentModifiers(), repeat = false)
                clearOneShotModifiers()
                if (key.type == KeyType.BACKSPACE || isArrow(key.type)) {
                    repeatKeyId = key.id
                    repeatHandler.postDelayed(repeatRunnable, 360L)
                }
            }
        }
    }

    private fun releaseKey() {
        repeatKeyId = null
        repeatHandler.removeCallbacks(repeatRunnable)
        val id = pressedId ?: return
        flashId = id
        animatePress(0f, 92L)
        animateFlash()
    }

    private fun cancelKey() {
        repeatKeyId = null
        repeatHandler.removeCallbacks(repeatRunnable)
        pressedId = null
        pressProgress = 0f
        pressAnimator?.cancel()
        flashId = null
        flashProgress = 0f
        flashAnimator?.cancel()
        invalidate()
    }

    private fun animatePress(target: Float, duration: Long) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressProgress, target).apply {
            this.duration = duration
            addUpdateListener {
                pressProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (target == 0f && pressedId == flashId) {
                        pressedId = null
                        pressProgress = 0f
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    private fun animateFlash() {
        flashAnimator?.cancel()
        flashProgress = 1f
        flashAnimator = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 155L
            addUpdateListener {
                flashProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    flashId = null
                    flashProgress = 0f
                    invalidate()
                }
            })
            start()
        }
    }

    private fun emitAction(key: KeySpec, modifiers: Modifiers, repeat: Boolean) {
        when (key.type) {
            KeyType.CHARACTER -> {
                val isLetter = key.text?.firstOrNull()?.isLetter() == true
                val useShift = if (isLetter) shiftLatched != capsLock else shiftLatched
                val value = if (useShift && key.shifted != null) key.shifted else key.text
                if (!value.isNullOrEmpty()) {
                    listener?.onCharacter(value, modifiers)
                }
            }
            KeyType.SPACE -> listener?.onCharacter(" ", modifiers)
            KeyType.BACKSPACE -> listener?.onBackspace(modifiers)
            KeyType.FORWARD_DELETE -> listener?.onDeleteForward(modifiers)
            KeyType.ENTER -> listener?.onEnter(modifiers)
            KeyType.TAB -> listener?.onTab(modifiers)
            KeyType.ESCAPE -> listener?.onEscape(modifiers)
            KeyType.ARROW_LEFT -> listener?.onArrow(ArrowDirection.LEFT, modifiers)
            KeyType.ARROW_RIGHT -> listener?.onArrow(ArrowDirection.RIGHT, modifiers)
            KeyType.ARROW_UP -> listener?.onArrow(ArrowDirection.UP, modifiers)
            KeyType.ARROW_DOWN -> listener?.onArrow(ArrowDirection.DOWN, modifiers)
            KeyType.FUNCTION -> listener?.onFunction(key.functionNumber, modifiers)
            else -> Unit
        }
        if (!repeat) invalidate()
    }

    private fun clearOneShotModifiers() {
        shiftLatched = false
        optionLatched = false
        controlLatched = false
        commandLatched = false
        invalidate()
    }

    private fun currentModifiers(): Modifiers {
        return Modifiers(
            shift = shiftLatched,
            option = optionLatched,
            control = controlLatched,
            command = commandLatched
        )
    }

    private fun isActive(spec: KeySpec): Boolean {
        return when (spec.type) {
            KeyType.SHIFT -> shiftLatched
            KeyType.CAPS_LOCK -> capsLock
            KeyType.OPTION -> optionLatched
            KeyType.CONTROL -> controlLatched
            KeyType.COMMAND -> commandLatched
            KeyType.FN -> showFunctionRow
            else -> false
        }
    }

    private fun findKey(x: Float, y: Float): KeySpec? {
        return hits.firstOrNull { it.rect.contains(x, y) }?.spec
    }

    private fun calculateLayout(width: Int): KeyboardLayout {
        val rows = buildRows()
        val screenHeight = resources.configuration.screenHeightDp
        val compactScreen = screenHeight < 540
        val gap = dp(if (compactScreen) 3.2f else 4.2f)
        val horizontalPad = dp(if (compactScreen) 4f else 6f).roundToInt()
        val verticalPad = dp(4f)
        val targetHeight = (screenHeight * 0.42f).coerceIn(178f, 300f) * density
        val functionHeight = if (showFunctionRow) dp(if (compactScreen) 27f else 30f) else 0f
        val rowCount = rows.size
        val mainRows = 5
        val usable = targetHeight - verticalPad * 2 - gap * (rowCount - 1) - functionHeight
        val mainHeight = (usable / mainRows).coerceIn(dp(28f), dp(48f))
        val desired = if (showFunctionRow) {
            functionHeight + mainHeight * mainRows + gap * (rowCount - 1) + verticalPad * 2
        } else {
            mainHeight * mainRows + gap * (rowCount - 1) + verticalPad * 2
        }
        return KeyboardLayout(
            rows = rows,
            gap = gap,
            horizontalPad = horizontalPad,
            verticalPad = verticalPad,
            functionHeight = functionHeight,
            mainHeight = mainHeight,
            desiredHeight = desired.roundToInt()
        )
    }

    private fun buildGeometry(width: Int, layout: KeyboardLayout) {
        val widthF = width.toFloat()
        if (widthF <= 0f) {
            hits = emptyList()
            return
        }
        val available = widthF - layout.horizontalPad * 2f
        val unit = available / 15f
        val built = ArrayList<KeyHit>(90)
        var y = layout.verticalPad
        layout.rows.forEachIndexed { rowIndex, row ->
            val rowHeight = if (showFunctionRow && rowIndex == 0) layout.functionHeight else layout.mainHeight
            var offset = 0f
            row.forEach { spec ->
                val left = layout.horizontalPad.toFloat() + offset * unit + layout.gap / 2f
                val right = layout.horizontalPad.toFloat() + (offset + spec.units) * unit - layout.gap / 2f
                val top = y + layout.gap / 2f
                val bottom = y + rowHeight - layout.gap / 2f
                built += KeyHit(spec, RectF(left, top, right, bottom))
                offset += spec.units
            }
            y += rowHeight + layout.gap
        }
        hits = built
    }

    private fun buildRows(): List<List<KeySpec>> {
        val rows = mutableListOf<List<KeySpec>>()
        if (showFunctionRow) {
            val functionRow = mutableListOf<KeySpec>()
            functionRow += KeySpec("esc", KeyType.ESCAPE, 1.8f, "esc", compact = true)
            for (i in 1..12) {
                functionRow += KeySpec(
                    "f$i", KeyType.FUNCTION, 1.1f, "F$i",
                    functionNumber = i, compact = true
                )
            }
            rows += functionRow
        }

        rows += listOf(
            glyph("grave", "`", "~"),
            glyph("1", "1", "!"),
            glyph("2", "2", "@"),
            glyph("3", "3", "#"),
            glyph("4", "4", "$"),
            glyph("5", "5", "%"),
            glyph("6", "6", "^"),
            glyph("7", "7", "&"),
            glyph("8", "8", "*"),
            glyph("9", "9", "("),
            glyph("0", "0", ")"),
            glyph("minus", "-", "_"),
            glyph("equals", "=", "+"),
            KeySpec("backspace", KeyType.BACKSPACE, 2f, "⌫", secondary = "delete")
        )

        val letterRow = mutableListOf<KeySpec>()
        letterRow += KeySpec("tab", KeyType.TAB, 2f, "⇥", secondary = "tab")
        listOf(
            "q", "w", "e", "r", "t", "y", "u", "i", "o", "p"
        ).forEach { letterRow += letter(it) }
        letterRow += glyph("left_bracket", "[", "{")
        letterRow += glyph("right_bracket", "]", "}")
        letterRow += glyph("backslash", "\\", "|")
        rows += letterRow

        val homeRow = mutableListOf<KeySpec>()
        homeRow += KeySpec("caps", KeyType.CAPS_LOCK, 2f, "⇪", secondary = "caps lock")
        listOf(
            "a", "s", "d", "f", "g", "h", "j", "k", "l"
        ).forEach { homeRow += letter(it) }
        homeRow += glyph("semicolon", ";", ":")
        homeRow += glyph("quote", "'", "\"")
        homeRow += KeySpec("return", KeyType.ENTER, 2f, "↵", secondary = "return")
        rows += homeRow

        val bottomLetterRow = mutableListOf<KeySpec>()
        bottomLetterRow += KeySpec("shift_left", KeyType.SHIFT, 2.25f, "⇧", secondary = "shift")
        listOf(
            "z", "x", "c", "v", "b", "n", "m"
        ).forEach { bottomLetterRow += letter(it) }
        bottomLetterRow += glyph("comma", ",", "<")
        bottomLetterRow += glyph("period", ".", ">")
        bottomLetterRow += glyph("slash", "/", "?")
        bottomLetterRow += KeySpec("shift_right", KeyType.SHIFT, 2.75f, "⇧", secondary = "shift")
        rows += bottomLetterRow

        rows += listOf(
            KeySpec("fn", KeyType.FN, 0.8f, "fn", secondary = null),
            KeySpec("control", KeyType.CONTROL, 1.15f, "⌃", secondary = "control"),
            KeySpec("option_left", KeyType.OPTION, 1.15f, "⌥", secondary = "option"),
            KeySpec("command_left", KeyType.COMMAND, 1.4f, "⌘", secondary = "command"),
            KeySpec("space", KeyType.SPACE, 4.35f, "space"),
            KeySpec("command_right", KeyType.COMMAND, 1.4f, "⌘", secondary = "command"),
            KeySpec("option_right", KeyType.OPTION, 1.15f, "⌥", secondary = "option"),
            KeySpec("left", KeyType.ARROW_LEFT, 0.9f, "←"),
            KeySpec("up", KeyType.ARROW_UP, 0.9f, "↑"),
            KeySpec("down", KeyType.ARROW_DOWN, 0.9f, "↓"),
            KeySpec("right", KeyType.ARROW_RIGHT, 0.9f, "→")
        )

        return rows
    }

    private fun letter(letter: String): KeySpec {
        return KeySpec(
            id = letter,
            type = KeyType.CHARACTER,
            units = 1f,
            label = letter.uppercase(),
            text = letter,
            shifted = letter.uppercase()
        )
    }

    private fun glyph(id: String, label: String, shifted: String): KeySpec {
        return KeySpec(
            id = id,
            type = KeyType.CHARACTER,
            units = 1f,
            label = label,
            text = label,
            shifted = shifted,
            shiftLabel = shifted
        )
    }

    private fun isArrow(type: KeyType): Boolean {
        return type == KeyType.ARROW_LEFT || type == KeyType.ARROW_RIGHT ||
            type == KeyType.ARROW_UP || type == KeyType.ARROW_DOWN
    }

    private fun dp(value: Float): Float = value * density

    private fun sp(value: Float): Float = value * scaledDensity

    override fun onDetachedFromWindow() {
        repeatHandler.removeCallbacks(repeatRunnable)
        pressAnimator?.cancel()
        flashAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private data class KeyHit(val spec: KeySpec, val rect: RectF)

    private data class KeyboardLayout(
        val rows: List<List<KeySpec>>,
        val gap: Float,
        val horizontalPad: Int,
        val verticalPad: Float,
        val functionHeight: Float,
        val mainHeight: Float,
        val desiredHeight: Int
    )
}
