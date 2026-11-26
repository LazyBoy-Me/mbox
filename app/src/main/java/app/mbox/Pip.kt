package app.mbox

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational

/**
 * Picture-in-picture state shared between the player and the activity.
 *
 * The stock app keeps playback alive when you press Home and exposes play/pause, next episode and
 * rewind in the PiP window. That needs three pieces: the player registering callbacks, the activity
 * entering PiP, and a receiver for the window's own buttons.
 */
object Pip {
    const val ACTION_TOGGLE = "app.mbox.PIP_TOGGLE"
    const val ACTION_NEXT = "app.mbox.PIP_NEXT"
    const val ACTION_BACK10 = "app.mbox.PIP_BACK10"

    @Volatile var active: Boolean = false
    @Volatile var playing: Boolean = false
    @Volatile var hasNext: Boolean = false
    @Volatile var title: String = ""
    /** A floating mini window is on screen; Home must not turn it into a PiP window. */
    @Volatile var mini: Boolean = false

    var onToggle: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onBack10: (() -> Unit)? = null

    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    fun actions(ctx: Context): List<RemoteAction> {
        if (!supported) return emptyList()
        val list = ArrayList<RemoteAction>(3)
        list.add(
            action(
                ctx,
                ACTION_TOGGLE,
                if (playing) "Pause" else "Play",
                if (playing) R.drawable.ic_player_pause else R.drawable.ic_player_play,
                1,
            )
        )
        if (hasNext) {
            list.add(action(ctx, ACTION_NEXT, "Next episode", R.drawable.ic_play_next, 2))
        }
        list.add(
            action(
                ctx,
                ACTION_BACK10,
                "Back 10 seconds",
                R.drawable.ls_ic_player_backward_10,
                3,
            )
        )
        return list
    }

    private fun action(ctx: Context, action: String, label: String, icon: Int, code: Int): RemoteAction {
        val intent = Intent(ctx, PipReceiver::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getBroadcast(ctx, code, intent, flags)
        return RemoteAction(Icon.createWithResource(ctx, icon), label, label, pending)
    }

    fun params(ctx: Context, autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9))
        if (supported) {
            builder.setActions(actions(ctx))
            if (title.isNotBlank()) builder.setTitle(title.take(48))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }
}

/** Receives the PiP window's action buttons. */
class PipReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Pip.ACTION_TOGGLE -> Pip.onToggle?.invoke()
            Pip.ACTION_NEXT -> Pip.onNext?.invoke()
            Pip.ACTION_BACK10 -> Pip.onBack10?.invoke()
        }
    }
}
