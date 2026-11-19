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

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Windows video decoder built on Media Foundation (the engine Windows Media Player uses).
 *
 * <p>This exists because JavaFX's Windows media backend (a GStreamer {@code dshowwrapper}
 * pipeline) fails to open almost any MP4 on some Windows/driver combinations with
 * {@code ERROR_MEDIA_INVALID}, while Media Foundation decodes the very same files without
 * trouble. It implements the same {@link IVideoFrameProvider} contract, so the rest of the
 * cg stack (texture upload, overlay, events) is unchanged.</p>
 *
 * <p>Decoding runs on a dedicated thread (MTA apartment). Frames are handed out through
 * {@link #getFramePixels()} exactly once each, in BGRA/ARGB order, exactly like the JavaFX
 * implementation.</p>
 *
 * <p>Windows only; {@link #isSupported()} is {@code false} everywhere else, so callers fall
 * back to {@link FXVideoPlayer}.</p>
 */
public final class MFVideoPlayer implements IVideoFrameProvider {

    private static final Logger LOGGER = LogManager.getLogger("MFVideoPlayer");

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    private static volatile Boolean supported;

    // ---- Media Foundation constants -------------------------------------------------
    private static final int MF_VERSION = 0x00020070;
    private static final int MFSTARTUP_FULL = 0;
    private static final int MF_SOURCE_READER_FIRST_VIDEO_STREAM = 0xFFFFFFFC;
    private static final int MF_SOURCE_READER_MEDIASOURCE = 0xFFFFFFFF;
    private static final int MF_SOURCE_READERF_ENDOFSTREAM = 0x00000002;
    private static final int COINIT_MULTITHREADED = 0x0;

    // ---- COM vtable indices (0 based, after the three IUnknown slots) ----------------
    private static final int IUNKNOWN_RELEASE = 2;

    // IMFSourceReader
    private static final int SRC_GET_CURRENT_MEDIA_TYPE = 6;
    private static final int SRC_SET_CURRENT_MEDIA_TYPE = 7;
    private static final int SRC_READ_SAMPLE = 9;
    private static final int SRC_GET_PRESENTATION_ATTRIBUTE = 12;

    // IMFSample extends IMFAttributes, so its methods start after IMFAttributes' 33
    private static final int SAMPLE_CONVERT_TO_CONTIGUOUS = 41;

    // IMFMediaBuffer
    private static final int BUF_LOCK = 3;
    private static final int BUF_UNLOCK = 4;

    // IMFAttributes
    private static final int ATTR_GET_UINT64 = 8;
    private static final int ATTR_SET_UINT32 = 21;
    private static final int ATTR_SET_GUID = 24;

    // PROPVARIANT.vt for VT_UI8
    private static final short VT_UI8 = 21;

    private static NativeLibrary mfplat;
    static NativeLibrary mfreadwrite;
    static NativeLibrary ole32;
    private static Function MFStartup;
    private static Function MFShutdown;
    private static Function MFCreateAttributes;
    private static Function MFCreateSourceReaderFromURL;
    private static Function MFCreateMediaType;
    private static Function CoInitializeEx;
    private static Function CoUninitialize;

    private final String filePath;

    private volatile int width;
    private volatile int height;
    private volatile long durationMs;
    private volatile boolean running;
    private volatile boolean stopped;
    private volatile boolean paused;

    private final Object frameLock = new Object();
    private int[] latestFrame;
    private long latestFrameTimeMs;
    private boolean frameDirty;

    private Thread decodeThread;
    private volatile Pointer reader;
    private volatile int openError;
    private final CountDownLatch openLatch = new CountDownLatch(1);
    private volatile String openFailure;

    private final Runnable onEnded;
    private volatile MFAudioPlayer audio;
    private final java.util.function.Consumer<String> onFailed;

    public MFVideoPlayer(String filePath) {
        this(filePath, null, null);
    }

    public MFVideoPlayer(String filePath, Runnable onEnded,
                         java.util.function.Consumer<String> onFailed) {
        this.filePath = filePath;
        this.onEnded = onEnded;
        this.onFailed = onFailed;
    }

    /** True when this platform can use the Media Foundation decoder. */
    public static boolean isSupported() {
        Boolean cached = supported;
        if (cached != null) {
            return cached;
        }
        boolean available = false;
        if (WINDOWS) {
            try {
                NativeLibrary.getInstance("mfplat.dll");
                NativeLibrary.getInstance("mfreadwrite.dll");
                available = true;
            } catch (Throwable t) {
                LOGGER.warn("[MFVideoPlayer] Media Foundation is not available", t);
            }
        }
        supported = available;
        return available;
    }

    /**
     * Starts opening the media and returns immediately; the caller waits for the decoder
     * with {@link #awaitReady(long)} on a worker thread. Waiting inside this method used to
     * block the client thread for up to 15 seconds while Windows opened the file.
     */
    @Override
    public void start() {
        if (!isSupported()) {
            throw new IllegalStateException("Media Foundation is not available on this platform");
        }
        File file = new File(filePath);
        if (!file.isFile()) {
            throw new IllegalStateException("Video file not found: " + file.getAbsolutePath());
        }
        stopped = false;
        paused = false;
        openError = 0;
        openFailure = null;

        running = true;
        decodeThread = new Thread(this::runDecoder, "CG-MFDecoder");
        decodeThread.setDaemon(true);
        decodeThread.start();
    }

    /**
     * Waits for the decoder to finish opening the media.
     *
     * @return true when frames can be read; false on timeout, cancellation or media error
     */
    @Override
    public boolean awaitReady(long timeoutMs) {
        try {
            if (!openLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                openFailure = "Media Foundation did not open the media in time";
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            openFailure = "Interrupted while opening the media";
            return false;
        }
        if (stopped) {
            openFailure = "the playback was stopped before the decoder was ready";
            return false;
        }
        if (openError != 0 || width <= 0 || height <= 0) {
            if (openFailure == null) {
                openFailure = "Media Foundation could not open the media (hr=0x"
                        + Integer.toHexString(openError) + ")";
            }
            return false;
        }
        return true;
    }

    @Override
    public String getOpenFailure() {
        return openFailure;
    }

    /** Marks the attempt as opened (or as no longer pending). */
    private void markOpenFinished() {
        openLatch.countDown();
    }

    // ------------------------------------------------------------------ decode thread

    private void runDecoder() {
        Pointer attrs = null;
        Pointer mediaType = null;
        Pointer sourceReader = null;
        boolean comInitialized = false;
        boolean mfStarted = false;
        try {
            initNative();
            if (failed(CoInitializeEx.invokeInt(new Object[]{Pointer.NULL, COINIT_MULTITHREADED}))) {
                LOGGER.warn("[MFVideoPlayer] CoInitializeEx failed, continuing anyway");
            } else {
                comInitialized = true;
            }
            if (failed(MFStartup.invokeInt(new Object[]{MF_VERSION, MFSTARTUP_FULL}))) {
                openError = -1;
                return;
            }
            mfStarted = true;

            PointerByReference ppAttrs = new PointerByReference();
            if (failed(MFCreateAttributes.invokeInt(new Object[]{ppAttrs, 2}))) {
                openError = -2;
                return;
            }
            attrs = ppAttrs.getValue();
            // Let the source reader insert the video processor (RGB32 conversion).
            hr(attrs, ATTR_SET_UINT32, guid(0xFB394F3D, 0xCCF1, 0x42EE,
                    new int[]{0xBB, 0xB3, 0xF9, 0xB8, 0x45, 0xD5, 0x68, 0x1D}), 1);
            hr(attrs, ATTR_SET_UINT32, guid(0x0DBF2D50, 0x11F4, 0x4A4B,
                    new int[]{0x9F, 0x4B, 0x0C, 0x42, 0x47, 0x0E, 0x0D, 0x8B}), 1);

            PointerByReference ppReader = new PointerByReference();
            String url = new File(filePath).toURI().toString();
            int hr = MFCreateSourceReaderFromURL.invokeInt(
                    new Object[]{wide(url), attrs, ppReader});
            if (failed(hr)) {
                openError = hr;
                return;
            }
            sourceReader = ppReader.getValue();
            reader = sourceReader;

            PointerByReference ppType = new PointerByReference();
            if (failed(MFCreateMediaType.invokeInt(new Object[]{ppType}))) {
                openError = -3;
                return;
            }
            mediaType = ppType.getValue();
            hr(mediaType, ATTR_SET_GUID, guid(0x48EBA18E, 0xF8C9, 0x4687,
                            new int[]{0xBF, 0x11, 0x0A, 0x74, 0xC9, 0xF9, 0x6A, 0x8F}),
                    guid(0x73646976, 0x0000, 0x0010,
                            new int[]{0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}));
            hr(mediaType, ATTR_SET_GUID, guid(0xF7E34C9A, 0x42E8, 0x4714,
                            new int[]{0xB7, 0x4B, 0xCB, 0x29, 0xD7, 0x2C, 0x35, 0xE5}),
                    guid(0x00000016, 0x0000, 0x0010,
                            new int[]{0x80, 0x00, 0x00, 0xAA, 0x00, 0x38, 0x9B, 0x71}));

            hr = hr(sourceReader, SRC_SET_CURRENT_MEDIA_TYPE,
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM, Pointer.NULL, mediaType);
            if (failed(hr)) {
                openError = hr;
                return;
            }
            durationMs = readDurationMs(sourceReader);

            PointerByReference ppCurrent = new PointerByReference();
            if (failed(hr(sourceReader, SRC_GET_CURRENT_MEDIA_TYPE,
                    MF_SOURCE_READER_FIRST_VIDEO_STREAM, ppCurrent))) {
                openError = -4;
                return;
            }
            Pointer current = ppCurrent.getValue();
            try {
                LongByReference frameSize = new LongByReference();
                if (!failed(hr(current, ATTR_GET_UINT64, guid(0x1652C33D, 0xD6B2, 0x4012,
                        new int[]{0xB8, 0x34, 0x72, 0x03, 0x08, 0x49, 0xA3, 0x7D}), frameSize))) {
                    long packed = frameSize.getValue();
                    width = (int) (packed >> 32);
                    height = (int) (packed & 0xFFFFFFFFL);
                }
            } finally {
                release(current);
            }
            if (width <= 0 || height <= 0) {
                openError = -5;
                return;
            }
            LOGGER.info("[MFVideoPlayer] Opened {} ({}x{}, {} ms)", url, width, height, durationMs);
            markOpenFinished();

            audio = new MFAudioPlayer(filePath);
            audio.start();

            decodeFrames();
        } catch (Throwable t) {
            LOGGER.error("[MFVideoPlayer] Decoder crashed", t);
            openError = openError == 0 ? -99 : openError;
            openFailure = "Media Foundation decoder crashed: " + t;
        } finally {
            running = false;
            reader = null;
            MFAudioPlayer audioPlayer = audio;
            audio = null;
            if (audioPlayer != null) {
                audioPlayer.stop();
            }
            if (sourceReader != null) {
                release(sourceReader);
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
            // Also releases a caller waiting for a decoder that failed to open.
            markOpenFinished();
        }
    }

    private void decodeFrames() {
        long firstTimestamp = -1;
        long wallStart = System.nanoTime();
        // Double buffer: the decoder writes one array while the render thread may still be
        // uploading the other one to the OpenGL texture.
        int[][] buffers = {new int[width * height], new int[width * height]};
        int bufferIndex = 0;
        boolean decodeFailed = false;

        while (!stopped) {
            if (paused) {
                sleepQuietly(15);
                continue;
            }
            IntByReference actualStream = new IntByReference();
            IntByReference flags = new IntByReference();
            LongByReference timestamp = new LongByReference();
            PointerByReference ppSample = new PointerByReference();
            int hr = hr(reader, SRC_READ_SAMPLE, MF_SOURCE_READER_FIRST_VIDEO_STREAM, 0,
                    actualStream, flags, timestamp, ppSample);
            if (failed(hr)) {
                LOGGER.warn("[MFVideoPlayer] ReadSample failed hr=0x{}", Integer.toHexString(hr));
                decodeFailed = true;
                notifyFailed("decode failed (hr=0x" + Integer.toHexString(hr) + ")");
                break;
            }
            Pointer sample = ppSample.getValue();
            if (sample == null) {
                if ((flags.getValue() & MF_SOURCE_READERF_ENDOFSTREAM) != 0) {
                    break;
                }
                sleepQuietly(2);
                continue;
            }
            try {
                if ((flags.getValue() & MF_SOURCE_READERF_ENDOFSTREAM) != 0) {
                    break;
                }
                long tsRef = timestamp.getValue(); // 100 ns units
                if (firstTimestamp < 0) {
                    firstTimestamp = tsRef;
                    wallStart = System.nanoTime();
                    if (audio != null) {
                        audio.alignTo(firstTimestamp);
                    }
                }
                // Pace playback to the media timeline.
                long targetNanos = wallStart + (tsRef - firstTimestamp) * 100L;
                long waitNanos = targetNanos - System.nanoTime();
                if (waitNanos > 1_000_000L) {
                    sleepNanos(waitNanos);
                }
                if (stopped) {
                    break;
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
                    int needed = width * height * 4;
                    if (currentLength.getValue() >= needed) {
                        int[] pixels = buffers[bufferIndex];
                        bufferIndex ^= 1;
                        ByteBuffer bytes = ppData.getValue()
                                .getByteBuffer(0, needed).order(ByteOrder.LITTLE_ENDIAN);
                        IntBuffer ints = bytes.asIntBuffer();
                        ints.get(pixels);
                        // Media Foundation RGB32 is BGRA in memory, which is exactly
                        // 0xAARRGGBB when read as a little endian int. Force opaque alpha.
                        for (int i = 0; i < pixels.length; i++) {
                            pixels[i] |= 0xFF000000;
                        }
                        publishFrame(pixels, tsRef / 10_000L);
                    }
                } finally {
                    hr(buffer, BUF_UNLOCK);
                    release(buffer);
                }
            } finally {
                release(sample);
            }
        }
        running = false;
        if (stopped || decodeFailed) {
            return;
        }
        if (onEnded != null) {
            try {
                onEnded.run();
            } catch (Throwable t) {
                LOGGER.warn("[MFVideoPlayer] End-of-media callback failed", t);
            }
        }
    }

    private void notifyFailed(String reason) {
        if (onFailed == null) {
            return;
        }
        try {
            onFailed.accept(reason);
        } catch (Throwable t) {
            LOGGER.warn("[MFVideoPlayer] Failure callback threw", t);
        }
    }

    private void publishFrame(int[] pixels, long timeMs) {
        synchronized (frameLock) {
            latestFrame = pixels;
            latestFrameTimeMs = timeMs;
            frameDirty = true;
        }
    }

    private long readDurationMs(Pointer sourceReader) {
        try {
            Memory propVariant = new Memory(24);
            propVariant.clear();
            int hr = hr(sourceReader, SRC_GET_PRESENTATION_ATTRIBUTE,
                    MF_SOURCE_READER_MEDIASOURCE,
                    guid(0x6C990D33, 0xBB8E, 0x477A,
                            new int[]{0x85, 0x98, 0x0D, 0x5D, 0x96, 0xFC, 0xD8, 0x8A}),
                    propVariant);
            if (!failed(hr) && propVariant.getShort(0) == VT_UI8) {
                return propVariant.getLong(8) / 10_000L;
            }
        } catch (Throwable t) {
            LOGGER.debug("[MFVideoPlayer] Duration attribute unavailable", t);
        }
        // Media Foundation often does not expose MF_PD_DURATION; reading the MP4 header is
        // cheap and gives the progress bar something to work with.
        return readMp4DurationMs();
    }

    /** Reads {@code moov/mvhd} from an MP4/MOV file; 0 when the file is not one. */
    private long readMp4DurationMs() {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(new File(filePath), "r")) {
            long total = raf.length();
            long pos = 0;
            while (pos + 8 <= total) {
                raf.seek(pos);
                long size = raf.readInt() & 0xFFFFFFFFL;
                byte[] typeBytes = new byte[4];
                raf.readFully(typeBytes);
                String type = new String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII);
                if (size == 1) {
                    size = raf.readLong();
                    if (size < 16) {
                        break;
                    }
                } else if (size < 8) {
                    break;
                }
                if ("moov".equals(type)) {
                    long child = pos + 8;
                    long end = Math.min(pos + size, total);
                    while (child + 8 <= end) {
                        raf.seek(child);
                        long boxSize = raf.readInt() & 0xFFFFFFFFL;
                        byte[] childType = new byte[4];
                        raf.readFully(childType);
                        String childName = new String(childType,
                                java.nio.charset.StandardCharsets.US_ASCII);
                        if (boxSize == 1) {
                            boxSize = raf.readLong();
                            if (boxSize < 16) {
                                break;
                            }
                        } else if (boxSize < 8) {
                            break;
                        }
                        if ("mvhd".equals(childName)) {
                            raf.seek(child + 8);
                            int version = raf.readUnsignedByte();
                            raf.skipBytes(3);
                            long timescale;
                            long duration;
                            if (version == 1) {
                                raf.skipBytes(16);
                                timescale = raf.readInt() & 0xFFFFFFFFL;
                                duration = raf.readLong();
                            } else {
                                raf.skipBytes(8);
                                timescale = raf.readInt() & 0xFFFFFFFFL;
                                duration = raf.readInt() & 0xFFFFFFFFL;
                            }
                            if (timescale > 0) {
                                return duration * 1000L / timescale;
                            }
                            return 0L;
                        }
                        child += boxSize;
                    }
                    return 0L;
                }
                pos += size;
            }
        } catch (Throwable t) {
            LOGGER.debug("[MFVideoPlayer] Could not read the container duration", t);
        }
        return 0L;
    }

    // ------------------------------------------------------------------ provider API

    @Override
    public int[] getFramePixels() {
        synchronized (frameLock) {
            if (!frameDirty) {
                return null;
            }
            frameDirty = false;
            return latestFrame;
        }
    }

    @Override
    public boolean isPlaying() {
        return running && !stopped;
    }

    @Override
    public int getWidth() {
        return width;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public void pause() {
        paused = true;
        MFAudioPlayer current = audio;
        if (current != null) {
            current.pause();
        }
    }

    @Override
    public void resume() {
        paused = false;
        MFAudioPlayer current = audio;
        if (current != null) {
            current.resume();
        }
    }

    @Override
    public boolean isPaused() {
        return paused;
    }

    @Override
    public float getProgress() {
        if (durationMs <= 0) {
            return 0f;
        }
        long time;
        synchronized (frameLock) {
            time = latestFrameTimeMs;
        }
        return Math.max(0f, Math.min(1f, (float) ((double) time / (double) durationMs)));
    }

    @Override
    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public void stop() {
        stopped = true;
        paused = false;
        // Release a caller blocked in awaitReady() as well.
        markOpenFinished();
        MFAudioPlayer audioPlayer = audio;
        if (audioPlayer != null) {
            audioPlayer.stop();
        }
        Thread thread = decodeThread;
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
            try {
                thread.join(2000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        running = false;
        synchronized (frameLock) {
            frameDirty = false;
            latestFrame = null;
        }
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
                .getPointer((long) vtableIndex * Native.POINTER_SIZE));
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


    private void sleepNanos(long nanos) {
        long deadline = System.nanoTime() + nanos;
        while (!stopped) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0L) {
                return;
            }
            long millis = Math.min(remaining / 1_000_000L, 20L);
            if (millis == 0L) {
                Thread.yield();
            } else {
                sleepQuietly(millis);
            }
        }
    }
    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(Math.min(millis, 100L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
