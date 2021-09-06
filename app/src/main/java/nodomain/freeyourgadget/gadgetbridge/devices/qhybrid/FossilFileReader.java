/*  Copyright (C) 2021 Arjan Schrijver, Daniel Dakhno

    This file is part of Gadgetbridge.

    Gadgetbridge is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as published
    by the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Gadgetbridge is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>. */
package nodomain.freeyourgadget.gadgetbridge.devices.qhybrid;

import android.content.Context;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;

import nodomain.freeyourgadget.gadgetbridge.impl.GBDeviceApp;
import nodomain.freeyourgadget.gadgetbridge.util.UriHelper;

/**
 * Reads and parses files meant to be uploaded to Fossil Hybrid Q & HR watches.
 * These can be firmware files, watch apps and watchfaces (HR only).
 */
public class FossilFileReader {
    private static final Logger LOG = LoggerFactory.getLogger(FossilFileReader.class);
    private final UriHelper uriHelper;
    private boolean isValid = false;
    private boolean isFirmware = false;
    private boolean isApp = false;
    private boolean isWatchface = false;
    private String foundVersion = "(Unknown version)";
    private String foundName = "(unknown)";
    private GBDeviceApp app;
    private JSONObject mAppKeys;

    public FossilFileReader(Uri uri, Context context) throws IOException {
        uriHelper = UriHelper.get(uri, context);

        try (InputStream in = new BufferedInputStream(uriHelper.openInputStream())) {
            // Read just the first 32 bytes for file type detection
            byte[] bytes = new byte[32];
            int read = in.read(bytes);
            in.close();
            if (read < 32) {
                isValid = false;
                return;
            }
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            buf.order(ByteOrder.LITTLE_ENDIAN);

            short handle = buf.getShort();
            short version = buf.getShort();
            if ((handle == 5630) && (version == 3)) {
                // This is a watch app or watch face
                isValid = true;
                isApp = true;
                parseApp();
                return;
            }

            // Back to byte 0 for firmware detection
            buf.rewind();
            int header0 = buf.getInt();
            buf.getInt(); // size
            int header2 = buf.getInt();
            int header3 = buf.getInt();
            if (header0 != 1 || header2 != 0x00012000 || header3 != 0x00012000) {
                return;
            }

            buf.getInt(); // unknown
            isValid = true;
            isFirmware = true;
            parseFirmware();
        } catch (Exception e) {
            LOG.warn("Error during Fossil file parsing", e);
        }
    }

    private void parseFirmware() throws IOException {
        InputStream in = new BufferedInputStream(uriHelper.openInputStream());
        byte[] bytes = new byte[in.available()];
        in.read(bytes);
        in.close();
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(20);
        int version1 = buf.get() % 0xff;
        int version2 = buf.get() & 0xff;
        foundVersion = "DN1.0." + version1 + "." + version2;
        foundName = "Fossil Hybrid HR firmware";
    }

    private void parseApp() throws IOException, JSONException {
        mAppKeys = new JSONObject();
        mAppKeys.put("creator", "(unknown)");
        InputStream in = new BufferedInputStream(uriHelper.openInputStream());
        byte[] bytes = new byte[in.available()];
        in.read(bytes);
        in.close();
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.position(8);  // skip file handle and version
        int fileSize = buf.getInt();
        foundVersion = (int)buf.get() + "." + (int)buf.get() + "." + (int)buf.get() + "." + (int)buf.get();
        mAppKeys.put("version", foundVersion);
        buf.position(buf.position() + 8);  // skip null bytes
        int jerryStart = buf.getInt();
        int appIconStart = buf.getInt();
        int layout_start = buf.getInt();
        int display_name_start = buf.getInt();
        int display_name_start_2 = buf.getInt();
        int config_start = buf.getInt();
        int file_end = buf.getInt();
        buf.position(jerryStart);

        ArrayList<String> filenamesCode = parseAppFilenames(buf, appIconStart,false);
        if (filenamesCode.size() > 0) {
            foundName = filenamesCode.get(0);
            mAppKeys.put("name", foundName);
            mAppKeys.put("uuid", UUID.nameUUIDFromBytes(foundName.getBytes(StandardCharsets.UTF_8)));
        }
        ArrayList<String> filenamesIcons = parseAppFilenames(buf, layout_start,false);
        ArrayList<String> filenamesLayout = parseAppFilenames(buf, display_name_start,true);
        ArrayList<String> filenamesDisplayName = parseAppFilenames(buf, config_start,true);

        if (filenamesDisplayName.contains("theme_class")) {
            isApp = false;
            isWatchface = true;
            mAppKeys.put("type", "WATCHFACE");
        } else {
            mAppKeys.put("type", "APP_GENERIC");
        }
        app = new GBDeviceApp(mAppKeys, false);
    }

    private ArrayList<String> parseAppFilenames(ByteBuffer buf, int untilPosition, boolean cutTrailingNull) {
        ArrayList<String> list = new ArrayList<>();
        while (buf.position() < untilPosition) {
            int filenameLength = (int)buf.get();
            byte[] filenameBytes = new byte[filenameLength - 1];
            buf.get(filenameBytes);
            buf.get();
            list.add(new String(filenameBytes, Charset.forName("UTF8")));
            int filesize = buf.getShort();
            if (cutTrailingNull) {
                filesize -= 1;
            }
            buf.position(buf.position() + filesize);  // skip file data for now
            if (cutTrailingNull) {
                buf.get();
            }
        }
        return list;
    }

    public boolean isValid() {
        return isValid;
    }

    public boolean isFirmware() {
        return isFirmware;
    }

    public boolean isApp() {
        return isApp;
    }

    public boolean isWatchface() {
        return isWatchface;
    }

    public String getVersion() {
        return foundVersion;
    }

    public String getName() {
        return foundName;
    }

    public GBDeviceApp getGBDeviceApp() {
        return app;
    }

    public JSONObject getAppKeysJSON() {
        return mAppKeys;
    }
}
