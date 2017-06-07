package ca.yyx.hu.aap

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.IBinder
import android.os.ResultReceiver
import android.support.v4.app.NotificationCompat
import android.support.v4.content.LocalBroadcastManager
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.widget.Toast

import ca.yyx.hu.App
import ca.yyx.hu.R
import ca.yyx.hu.RemoteControlReceiver
import ca.yyx.hu.aap.protocol.messages.NightModeEvent
import ca.yyx.hu.connection.AccessoryConnection
import ca.yyx.hu.connection.UsbAccessoryConnection
import ca.yyx.hu.connection.SocketAccessoryConnection
import ca.yyx.hu.decoder.AudioDecoder
import ca.yyx.hu.location.GpsLocationService
import ca.yyx.hu.roadrover.DeviceListener
import ca.yyx.hu.connection.UsbReceiver
import ca.yyx.hu.utils.LocalIntent
import ca.yyx.hu.utils.AppLog
import ca.yyx.hu.utils.NightMode
import ca.yyx.hu.utils.Settings
import ca.yyx.hu.utils.Utils

/**
 * @author algavris
 * *
 * @date 03/06/2016.
 */

class AapService : Service(), UsbReceiver.Listener, AccessoryConnection.Listener {

    private lateinit var mMediaSession: MediaSessionCompat
    private lateinit var mAudioDecoder: AudioDecoder
    private lateinit var mUiModeManager: UiModeManager
    private var mAccessoryConnection: AccessoryConnection? = null
    private lateinit var mUsbReceiver: UsbReceiver
    private lateinit var mTimeTickReceiver: BroadcastReceiver
    private lateinit var mDeviceListener: DeviceListener


    override fun onBind(intent: Intent): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        mUiModeManager = getSystemService(UI_MODE_SERVICE) as UiModeManager
        mUiModeManager.nightMode = UiModeManager.MODE_NIGHT_AUTO

        mAudioDecoder = App.get(this).audioDecoder()

