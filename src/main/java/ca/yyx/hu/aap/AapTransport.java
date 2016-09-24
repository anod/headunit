package ca.yyx.hu.aap;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.NonNull;

import ca.yyx.hu.decoder.AudioDecoder;
import ca.yyx.hu.decoder.MicRecorder;
import ca.yyx.hu.decoder.VideoDecoder;
import ca.yyx.hu.usb.UsbAccessoryConnection;
import ca.yyx.hu.utils.Utils;

public class AapTransport extends HandlerThread implements Handler.Callback {
    private static final int POLL = 1;
    private static final int DATA_MESSAGE = 2;
    private static final int MIC_RECORD_START = 3;
    private static final int MIC_RECORD_STOP = 4;

    private static final int DEFBUF = 131080;

    private byte[] fixed_res_buf = new byte[DEFBUF * 16];

    private Handler mHandler;
    private boolean mMicRecording;

    private final AudioDecoder mAudioDecoder;
    private final MicRecorder mMicRecorder;
    private final VideoDecoder mVideoDecoder;
    private boolean mStopped;

    public AapTransport(AudioDecoder audioDecoder, VideoDecoder videoDecoder) {
        super("AapTransport");
        mAudioDecoder = audioDecoder;
        mVideoDecoder = videoDecoder;
        mMicRecorder = new MicRecorder();
    }

    static {
        System.loadLibrary("hu_jni");
    }

    private static native int native_aap_start(int ep_in_addr, int ep_out_addr);
    // Java_ca_yyx_hu_aap_AapTransport_native_1aa_1cmd
    private static native int native_aap_poll(int res_len, byte[] res_buf);
    private static native int native_aap_send(int channel, int cmd_len, byte[] cmd_buf);


    @Override
    protected void onLooperPrepared() {
        super.onLooperPrepared();
        mHandler = new Handler(getLooper(), this);
        mHandler.sendEmptyMessage(POLL);
    }

    @Override
    public boolean handleMessage(Message msg) {
        int ret;

        if (msg.what == MIC_RECORD_STOP) {
            mMicRecording = false;
        } else if (msg.what == MIC_RECORD_START) {
            mMicRecording = true;
        }

        if (mMicRecording) {
            byte[] mic_buf = Protocol.createMicBuffer();
            int mic_audio_len = mMicRecorder.mic_audio_read(mic_buf, 10, MicRecorder.MIC_BUFFER_SIZE);
            if (mic_audio_len >= 78) {                                    // If we read at least 64 bytes of audio data
                Utils.put_time(6, mic_buf, SystemClock.elapsedRealtime());
                ret = native_aap_send(Protocol.AA_CH_MIC, mic_audio_len, mic_buf);    // Send mic audio
            } else if (mic_audio_len > 0) {
                Utils.loge("No data from microphone");
            }
        }

        if (msg.what == DATA_MESSAGE) {
            int channel = msg.arg1;
            int dataLength = msg.arg2;
            byte[] data = (byte[]) msg.obj;
            ret = native_aap_send(channel, dataLength, data);
            if (ret < 0) {
                Utils.loge("Send result: " + ret);
            }
        }

        ret = aa_poll(fixed_res_buf.length, fixed_res_buf);
        if (mHandler == null) {
            return false;
        }
        if (isAlive() && !mHandler.hasMessages(POLL)) {
            mHandler.sendEmptyMessage(POLL);
        }

        if (ret < 0) {
            Utils.loge("Error result: " + ret);
            this.quit();
        }

        return true;
    }

    @Override
    public boolean quit() {

        native_aap_send(Protocol.AA_CH_CTR, Protocol.BYEBYE_REQUEST.length, Protocol.BYEBYE_REQUEST);
        Utils.ms_sleep(100);
        if (mHandler != null) {
            mHandler.removeCallbacks(this);
            mHandler = null;
        }
        mStopped = true;
        return super.quit();
    }

    private void setMicRecording(boolean start) {
        mHandler.sendEmptyMessage(start ? MIC_RECORD_START : MIC_RECORD_STOP);
    }

    public boolean connectAndStart(UsbAccessoryConnection connection) {
        Utils.logd("Start Aap transport for " + connection);

        int ret = native_aap_start(connection.getEndpointInAddr(), connection.getEndpointOutAddr());

        if (ret == 0) {                                                     // If started OK...
            this.start();                                          // Create and start Transport Thread
            return true;
        }
        Utils.loge("Cannot start AAP ret:" + ret);
        return false;
    }

    // Send AA packet/HU command/mic audio AND/OR receive video/output audio/audio notifications
    private int aa_poll(int res_len, @NonNull byte[] res_buf) {

        // Send a command (or null command)
        int ret = native_aap_poll(res_len, res_buf);

        if (ret == Protocol.RESPONSE_MIC_STOP) {                                                     // If mic stop...
            Utils.logd("Microphone Stop");
            setMicRecording(false);
            mMicRecorder.mic_audio_stop();
            return (0);
        } else if (ret == Protocol.RESPONSE_MIC_START) {                                                // Else if mic start...
            Utils.logd("Microphone Start");
            setMicRecording(true);
            return (0);
        } else if (ret == Protocol.RESPONSE_AUDIO_STOP) {                                                // Else if audio stop...
            Utils.logd("Audio Stop");
            mAudioDecoder.out_audio_stop(AudioDecoder.AA_CH_AUD);
            return (0);
        } else if (ret == Protocol.RESPONSE_AUDIO1_STOP) {                                                // Else if audio1 stop...
            Utils.logd("Audio1 Stop");
            mAudioDecoder.out_audio_stop(AudioDecoder.AA_CH_AU1);
            return (0);
        } else if (ret == Protocol.RESPONSE_AUDIO2_STOP) {                                                // Else if audio2 stop...
            Utils.logd("Audio2 Stop");
            mAudioDecoder.out_audio_stop(AudioDecoder.AA_CH_AU2);
            return (0);
        } else if (ret > 0) {
            handleMedia(res_buf, ret);
        }
        return ret;
    }

    private void handleMedia(byte[] buffer, int size) {
        if (VideoDecoder.isH246Video(buffer)) {
            mVideoDecoder.decode(buffer, size);
        } else {
            mAudioDecoder.decode(buffer, size);
        }
    }

    void sendTouch(byte action, int x, int y) {
        if (mHandler != null) {
            long ts = SystemClock.elapsedRealtime() * 1000000L;
            ByteArray ba = Protocol.createTouchMessage(ts, action, x, y);
            Message msg = mHandler.obtainMessage(DATA_MESSAGE, Protocol.AA_CH_TOU, ba.length, ba.data);
            mHandler.sendMessage(msg);
        }
    }

    public boolean isStopped() {
        return mStopped;
    }

    public void sendButton(int btnCode, boolean isPress) {
        if (mHandler != null)
        {
            long ts = SystemClock.elapsedRealtime() * 1000000L;
            // Timestamp in nanoseconds = microseconds x 1,000,000
            ByteArray ba = Protocol.createButtonMessage(ts, btnCode, isPress);
            Message msg = mHandler.obtainMessage(DATA_MESSAGE, Protocol.AA_CH_TOU, ba.length, ba.data);
            mHandler.sendMessage(msg);
        }
    }

    void sendNightMode(boolean enabled) {
        if (mHandler != null) {
            byte[] modeData = Protocol.createNightModeMessage(enabled);
            Message msg = mHandler.obtainMessage(DATA_MESSAGE, Protocol.AA_CH_SEN, modeData.length, modeData);
            mHandler.sendMessage(msg);
        }
    }
}

