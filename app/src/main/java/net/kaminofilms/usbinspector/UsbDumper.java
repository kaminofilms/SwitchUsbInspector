package net.kaminofilms.usbinspector;

import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import java.util.Locale;

/** Turns everything Android can see about a USB device into readable text. */
final class UsbDumper {
    static final int NINTENDO_VID = 0x057E;

    private UsbDumper() {}

    static boolean isNintendo(UsbDevice d) {
        return d != null && d.getVendorId() == NINTENDO_VID;
    }

    static String hex4(int v) { return String.format(Locale.US, "0x%04X", v & 0xFFFF); }
    static String hex2(int v) { return String.format(Locale.US, "0x%02X", v & 0xFF); }

    static String shortName(UsbDevice d) {
        String vendor = isNintendo(d) ? "Nintendo" : "Vendor";
        return vendor + " " + hex4(d.getVendorId()) + ":" + hex4(d.getProductId()) + " (" + d.getDeviceName() + ")";
    }

    static String className(int c) {
        switch (c) {
            case 0x00: return "0x00 (defined per interface)";
            case 0x01: return "0x01 Audio";
            case 0x02: return "0x02 CDC/Comms";
            case 0x03: return "0x03 HID";
            case 0x05: return "0x05 Physical";
            case 0x06: return "0x06 Image/PTP";
            case 0x07: return "0x07 Printer";
            case 0x08: return "0x08 Mass storage";
            case 0x09: return "0x09 Hub";
            case 0x0A: return "0x0A CDC data";
            case 0x0B: return "0x0B Smart card";
            case 0x0E: return "0x0E Video";
            case 0xDC: return "0xDC Diagnostic";
            case 0xE0: return "0xE0 Wireless";
            case 0xEF: return "0xEF Misc";
            case 0xFE: return "0xFE App-specific";
            case 0xFF: return "0xFF Vendor-specific";
            default: return hex2(c);
        }
    }

    static String direction(UsbEndpoint e) {
        return e.getDirection() == UsbConstants.USB_DIR_IN ? "IN " : "OUT";
    }

    static String transferType(int t) {
        switch (t) {
            case UsbConstants.USB_ENDPOINT_XFER_CONTROL: return "control";
            case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "isochronous";
            case UsbConstants.USB_ENDPOINT_XFER_BULK: return "bulk";
            case UsbConstants.USB_ENDPOINT_XFER_INT: return "interrupt";
            default: return "type" + t;
        }
    }

    /** Info that needs no permission. */
    static String summary(UsbDevice d) {
        StringBuilder sb = new StringBuilder();
        sb.append(isNintendo(d) ? "★ NINTENDO DEVICE " : "Device ").append(shortName(d)).append('\n');
        sb.append("  Device class ").append(className(d.getDeviceClass()))
          .append(", subclass ").append(hex2(d.getDeviceSubclass()))
          .append(", protocol ").append(hex2(d.getDeviceProtocol())).append('\n');
        if (Build.VERSION.SDK_INT >= 23) sb.append("  bcdDevice ").append(d.getVersion()).append('\n');
        int configs = d.getConfigurationCount();
        if (configs > 0) {
            for (int c = 0; c < configs; c++) {
                UsbConfiguration cf = d.getConfiguration(c);
                sb.append("  Config ").append(cf.getId())
                  .append(": maxPower ").append(cf.getMaxPower()).append(" mA")
                  .append(cf.isSelfPowered() ? ", self-powered" : "")
                  .append(cf.isRemoteWakeup() ? ", remote-wakeup" : "")
                  .append(", ").append(cf.getInterfaceCount()).append(" interface(s)")
                  .append(cf.getName() != null ? " \"" + cf.getName() + "\"" : "").append('\n');
                for (int i = 0; i < cf.getInterfaceCount(); i++) appendInterface(sb, cf.getInterface(i));
            }
        } else {
            for (int i = 0; i < d.getInterfaceCount(); i++) appendInterface(sb, d.getInterface(i));
        }
        return sb.toString().trim();
    }

    private static void appendInterface(StringBuilder sb, UsbInterface it) {
        sb.append("    Interface ").append(it.getId()).append(" alt ").append(it.getAlternateSetting())
          .append(": class ").append(className(it.getInterfaceClass()))
          .append(", sub ").append(hex2(it.getInterfaceSubclass()))
          .append(", proto ").append(hex2(it.getInterfaceProtocol()))
          .append(it.getName() != null ? " \"" + it.getName() + "\"" : "")
          .append(", ").append(it.getEndpointCount()).append(" endpoint(s)\n");
        for (int e = 0; e < it.getEndpointCount(); e++) {
            UsbEndpoint ep = it.getEndpoint(e);
            sb.append("      EP ").append(hex2(ep.getAddress())).append(' ').append(direction(ep))
              .append(' ').append(transferType(ep.getType()))
              .append(", maxPacket ").append(ep.getMaxPacketSize())
              .append(", interval ").append(ep.getInterval()).append('\n');
        }
    }

