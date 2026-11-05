package com.vbuser.genshin.tool.network;

import com.github.luben.zstd.Zstd;
import com.vbuser.genshin.tool.network.util.NetConfig;
import com.vbuser.genshin.tool.network.util.VarInt;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;

import java.io.ByteArrayOutputStream;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Carrier for one batch of vanilla play packets.
 *
 * <p>Compression is chosen per batch: raw, zlib, or Zstd, whichever is
 * smallest. Zstd is used when it wins; the zlib fallback keeps small or
 * unusual payloads from getting larger.</p>
 *
 * <p>Layout: byte method (0 raw, 1 zlib, 2 zstd), optional varint
 * original-size, then bytes. The raw bytes themselves are
 * {@code magic, version, varint count, { varint packetId, varint length, data }}.</p>
 */
public class VanillaBundlePacket implements IMessage {

    private static final byte METHOD_RAW = 0;
    private static final byte METHOD_ZLIB = 1;
    private static final byte METHOD_ZSTD = 2;

    private byte[] raw;

    public VanillaBundlePacket() {}

    public VanillaBundlePacket(byte[] raw) {
        this.raw = raw;
    }

    public byte[] getRaw() {
        return raw;
    }

    @Override
    public void toBytes(ByteBuf buf) {
        if (raw == null || raw.length == 0) {
            buf.writeByte(METHOD_RAW);
            return;
        }

        byte method = METHOD_RAW;
        byte[] best = raw;

        if (raw.length >= NetConfig.VANILLA_ZSTD_THRESHOLD) {
            byte[] zlib = zlib(raw);
            if (zlib.length < best.length) {
                best = zlib;
                method = METHOD_ZLIB;
            }
            byte[] zstd = zstd(raw);
            if (zstd.length < best.length) {
                best = zstd;
                method = METHOD_ZSTD;
            }
        }

        buf.writeByte(method);
        if (method != METHOD_RAW) {
            VarInt.writeVarInt(buf, raw.length);
            buf.writeBytes(best);
        } else {
            buf.writeBytes(raw);
        }
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        int method = buf.readUnsignedByte();
        if (method == METHOD_RAW) {
            if (buf.readableBytes() > NetConfig.VANILLA_MAX_RAW_BYTES) {
                throw new IllegalArgumentException("Vanilla bundle too large");
            }
            this.raw = new byte[buf.readableBytes()];
            buf.readBytes(this.raw);
            return;
        }

        int originalSize = VarInt.readVarInt(buf);
        if (originalSize < 0 || originalSize > NetConfig.VANILLA_MAX_RAW_BYTES) {
            throw new IllegalArgumentException("Invalid vanilla bundle size: " + originalSize);
        }
        byte[] compressed = new byte[buf.readableBytes()];
        buf.readBytes(compressed);

        if (method == METHOD_ZLIB) {
            this.raw = inflate(compressed, originalSize);
        } else if (method == METHOD_ZSTD) {
            this.raw = new byte[originalSize];
            long written = Zstd.decompress(this.raw, compressed);
            if (written != originalSize) {
                throw new IllegalArgumentException(
                        "Zstd decompression size mismatch: " + written + " != " + originalSize);
            }
        } else {
            throw new IllegalArgumentException("Unknown vanilla bundle method: " + method);
        }
    }

    private static byte[] zlib(byte[] data) {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                out.write(buffer, 0, count);
            }
            return out.toByteArray();
        } catch (Throwable t) {
            return data;
        } finally {
            deflater.end();
        }
    }

    private static byte[] zstd(byte[] data) {
        try {
            return Zstd.compress(data, 9);
        } catch (Throwable t) {
            return data;
        }
    }

    private static byte[] inflate(byte[] data, int originalSize) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            byte[] out = new byte[originalSize];
            int offset = 0;
            while (!inflater.finished() && offset < out.length) {
                int count = inflater.inflate(out, offset, out.length - offset);
                if (count == 0 && inflater.needsInput()) {
                    break;
                }
                offset += count;
            }
            if (offset != originalSize) {
                throw new IllegalArgumentException(
                        "Zlib decompression size mismatch: " + offset + " != " + originalSize);
            }
            return out;
        } catch (Throwable t) {
            throw new IllegalArgumentException("Failed to inflate vanilla bundle", t);
        } finally {
            inflater.end();
        }
    }
}
