package com.vbuser.genshin.tool.cg.decoder;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/**
 * Windows audio track playback for the cg decoder.
 *
 * <p>Decodes the file's audio stream with Media Foundation (to PCM) and plays it through
 * {@link SourceDataLine} (Java Sound, shared mode). Runs on its own thread with its own COM
 * apartment so it can live next to {@link MFVideoPlayer} without touching its state.</p>
 *
 * <p>Audio is optional: any failure is logged and playback continues silently.</p>
 */
final class MFAudioPlayer {

    private static final Logger LOGGER = LogManager.getLogger("MFAudioPlayer");

    private static final int MF_VERSION = 0x00020070;
    private static final int COINIT_MULTITHREADED = 0x0;
    private static final int MF_SOURCE_READER_FIRST_AUDIO_STREAM = 0xFFFFFFFD;
    private static final int MF_SOURCE_READERF_ENDOFSTREAM = 0x00000002;

    private static final int IUNKNOWN_RELEASE = 2;
    private static final int SRC_GET_CURRENT_MEDIA_TYPE = 6;
    private static final int SRC_SET_CURRENT_MEDIA_TYPE = 7;
    private static final int SRC_READ_SAMPLE = 9;
    private static final int SAMPLE_CONVERT_TO_CONTIGUOUS = 41;
    private static final int BUF_LOCK = 3;
    private static final int BUF_UNLOCK = 4;
    private static final int ATTR_GET_UINT32 = 7;
    private static final int ATTR_SET_GUID = 24;

    private static NativeLibrary mfplat;
    private static NativeLibrary mfreadwrite;
    private static NativeLibrary ole32;
    private static Function MFStartup;
    private static Function MFShutdown;
    private static Function MFCreateAttributes;
    private static Function MFCreateSourceReaderFromURL;
    private static Function MFCreateMediaType;
    private static Function CoInitializeEx;
    private static Function CoUninitialize;

    private final String filePath;
    private volatile long baseTimestamp = Long.MIN_VALUE;
    private volatile boolean stopped;
    private volatile boolean paused;
    private volatile SourceDataLine line;
    private Thread thread;

    MFAudioPlayer(String filePath) {
        this.filePath = filePath;
    }

    void start() {
        thread = new Thread(this::run, "CG-MFAudio");
        thread.setDaemon(true);
        thread.start();
    }

    /** First video timestamp (100 ns units); samples before it are dropped. */
    void alignTo(long timestamp) {
        baseTimestamp = timestamp;
    }

    void pause() {
        paused = true;
        SourceDataLine current = line;
        if (current != null) {
            try {
                current.stop();
                current.flush();
            } catch (Throwable ignored) {
            }
        }
    }

    void resume() {
        paused = false;
        SourceDataLine current = line;
        if (current != null) {
            try {
                current.start();
            } catch (Throwable ignored) {
            }
        }
    }