        mMediaSession = MediaSessionCompat(this, "MediaSession", ComponentName(this, RemoteControlReceiver::class.java), null)
        mMediaSession.setCallback(MediaSessionCallback(this))
        mMediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)

        mUsbReceiver = UsbReceiver(this)
        mTimeTickReceiver = TimeTickReceiver(Settings(this), mUiModeManager)

        mDeviceListener = DeviceListener()
        registerReceiver(mDeviceListener, DeviceListener.createIntentFilter())
        registerReceiver(mTimeTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        registerReceiver(mUsbReceiver, UsbReceiver.createFilter())
    }

    override fun onDestroy() {
        super.onDestroy()
        onDisconnect()
        unregisterReceiver(mDeviceListener)
        unregisterReceiver(mTimeTickReceiver)
        unregisterReceiver(mUsbReceiver)
        mUiModeManager.disableCarMode(0)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {

        mAccessoryConnection = createConnection(intent, this)
        if (mAccessoryConnection == null) {
            AppLog.e("Cannot create connection " + intent)
            stopSelf()
            return START_NOT_STICKY
        }

        mUiModeManager.enableCarMode(0)

        val aapIntent = Intent(this, AapProjectionActivity::class.java)
        aapIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val noty = NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_stat_aa)
                .setTicker("Headunit is running")
                .setWhen(System.currentTimeMillis())
                .setContentTitle("Headunit is running")
                .setContentText("...")
                .setAutoCancel(false)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, aapIntent, PendingIntent.FLAG_UPDATE_CURRENT))
                .setPriority(Notification.PRIORITY_HIGH)
                .build()

        startService(GpsLocationService.intent(this))

        mMediaSession.setPlaybackState(PlaybackStateCompat.Builder()
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 0F)
                .setActions(PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .build())

        mMediaSession.isActive = true

        startForeground(1, noty)

        mAccessoryConnection!!.connect(this)

        return START_STICKY
    }

    override fun onConnectionResult(success: Boolean) {
        if (success) {
            reset()
            App.get(this).transport().connectAndStart(mAccessoryConnection!!)
        } else {
            AppLog.e("Cannot connect to device")
            Toast.makeText(this, "Cannot connect to the device", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    private fun onDisconnect() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(LocalIntent.ACTION_DISCONNECT)
        reset()
        mAccessoryConnection!!.disconnect()
        mAccessoryConnection = null
    }

    private fun reset() {
        App.get(this).transport().quit()
        mAudioDecoder.stop()
        App.get(this).videoDecoder().stop("AapService::reset")
        App.get(this).reset()
    }


    private class MediaSessionCallback internal constructor(private val mContext: Context) : MediaSessionCompat.Callback() {

        override fun onCommand(command: String, extras: Bundle, cb: ResultReceiver) {
            AppLog.i(command)
        }

        override fun onCustomAction(action: String, extras: Bundle) {
            AppLog.i(action)
        }

        override fun onSkipToNext() {
            AppLog.i("onSkipToNext")

            App.get(mContext).transport().sendButton(KeyEvent.KEYCODE_MEDIA_NEXT, true)
            Utils.ms_sleep(10)
            App.get(mContext).transport().sendButton(KeyEvent.KEYCODE_MEDIA_NEXT, false)
        }

        override fun onSkipToPrevious() {
            AppLog.i("onSkipToPrevious")

            App.get(mContext).transport().sendButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS, true)
            Utils.ms_sleep(10)
            App.get(mContext).transport().sendButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS, false)
        }

        override fun onPlay() {
            AppLog.i("PLAY")

            App.get(mContext).transport().sendButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, true)
            Utils.ms_sleep(10)
            App.get(mContext).transport().sendButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, false)
        }

        override fun onMediaButtonEvent(mediaButtonEvent: Intent): Boolean {
            AppLog.i(mediaButtonEvent.toString())
            return super.onMediaButtonEvent(mediaButtonEvent)
        }
    }

    override fun onUsbDetach(device: UsbDevice) {
        if (mAccessoryConnection is UsbAccessoryConnection) {
            if ((mAccessoryConnection as UsbAccessoryConnection).isDeviceRunning(device)) {
                stopSelf()
            }
        }
    }

    override fun onUsbAttach(device: UsbDevice) {

    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice) {

    }

    private class TimeTickReceiver(settings: Settings, private val mUiModeManager: UiModeManager) : BroadcastReceiver() {
        private val mNightMode: NightMode = NightMode(settings)
        private var mLastNightMode = false

        override fun onReceive(context: Context, intent: Intent) {

            val isCurrent = mNightMode.current
            if (mLastNightMode != isCurrent) {
                mLastNightMode = isCurrent

                mUiModeManager.nightMode = if (isCurrent) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO
                AppLog.i(mNightMode.toString())
                App.get(context).transport().send(NightModeEvent(isCurrent))
            }
        }
    }

    companion object {
        private val TYPE_USB = 1
        private val TYPE_WIFI = 2
        val EXTRA_CONNECTION_TYPE = "extra_connection_type"
        val EXTRA_IP = "extra_ip"

        fun createIntent(device: UsbDevice, context: Context): Intent {
            val intent = Intent(context, AapService::class.java)
            intent.putExtra(UsbManager.EXTRA_DEVICE, device)
            intent.putExtra(EXTRA_CONNECTION_TYPE, TYPE_USB)
            return intent
        }

        fun createIntent(ip: String, context: Context): Intent {
            val intent = Intent(context, AapService::class.java)
            intent.putExtra(EXTRA_IP, ip)
            intent.putExtra(EXTRA_CONNECTION_TYPE, TYPE_WIFI)
            return intent
        }

        private fun createConnection(intent: Intent, context: Context): AccessoryConnection? {

            val connectionType = intent.getIntExtra(EXTRA_CONNECTION_TYPE, 0)

            if (connectionType == TYPE_USB) {
                val device = LocalIntent.extractDevice(intent)
                if (device == null) {
                    AppLog.e("No device in " + intent)
                    return null
                }
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                return UsbAccessoryConnection(usbManager, device)
            } else if (connectionType == TYPE_WIFI) {
                val ip = intent.getStringExtra(EXTRA_IP)
                return SocketAccessoryConnection(ip)
            }

            return null
        }
    }
}