package de.markusfisch.android.screentime.activity

import android.app.Activity
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Typeface
import android.os.Bundle
import android.preference.PreferenceManager.getDefaultSharedPreferences
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import de.markusfisch.android.screentime.R
import de.markusfisch.android.screentime.app.db
import de.markusfisch.android.screentime.data.drawUsageChart
import de.markusfisch.android.screentime.service.msToNextFullMinute
import kotlinx.coroutines.*
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
	private val job = SupervisorJob()
	private val scope = CoroutineScope(Dispatchers.Default + job)
	private val updateUsageRunnable = Runnable {
		scheduleUsageUpdate()
		update(dayBar.progress)
	}
	private val prefs by lazy { getDefaultSharedPreferences(this) }
	private val usagePaint by lazy {
		fillPaint(resources.getColor(R.color.usage)).apply {
			xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
		}
	}
	private val dialPaint by lazy {
		fillPaint(resources.getColor(R.color.dial))
	}
	private val textPaint by lazy {
		fillPaint(resources.getColor(R.color.text)).apply {
			typeface = Typeface.DEFAULT_BOLD
		}
	}

	private lateinit var usageView: ImageView
	private lateinit var dayBar: SeekBar
	private var paused = true

	override fun onCreate(state: Bundle?) {
		super.onCreate(state)
		setContentView(R.layout.activity_main)
		usageView = findViewById(R.id.graph)
		dayBar = findViewById(R.id.days)

		dayBar.setOnSeekBarChangeListener(object :
			SeekBar.OnSeekBarChangeListener {
			override fun onProgressChanged(
				seekBar: SeekBar,
				progress: Int,
				fromUser: Boolean
			) {
				if (fromUser) {
					// Post to queue changes.
					postUpdate(progress)
				}
			}

			override fun onStartTrackingTouch(seekBar: SeekBar) {}

			override fun onStopTrackingTouch(seekBar: SeekBar) {}
		})
	}

	override fun onResume() {
		super.onResume()
		updateDayBar()
		// Run update() after layout.
		postUpdate(dayBar.progress)
		paused = false
	}

	override fun onPause() {
		super.onPause()
		paused = true
		cancelUsageUpdate()
		prefs.edit().apply {
			putInt(DAYS, dayBar.progress)
			apply()
		}
	}

	override fun onDestroy() {
		super.onDestroy()
		job.cancelChildren()
	}

	private fun updateDayBar() {
		val availableHistoryInDays = db.availableHistoryInDays
		if (availableHistoryInDays < 1) {
			// Insert an initial SCREEN ON event if the database is
			// empty because we can only find an empty database if
			// the user has started this app for the first time.
			db.insertScreenEvent(System.currentTimeMillis(), true, 0f)
		}
		dayBar.progress = prefs.getInt(DAYS, dayBar.progress)
		dayBar.max = min(30, availableHistoryInDays)
		dayBar.visibility = if (dayBar.max == 0) View.GONE else View.VISIBLE
	}

	private fun postUpdate(days: Int) {
		usageView.post {
			update(days)
		}
	}

	private fun update(
		days: Int,
		timestamp: Long = System.currentTimeMillis()
	) {
		val dp = resources.displayMetrics.density
		val padding = (16f * dp).roundToInt() * 2
		val width = usageView.measuredWidth - padding
		val height = usageView.measuredHeight - padding
		if (width < 1 || height < 1) {
			usageView.postDelayed({
				update(days, timestamp)
			}, 1000)
			return
		}
		val d = days + 1
		val daysString = resources.getQuantityString(R.plurals.days, d, d)
		scope.launch {
			val bitmap = drawUsageChart(
				width,
				height,
				timestamp,
				days,
				daysString,
				usagePaint,
				dialPaint,
				textPaint
			)
			withContext(Dispatchers.Main) {
				usageView.setImageBitmap(bitmap)
				if (!paused) {
					scheduleUsageUpdate()
				}
			}
		}
	}

	private fun scheduleUsageUpdate() {
		cancelUsageUpdate()
		usageView.postDelayed(updateUsageRunnable, msToNextFullMinute())
	}

	private fun cancelUsageUpdate() {
		usageView.removeCallbacks(updateUsageRunnable)
	}

	companion object {
		private const val DAYS = "days"
	}
}

private fun fillPaint(col: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
	color = col
	style = Paint.Style.FILL
}