    void stop() {
        stopped = true;
        Thread current = thread;
        if (current != null && current != Thread.currentThread()) {
            current.interrupt();
            try {
                current.join(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ worker

    private void run() {
        Pointer attrs = null;
        Pointer mediaType = null;
        Pointer reader = null;
        SourceDataLine audioLine = null;
        boolean comInitialized = false;
        boolean mfStarted = false;
        try {
            initNative();
            if (!failed(CoInitializeEx.invokeInt(new Object[]{Pointer.NULL, COINIT_MULTITHREADED}))) {
                comInitialized = true;
            }
            if (failed(MFStartup.invokeInt(new Object[]{MF_VERSION, 0}))) {
                LOGGER.warn("[MFAudioPlayer] MFStartup failed, audio disabled");
                return;
            }
            mfStarted = true;

            PointerByReference ppAttrs = new PointerByReference();
            if (failed(MFCreateAttributes.invokeInt(new Object[]{ppAttrs, 1}))) {
                return;
            }
            attrs = ppAttrs.getValue();

            PointerByReference ppReader = new PointerByReference();
            int hr = MFCreateSourceReaderFromURL.invokeInt(
                    new Object[]{wide(new java.io.File(filePath).toURI().toString()), attrs, ppReader});
            if (failed(hr)) {
                LOGGER.warn("[MFAudioPlayer] Could not open the media for audio (hr=0x{})",
                        Integer.toHexString(hr));
                return;
            }
            reader = ppReader.getValue();

            PointerByReference ppType = new PointerByReference();
            if (failed(MFCreateMediaType.invokeInt(new Object[]{ppType}))) {
                return;
            }
            mediaType = ppType.getValue();
            hr(mediaType, ATTR_SET_GUID, guid(0x48EBA18E, 0xF8C9, 0x4687,
                            new int[]{0xBF, 0x11, 0x0A, 0x74, 0xC9, 0xF9, 0x6A, 0x8F}),
                    guid(0x73647561, 0x0000, 0x0010,
                            new int[]{0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}));
            hr(mediaType, ATTR_SET_GUID, guid(0xF7E34C9A, 0x42E8, 0x4714,
                            new int[]{0xB7, 0x4B, 0xCB, 0x29, 0xD7, 0x2C, 0x35, 0xE5}),
                    guid(0x00000001, 0x0000, 0x0010,
                            new int[]{0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}));

            hr = hr(reader, SRC_SET_CURRENT_MEDIA_TYPE,
                    MF_SOURCE_READER_FIRST_AUDIO_STREAM, Pointer.NULL, mediaType);
            if (failed(hr)) {
                LOGGER.warn("[MFAudioPlayer] No PCM audio stream (hr=0x{}), playing silently",
                        Integer.toHexString(hr));
                return;
            }

            PointerByReference ppCurrent = new PointerByReference();
            if (failed(hr(reader, SRC_GET_CURRENT_MEDIA_TYPE,
                    MF_SOURCE_READER_FIRST_AUDIO_STREAM, ppCurrent))) {
                return;
            }
            Pointer current = ppCurrent.getValue();
            int channels;
            int sampleRate;
            int bits;
            try {
                channels = readUInt32(current, guid(0x37E48BF5, 0x645E, 0x4C5B,
                        new int[]{0x89, 0xDE, 0xAD, 0xA9, 0xE2, 0x9B, 0x69, 0x6A}), 2);
                sampleRate = readUInt32(current, guid(0x5FAEEAE7, 0x0290, 0x4C31,
                        new int[]{0x9E, 0x8A, 0xC5, 0x34, 0xF6, 0x8D, 0x9D, 0xBA}), 48000);
                bits = readUInt32(current, guid(0xF2DEB57F, 0x40FA, 0x4764,
                        new int[]{0xAA, 0x33, 0xED, 0x4F, 0x2D, 0x1F, 0xF6, 0x69}), 16);
            } finally {
                release(current);
            }
            if (channels <= 0 || sampleRate <= 0 || bits != 16) {
                LOGGER.warn("[MFAudioPlayer] Unsupported PCM layout ({}ch {}Hz {}bit), silent",
                        channels, sampleRate, bits);
                return;
            }

            AudioFormat format = new AudioFormat(sampleRate, bits, channels, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
            if (!AudioSystem.isLineSupported(info)) {
                LOGGER.warn("[MFAudioPlayer] No audio device for {}, playing silently", format);
                return;
            }
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
            audioLine.open(format, Math.max(sampleRate * channels * 2 / 4, 8192));
            audioLine.start();
            line = audioLine;
            LOGGER.info("[MFAudioPlayer] Playing audio {}Hz {}ch 16bit", sampleRate, channels);

            decodeLoop(reader, audioLine);
        } catch (Throwable t) {
            LOGGER.warn("[MFAudioPlayer] Audio playback failed, continuing silently", t);
        } finally {
            line = null;
            if (audioLine != null) {
                try {
                    audioLine.drain();
                    audioLine.stop();
                    audioLine.close();
                } catch (Throwable ignored) {
                }
            }
            if (reader != null) {
                release(reader);
            }
            if (mediaType != null) {
                release(mediaType);
            }
            if (attrs != null) {
                release(attrs);
            }
            if (mfStarted) {
                try {
                    MFShutdown.invokeInt(new Object[0]);
                } catch (Throwable ignored) {
                }
            }
            if (comInitialized) {
                try {
                    CoUninitialize.invokeInt(new Object[0]);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void decodeLoop(Pointer reader, SourceDataLine audioLine) {
        boolean firstPacket = true;
        while (!stopped) {
            if (paused || baseTimestamp == Long.MIN_VALUE) {
                sleepQuietly(10);
                continue;
            }
            IntByReference actualStream = new IntByReference();
            IntByReference flags = new IntByReference();
            LongByReference timestamp = new LongByReference();
            PointerByReference ppSample = new PointerByReference();
            int hr = hr(reader, SRC_READ_SAMPLE, MF_SOURCE_READER_FIRST_AUDIO_STREAM, 0,
                    actualStream, flags, timestamp, ppSample);
            if (failed(hr)) {
                LOGGER.debug("[MFAudioPlayer] ReadSample failed hr=0x{}", Integer.toHexString(hr));
                return;
            }
            Pointer sample = ppSample.getValue();
            if (sample == null) {
                if ((flags.getValue() & MF_SOURCE_READERF_ENDOFSTREAM) != 0) {
                    return;
                }
                sleepQuietly(2);
                continue;
            }
            try {
                if ((flags.getValue() & MF_SOURCE_READERF_ENDOFSTREAM) != 0) {
                    return;
                }
                // Drop everything before the first video frame so both streams line up.
                if (timestamp.getValue() < baseTimestamp) {
                    continue;
                }
                PointerByReference ppBuffer = new PointerByReference();
                if (failed(hr(sample, SAMPLE_CONVERT_TO_CONTIGUOUS, ppBuffer))) {
                    continue;
                }
                Pointer buffer = ppBuffer.getValue();
                PointerByReference ppData = new PointerByReference();
                IntByReference maxLength = new IntByReference();
                IntByReference currentLength = new IntByReference();
                if (failed(hr(buffer, BUF_LOCK, ppData, maxLength, currentLength))) {
                    release(buffer);
                    continue;
                }
                try {
                    int length = currentLength.getValue();
                    if (length > 0) {
                        byte[] pcm = ppData.getValue().getByteArray(0, length);
                        if (firstPacket) {
                            firstPacket = false;
                            LOGGER.info("[MFAudioPlayer] First PCM packet: {} bytes", length);
                        }
                        audioLine.write(pcm, 0, pcm.length);
                    }
                } finally {
                    hr(buffer, BUF_UNLOCK);
                    release(buffer);
                }
            } finally {
                release(sample);
            }
        }
    }

    private static int readUInt32(Pointer mediaType, Memory key, int fallback) {
        IntByReference value = new IntByReference();
        if (failed(hr(mediaType, ATTR_GET_UINT32, key, value))) {
            return fallback;
        }
        return value.getValue();
    }

    // ------------------------------------------------------------------ JNA plumbing

    private static synchronized void initNative() {
        if (mfplat != null) {
            return;
        }
        mfplat = NativeLibrary.getInstance("mfplat.dll");
        mfreadwrite = NativeLibrary.getInstance("mfreadwrite.dll");
        ole32 = NativeLibrary.getInstance("ole32.dll");
        MFStartup = mfplat.getFunction("MFStartup");
        MFShutdown = mfplat.getFunction("MFShutdown");
        MFCreateAttributes = mfplat.getFunction("MFCreateAttributes");
        MFCreateMediaType = mfplat.getFunction("MFCreateMediaType");
        MFCreateSourceReaderFromURL = mfreadwrite.getFunction("MFCreateSourceReaderFromURL");
        CoInitializeEx = ole32.getFunction("CoInitializeEx");
        CoUninitialize = ole32.getFunction("CoUninitialize");
    }

    private static int hr(Pointer object, int vtableIndex, Object... extraArgs) {
        Function function = Function.getFunction(object.getPointer(0)
                .getPointer(vtableIndex * Native.POINTER_SIZE));
        Object[] all = new Object[extraArgs.length + 1];
        all[0] = object;
        System.arraycopy(extraArgs, 0, all, 1, extraArgs.length);
        return function.invokeInt(all);
    }

    private static void release(Pointer object) {
        try {
            hr(object, IUNKNOWN_RELEASE);
        } catch (Throwable ignored) {
        }
    }

    private static Memory guid(int d1, int d2, int d3, int[] bytes) {
        Memory memory = new Memory(16);
        memory.setInt(0, d1);
        memory.setShort(4, (short) d2);
        memory.setShort(6, (short) d3);
        for (int i = 0; i < 8; i++) {
            memory.setByte(8 + i, (byte) bytes[i]);
        }
        return memory;
    }

    private static Memory wide(String text) {
        char[] chars = text.toCharArray();
        Memory memory = new Memory((chars.length + 1) * 2L);
        for (int i = 0; i < chars.length; i++) {
            memory.setShort(i * 2L, (short) chars[i]);
        }
        memory.setShort(chars.length * 2L, (short) 0);
        return memory;
    }

    private static boolean failed(int hr) {
        return hr < 0;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
