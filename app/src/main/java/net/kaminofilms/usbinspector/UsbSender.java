package net.kaminofilms.usbinspector;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Experimental: pushes raw bytes (e.g. an image file) to a bulk or interrupt OUT endpoint. */
final class UsbSender {
    interface Logger { void log(String msg); }

    static final class Target {
        final UsbInterface intf;
        final UsbEndpoint out;
        final UsbEndpoint in; // may be null

        Target(UsbInterface intf, UsbEndpoint out, UsbEndpoint in) {
            this.intf = intf;
            this.out = out;
            this.in = in;
        }

        String label() {
            return "Intf " + intf.getId() + " alt " + intf.getAlternateSetting()
                    + " → EP " + UsbDumper.hex2(out.getAddress()) + " " + UsbDumper.transferType(out.getType())
                    + " (max " + out.getMaxPacketSize() + ")"
                    + (in != null ? ", reply EP " + UsbDumper.hex2(in.getAddress()) : "");
        }
    }

    private UsbSender() {}

    static List<Target> findOutTargets(UsbDevice d) {
        List<Target> list = new ArrayList<>();
        if (d == null) return list;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i);
            UsbEndpoint in = null;
            List<UsbEndpoint> outs = new ArrayList<>();
            for (int e = 0; e < it.getEndpointCount(); e++) {
                UsbEndpoint ep = it.getEndpoint(e);
                int t = ep.getType();
                if (t != UsbConstants.USB_ENDPOINT_XFER_BULK && t != UsbConstants.USB_ENDPOINT_XFER_INT) continue;
                if (ep.getDirection() == UsbConstants.USB_DIR_OUT) outs.add(ep);
                else if (in == null) in = ep;
            }
            for (UsbEndpoint o : outs) list.add(new Target(it, o, in));
        }
        return list;
    }

    static void send(UsbManager usb, UsbDevice dev, Target t, byte[] data,
                     boolean lengthPrefix, boolean readReply, Logger log) {
        byte[] payload = data;
        if (lengthPrefix) {
            payload = new byte[data.length + 4];
            ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(data.length);
            System.arraycopy(data, 0, payload, 4, data.length);
        }
        UsbDeviceConnection c = usb.openDevice(dev);
        if (c == null) {
            log.log("openDevice() failed. Permission was revoked or the device was unplugged.");
            return;
        }
        boolean claimed = false;
        try {
            claimed = c.claimInterface(t.intf, true);
            if (!claimed) {
                log.log("claimInterface(" + t.intf.getId() + ") failed. Another driver or app owns that interface.");
                return;
            }
            if (t.intf.getAlternateSetting() != 0 && !c.setInterface(t.intf)) {
                log.log("Warning: setInterface(alt " + t.intf.getAlternateSetting() + ") failed; trying anyway.");
            }
            final int chunk = 16384; // per-transfer limit on older Android versions
            int off = 0;
            int lastDecile = -1;
            long start = System.currentTimeMillis();
            log.log("Sending " + payload.length + " bytes to " + t.label() + (lengthPrefix ? " (with 4-byte length header)" : ""));
            while (off < payload.length) {
                int n = Math.min(chunk, payload.length - off);
                int r = c.bulkTransfer(t.out, payload, off, n, 5000);
                if (r < 0) {
                    log.log("Transfer FAILED at byte " + off + " of " + payload.length
                            + ". The device timed out or stalled the endpoint, so nothing on its side is reading this data.");
                    break;
                }
                if (r == 0) {
                    log.log("Device accepted 0 bytes at offset " + off + "; stopping.");
                    break;
                }
                off += r;
                int decile = (int) (off * 10L / payload.length);
                if (decile != lastDecile) {
                    log.log("  " + (decile * 10) + "%  (" + off + " bytes)");
                    lastDecile = decile;
                }
            }
            if (off == payload.length) {
                int mps = t.out.getMaxPacketSize();
                if (t.out.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK && mps > 0 && payload.length % mps == 0) {
                    c.bulkTransfer(t.out, new byte[0], 0, 0, 1000); // zero-length packet marks end of transfer
                }
                log.log("Done: the device accepted all " + payload.length + " bytes in "
                        + (System.currentTimeMillis() - start) + " ms. Accepted doesn't mean understood: "
                        + "check whether anything actually changed on the console.");
            }
            if (readReply) {
                if (t.in == null) {
                    log.log("No IN endpoint on this interface, so there's no reply to read.");
                } else {
                    byte[] buf = new byte[Math.max(64, t.in.getMaxPacketSize()) * 16];
                    int r = c.bulkTransfer(t.in, buf, buf.length, 2000);
                    if (r < 0) log.log("No reply within 2 s on EP " + UsbDumper.hex2(t.in.getAddress()) + ".");
                    else log.log("Reply on EP " + UsbDumper.hex2(t.in.getAddress()) + " (" + r + " bytes):\n" + UsbDumper.hex(buf, r));
                }
            }
        } finally {
            if (claimed) c.releaseInterface(t.intf);
            c.close();
        }
    }
}
