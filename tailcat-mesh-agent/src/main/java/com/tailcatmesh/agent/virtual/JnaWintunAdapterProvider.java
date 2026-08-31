package com.tailcatmesh.agent.virtual;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.Structure;
import com.sun.jna.win32.StdCallLibrary;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * JNA binding for the small Wintun adapter lifecycle used by M7.
 *
 * <p>Packet I/O remains the responsibility of tun2socks. The Agent only
 * creates/opens the stable adapter and closes its native handle.</p>
 */
public final class JnaWintunAdapterProvider implements WintunAdapterProvider {

    private final Path wintunDll;
    private final Object loadLock = new Object();
    private volatile WintunLibrary library;

    public JnaWintunAdapterProvider() {
        this(null);
    }

    public JnaWintunAdapterProvider(Path wintunDll) {
        this.wintunDll = wintunDll == null ? null : wintunDll.toAbsolutePath().normalize();
    }

    @Override
    public Adapter openOrCreate(String interfaceName, UUID requestedGuid) {
        Objects.requireNonNull(interfaceName, "interfaceName");
        Objects.requireNonNull(requestedGuid, "requestedGuid");
        WintunLibrary nativeLibrary = library();
        Pointer handle = nativeLibrary.WintunOpenAdapter(new WString(interfaceName));
        boolean created = false;
        if (handle == null) {
            Guid guid = Guid.fromUuid(requestedGuid);
            handle = nativeLibrary.WintunCreateAdapter(
                    new WString(interfaceName),
                    new WString(WindowsWintunRuntime.DEFAULT_TUNNEL_TYPE),
                    guid);
            created = true;
        }
        if (handle == null) {
            throw new TunRuntimeException("unable to open or create Wintun adapter " + interfaceName);
        }
        return new Adapter(interfaceName, requestedGuid, handle, created);
    }

    @Override
    public Adapter openExisting(String interfaceName, UUID requestedGuid) {
        Objects.requireNonNull(interfaceName, "interfaceName");
        Objects.requireNonNull(requestedGuid, "requestedGuid");
        Pointer handle = library().WintunOpenAdapter(new WString(interfaceName));
        if (handle == null) {
            throw new TunRuntimeException("Wintun adapter is not ready: " + interfaceName);
        }
        return new Adapter(interfaceName, requestedGuid, handle, false);
    }

    @Override
    public void close(Adapter adapter) {
        Objects.requireNonNull(adapter, "adapter");
        if (!(adapter.nativeHandle() instanceof Pointer pointer)
                || pointer == null) {
            return;
        }
        library().WintunCloseAdapter(pointer);
    }

    private WintunLibrary library() {
        WintunLibrary result = library;
        if (result != null) {
            return result;
        }
        synchronized (loadLock) {
            result = library;
            if (result == null) {
                try {
                    String name = wintunDll == null ? "wintun" : wintunDll.toString();
                    result = Native.load(name, WintunLibrary.class);
                    library = result;
                } catch (UnsatisfiedLinkError error) {
                    throw new TunRuntimeException("wintun.dll is not available", error);
                }
            }
            return result;
        }
    }

    private interface WintunLibrary extends StdCallLibrary {
        Pointer WintunCreateAdapter(WString name, WString tunnelType, Guid requestedGuid);

        Pointer WintunOpenAdapter(WString name);

        void WintunCloseAdapter(Pointer adapter);
    }

    /** Windows GUID layout used by the Wintun C API. */
    public static final class Guid extends Structure {
        public int data1;
        public short data2;
        public short data3;
        public byte[] data4 = new byte[8];

        public Guid() {
        }

        static Guid fromUuid(UUID uuid) {
            byte[] bytes = ByteBuffer.allocate(16)
                    .order(ByteOrder.BIG_ENDIAN)
                    .putLong(uuid.getMostSignificantBits())
                    .putLong(uuid.getLeastSignificantBits())
                    .array();
            Guid result = new Guid();
            result.data1 = ByteBuffer.wrap(bytes, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            result.data2 = ByteBuffer.wrap(bytes, 4, 2).order(ByteOrder.BIG_ENDIAN).getShort();
            result.data3 = ByteBuffer.wrap(bytes, 6, 2).order(ByteOrder.BIG_ENDIAN).getShort();
            System.arraycopy(bytes, 8, result.data4, 0, result.data4.length);
            result.write();
            return result;
        }

        @Override
        protected List<String> getFieldOrder() {
            return List.of("data1", "data2", "data3", "data4");
        }
    }
}
