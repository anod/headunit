package ca.yyx.hu.decoder;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.view.Surface;

import java.io.FileOutputStream;
import java.nio.ByteBuffer;

import ca.yyx.hu.Utils;

/**
 * @author algavris
 * @date 28/04/2016.
 */
public class VideoDecoder {
    private SurfaceTexture mSurface;
    private MediaCodec mCodec;
    private MediaCodec.BufferInfo mCodecBufferInfo;
    private final static Object sLock = new Object();
    private ByteBuffer[] mCodecBuffer;

    private boolean video_recording = false;
    private FileOutputStream video_record_fos = null;
    private final Context mContext;

    public VideoDecoder(Context context) {
        mContext = context;
    }

    public void stop_record() {
        try {
            if (video_record_fos != null)
                video_record_fos.close();                                                     // Close output file
        } catch (Throwable t) {
            Utils.loge(t);
        }
        video_record_fos = null;
        video_recording = false;
    }

    void video_record_write(ByteBuffer content) {
        // ffmpeg -i 2015-04-29-00_38_16.mp4 -vcodec copy -an -bsf:v h264_mp4toannexb  aa.h264
        if (!video_recording) {
            try {
                video_record_fos = mContext.openFileOutput("/sdcard/hurec.h264", Context.MODE_WORLD_READABLE);//, Context.MODE_PRIVATE); // | MODE_WORLD_WRITEABLE      // NullPointerException here unless permissions 755
            } catch (Throwable t) {
                //Utils.loge ("Throwable: " + t);
                Utils.loge("Throwable: " + t);
                //return;
            }
            try {
                if (video_record_fos == null)
                    video_record_fos = mContext.openFileOutput("hurec.h264", Context.MODE_WORLD_READABLE);//, Context.MODE_PRIVATE); // | MODE_WORLD_WRITEABLE      // NullPointerException here unless permissions 755
            } catch (Throwable t) {
                //Utils.loge ("Throwable: " + t);
                Utils.loge("Throwable: " + t);
                return;
            }

            video_recording = true;
        }

        int pos = content.position();
        int siz = content.remaining();
        int last = pos + siz - 1;
        byte[] ba = content.array();

        byte b1 = ba[pos + 3];
        byte bl = ba[last];
        if (Utils.ena_log_verbo)
            Utils.logv("pos: " + pos + "  siz: " + siz + "  last: " + last + " (" + Utils.hex_get(b1) + ")  b1: " + b1 + "  bl: " + bl + " (" + Utils.hex_get(bl) + ")");

        try {
            video_record_fos.write(ba, pos, siz);                                               // Copy input to output file
        } catch (Throwable t) {
            Utils.loge(t);
        }
    }

    public boolean isRecording() {
        return video_recording;
    }

    public void decode(ByteBuffer content) {

        if (Utils.quiet_file_get("/sdcard/hurecv"))                       // If video record flag file exists...
            video_record_write(content);
        else if (isRecording())                                           // Else if was recording... (file must have been removed)
            stop_record();


        synchronized (sLock) {
            if (mCodec == null) {
                return;
            }

            while (content.hasRemaining()) {                                 // While there is remaining content...

                if (!codec_input_provide(content)) {                          // Process buffer; if no available buffers...
                    Utils.loge("Dropping content because there are no available buffers.");
                    return;
                }
                if (content.hasRemaining())                                    // Never happens now
                    Utils.loge("content.hasRemaining ()");

                codec_output_consume();                                        // Send result to video codec
            }
        }
    }

    private void codec_stop() {
        if (mCodec != null)
            mCodec.stop();                                                  // Stop codec
        mCodec = null;
        mCodecBuffer = null;
        mCodecBufferInfo = null;
    }

    private boolean codec_input_provide(ByteBuffer content) {            // Called only by media_decode() with new NAL unit in Byte Buffer
        try {
            final int index = mCodec.dequeueInputBuffer(1000000);           // Get input buffer with 1 second timeout
            if (index < 0) {
                return (false);                                                 // Done with "No buffer" error
            }
            if (mCodecBuffer == null) {
                mCodecBuffer = mCodec.getInputBuffers();                // Set mCodecBuffer if needed
            }

            final ByteBuffer buffer = mCodecBuffer[index];
            final int capacity = buffer.capacity();
            buffer.clear();
            if (content.remaining() <= capacity) {                           // If we can just put() the content...
                buffer.put(content);                                           // Put the content
            } else {                                                            // Else... (Should not happen ?)
                Utils.loge("content.hasRemaining (): " + content.hasRemaining() + "  capacity: " + capacity);

                int limit = content.limit();
                content.limit(content.position() + capacity);                 // Temporarily set constrained limit
                buffer.put(content);
                content.limit(limit);                                          // Restore original limit
            }
            buffer.flip();                                                   // Flip buffer for reading

            mCodec.queueInputBuffer(index, 0, buffer.limit(), 0, 0);       // Queue input buffer for decoding w/ offset=0, size=limit, no microsecond timestamp and no flags (not end of stream)
            return (true);                                                    // Processed
        } catch (Throwable t) {
            Utils.loge("Throwable: " + t);
        }
        return (false);                                                     // Error: exception
    }

    private void codec_output_consume() {                                // Called only by media_decode() after codec_input_provide()
        int index;
        for (; ; ) {                                                          // Until no more buffers...
            index = mCodec.dequeueOutputBuffer(mCodecBufferInfo, 0);        // Dequeue an output buffer but do not wait
            if (index >= 0)
                mCodec.releaseOutputBuffer(index, true /*render*/);           // Return the buffer to the codec
            else if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED)         // See this 1st shortly after start. API >= 21: Ignore as getOutputBuffers() deprecated
                Utils.logd("INFO_OUTPUT_BUFFERS_CHANGED");
            else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)          // See this 2nd shortly after start. Output format changed for subsequent data. See getOutputFormat()
                Utils.logd("INFO_OUTPUT_FORMAT_CHANGED");
            else if (index == MediaCodec.INFO_TRY_AGAIN_LATER)
                break;
            else
                break;
        }
        if (index != MediaCodec.INFO_TRY_AGAIN_LATER)
            Utils.loge("index: " + index);
    }

    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mSurface = surface;

        if (height > 1080) {                                              // Limit surface height to 1080 or N7 2013 won't work: width: 1920  height: 1104    Screen 1200x1920, nav = 96 pixels
            Utils.loge("height: " + height);
            height = 1080;
        }
        try {
            mCodec = MediaCodec.createDecoderByType("video/avc");       // Create video codec: ITU-T H.264 / ISO/IEC MPEG-4 Part 10, Advanced Video Coding (MPEG-4 AVC)
        } catch (Throwable t) {
            Utils.loge("Throwable creating video/avc decoder: " + t);
        }
        try {
            mCodecBufferInfo = new MediaCodec.BufferInfo();                         // Create Buffer Info
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
            mCodec.configure(format, new Surface(surface), null, 0);               // Configure codec for H.264 with given width and height, no crypto and no flag (ie decode)
            mCodec.start();                                             // Start codec
        } catch (Throwable t) {
            Utils.loge("Throwable: " + t);
        }
    }
}