    /** Needs permission: strings, raw descriptors, BOS, language IDs. */
    static String fullDump(UsbManager usb, UsbDevice d) {
        StringBuilder sb = new StringBuilder("FULL DUMP ").append(shortName(d)).append('\n');
        try {
            sb.append("  Manufacturer: ").append(d.getManufacturerName()).append('\n');
            sb.append("  Product:      ").append(d.getProductName()).append('\n');
            sb.append("  Serial:       ").append(d.getSerialNumber()).append('\n');
        } catch (SecurityException e) {
            sb.append("  Strings blocked: ").append(e.getMessage()).append('\n');
        }
        UsbDeviceConnection c = usb.openDevice(d);
        if (c == null) return sb.append("  openDevice() failed. Permission was revoked or the device is gone.").toString();
        try {
            byte[] raw = c.getRawDescriptors();
            if (raw == null) {
                sb.append("  Raw descriptors: unavailable\n");
            } else {
                sb.append("  Raw descriptors (").append(raw.length).append(" bytes):\n");
                parseDescriptors(sb, raw);
                hexDump(sb, raw, raw.length, "    ");
            }
            byte[] bos = new byte[1024];
            int n = c.controlTransfer(0x80, 6, 0x0F00, 0, bos, bos.length, 1000);
            if (n > 0) {
                sb.append("  BOS descriptor (").append(n).append(" bytes):\n");
                hexDump(sb, bos, n, "    ");
            } else {
                sb.append("  BOS descriptor: not provided\n");
            }
            byte[] lang = new byte[255];
            n = c.controlTransfer(0x80, 6, 0x0300, 0, lang, lang.length, 1000);
            if (n >= 4) {
                sb.append("  String language IDs:");
                for (int i = 2; i + 1 < n; i += 2) sb.append(' ').append(hex4((lang[i] & 0xFF) | ((lang[i + 1] & 0xFF) << 8)));
                sb.append('\n');
            }
        } finally {
            c.close();
        }
        return sb.toString().trim();
    }

    private static String descriptorName(int t) {
        switch (t) {
            case 0x01: return "DEVICE";
            case 0x02: return "CONFIGURATION";
            case 0x03: return "STRING";
            case 0x04: return "INTERFACE";
            case 0x05: return "ENDPOINT";
            case 0x0B: return "INTERFACE ASSOCIATION";
            case 0x0F: return "BOS";
            case 0x10: return "DEVICE CAPABILITY";
            case 0x21: return "HID";
            case 0x22: return "HID REPORT";
            case 0x24: return "CLASS-SPECIFIC INTERFACE";
            case 0x25: return "CLASS-SPECIFIC ENDPOINT";
            case 0x30: return "SUPERSPEED EP COMPANION";
            default: return "type " + hex2(t);
        }
    }

    private static int u16(byte[] b, int off) { return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8); }

    static void parseDescriptors(StringBuilder sb, byte[] raw) {
        int off = 0;
        while (off + 2 <= raw.length) {
            int len = raw[off] & 0xFF;
            int type = raw[off + 1] & 0xFF;
            if (len < 2 || off + len > raw.length) {
                sb.append("    +").append(off).append(" malformed descriptor (len ").append(len).append("), stopping parse\n");
                break;
            }
            sb.append("    +").append(off).append(' ').append(descriptorName(type)).append(" len ").append(len);
            if (type == 0x01 && len >= 18) {
                sb.append(": bcdUSB ").append(hex4(u16(raw, off + 2)))
                  .append(", maxPacket0 ").append(raw[off + 7] & 0xFF)
                  .append(", VID ").append(hex4(u16(raw, off + 8)))
                  .append(", PID ").append(hex4(u16(raw, off + 10)))
                  .append(", configs ").append(raw[off + 17] & 0xFF);
            } else if (type == 0x02 && len >= 9) {
                sb.append(": totalLen ").append(u16(raw, off + 2))
                  .append(", interfaces ").append(raw[off + 4] & 0xFF)
                  .append(", value ").append(raw[off + 5] & 0xFF)
                  .append(", attr ").append(hex2(raw[off + 7]))
                  .append(", maxPower ").append((raw[off + 8] & 0xFF) * 2).append(" mA");
            } else if (type == 0x04 && len >= 9) {
                sb.append(": number ").append(raw[off + 2] & 0xFF)
                  .append(", alt ").append(raw[off + 3] & 0xFF)
                  .append(", endpoints ").append(raw[off + 4] & 0xFF)
                  .append(", class ").append(className(raw[off + 5] & 0xFF));
            } else if (type == 0x05 && len >= 7) {
                sb.append(": address ").append(hex2(raw[off + 2]))
                  .append(", attr ").append(hex2(raw[off + 3]))
                  .append(", maxPacket ").append(u16(raw, off + 4))
                  .append(", interval ").append(raw[off + 6] & 0xFF);
            } else if (type == 0x21 && len >= 9) {
                sb.append(": bcdHID ").append(hex4(u16(raw, off + 2)))
                  .append(", report descriptor ").append(u16(raw, off + 7)).append(" bytes");
            }
            sb.append('\n');
            off += len;
        }
    }

    static void hexDump(StringBuilder sb, byte[] b, int len, String indent) {
        for (int row = 0; row < len; row += 16) {
            sb.append(indent).append(String.format(Locale.US, "%04X  ", row));
            StringBuilder ascii = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                int idx = row + i;
                if (idx < len) {
                    int v = b[idx] & 0xFF;
                    sb.append(String.format(Locale.US, "%02X ", v));
                    ascii.append(v >= 0x20 && v < 0x7F ? (char) v : '.');
                } else {
                    sb.append("   ");
                }
            }
            sb.append(' ').append(ascii).append('\n');
        }
    }

    static String hex(byte[] b, int len) {
        StringBuilder sb = new StringBuilder();
        hexDump(sb, b, len, "    ");
        return sb.toString();
    }
}
