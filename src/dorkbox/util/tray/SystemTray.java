/*
 * Copyright 2014 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.util.tray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dorkbox.util.NamedThreadFactory;
import dorkbox.util.OS;
import dorkbox.util.jna.linux.GtkSupport;
import dorkbox.util.tray.linux.AppIndicatorTray;
import dorkbox.util.tray.linux.GtkSystemTray;
import dorkbox.util.tray.swing.SwingSystemTray;


/**
 * Interface for system tray implementations.
 */
public abstract class SystemTray {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static MessageDigest digest;

    protected static final Logger logger = LoggerFactory.getLogger(SystemTray.class);

    /**
     * Size of the icon WHEN IT'S IN THE TRAY
     */
    public static int TRAY_SIZE = 22;

    /**
     * Location of the icon
     */
    public static String ICON_PATH = "";

    private static final long runtimeRandom = new SecureRandom().nextLong();
    private static Class<? extends SystemTray> trayType;

    static {
        if (OS.isLinux()) {
            GtkSupport.init();
            if (GtkSupport.isSupported) {
                // quick check, because we know that unity uses app-indicator. Maybe REALLY old versions do not. We support 14.04 LTE at least
                String getenv = System.getenv("XDG_CURRENT_DESKTOP");
                if (getenv != null && getenv.equals("Unity")) {
                    try {
                        trayType = AppIndicatorTray.class;
                    } catch (Exception ignored) {
                    }
                }

                if (trayType == null) {
                    BufferedReader bin = null;
                    try {
                        // the ONLY guaranteed way to determine if indicator-application-service is running (and thus, using app-indicator),
                        // is to look through all /proc/<pid>/status, and first line should be Name:\tindicator-appli
                        File proc = new File("/proc");
                        File[] listFiles = proc.listFiles();
                        if (listFiles != null) {
                            for (File procs : listFiles) {
                                String name = procs.getName();
                                if (!Character.isDigit(name.charAt(0))) {
                                    continue;
                                }

                                File status = new File(procs, "status");
                                if (!status.canRead()) {
                                    continue;
                                }

                                try {
                                    bin = new BufferedReader(new FileReader(status));
                                    String readLine = bin.readLine();
                                    if (readLine != null && readLine.contains("indicator-app")) {
                                        trayType = AppIndicatorTray.class;
                                        break;
                                    }
                                } finally {
                                    if (bin != null) {
                                        bin.close();
                                        bin = null;
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {
                    } finally {
                        if (bin != null) {
                            try {
                                bin.close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                }

                if (trayType == null) {
                    trayType = GtkSystemTray.class;
                }
            }
        }

        // this is windows OR mac
        if (trayType == null && java.awt.SystemTray.isSupported()) {
            trayType = SwingSystemTray.class;
        }

        if (trayType == null) {
            // unsupported tray
            logger.error("Unsupported tray type!");
        } else {
            try {
                digest = MessageDigest.getInstance("MD5");
            } catch (NoSuchAlgorithmException e) {
                logger.error("Unsupported hashing algorithm!");
                trayType = null;
            }
        }
    }

    protected final ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(new NamedThreadFactory("SysTrayExecutor", false));

    protected volatile FailureCallback failureCallback;
    protected volatile boolean active = false;
    protected String appName;

    public static SystemTray create(String appName) {
        if (trayType != null) {
            try {
                SystemTray newInstance = trayType.newInstance();
                if (newInstance != null) {
                    newInstance.setAppName(appName);
                }
                return newInstance;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // unsupported
        return null;
    }

    private void setAppName(String appName) {
        this.appName = appName;
    }

    public abstract void createTray(String iconName);

    public void removeTray() {
        SystemTray.this.callbackExecutor.shutdown();
    }

    public abstract void setStatus(String infoString, String iconName);

    public abstract void addMenuEntry(String menuText, SystemTrayMenuAction callback);

    public abstract void updateMenuEntry(String origMenuText, String newMenuText, SystemTrayMenuAction newCallback);


    protected String iconPath(String fileName) {
        // is file sitting on drive
        File iconTest;
        if (ICON_PATH.isEmpty()) {
            iconTest = new File(fileName);
        } else {
            iconTest = new File(ICON_PATH, fileName);
        }
        if (iconTest.isFile() && iconTest.canRead()) {
            return iconTest.getAbsolutePath();
        } else {
            if (!ICON_PATH.isEmpty()) {
                fileName = ICON_PATH + "/" + fileName;
            }

            String extension = "";
            int dot = fileName.lastIndexOf('.');
            if (dot > -1) {
                extension = fileName.substring(dot + 1);
            }

            // maybe it's in somewhere else.
            URL systemResource = Thread.currentThread().getContextClassLoader().getResource(fileName);
            if (systemResource == null) {
                // maybe it's in the system classloader?
                systemResource = ClassLoader.getSystemResource(fileName);
            }

            if (systemResource != null) {
                // copy out to a temp file, as a hash of the file
                String resourceFileName = systemResource.getPath();
                byte[] bytes = resourceFileName.getBytes(UTF_8);
                File newFile;
                String tempDir = System.getProperty("java.io.tmpdir");

                // can be wimpy, only one at a time
                synchronized (SystemTray.this) {
                    digest.reset();
                    digest.update(bytes);

                    // For KDE4, it must also be unique across runs
                    byte[] longBytes = new byte[8];
                    ByteBuffer wrap = ByteBuffer.wrap(longBytes);
                    wrap.putLong(runtimeRandom);
                    digest.update(longBytes);

                    byte[] hashBytes = digest.digest();
                    String hash = new BigInteger(1, hashBytes).toString(32);

                    newFile = new File(tempDir, hash + '.' + extension).getAbsoluteFile();
                    newFile.deleteOnExit();
                }

                InputStream inStream = null;
                OutputStream outStream = null;

                try {
                    inStream = systemResource.openStream();
                    outStream = new FileOutputStream(newFile);

                    byte[] buffer = new byte[2048];
                    int read;
                    while ((read = inStream.read(buffer)) > 0) {
                        outStream.write(buffer, 0, read);
                    }

                    return newFile.getAbsolutePath();
                } catch (IOException e) {
                    // Running from main line.
                    String message = "Unable to copy icon '" + fileName + "' to location: '" + newFile.getAbsolutePath() + "'";
                    logger.error(message, e);
                    throw new RuntimeException(message);
                } finally {
                    try {
                        if (inStream != null) {
                            inStream.close();
                        }
                    } catch (Exception ignored) {
                    }
                    try {
                        if (outStream != null) {
                            outStream.close();
                        }
                    } catch (Exception ignored) {
                    }
                }

                // appIndicator/gtk require strings
                // swing version loads as an image
            }
        }

        // Running from main line.
        String message = "Unable to find icon '" + fileName + "'";
        logger.error(message);
        throw new RuntimeException(message);
    }

    public final void setFailureCallback(FailureCallback failureCallback) {
        this.failureCallback = failureCallback;
    }

    public final boolean isActive() {
        return this.active;
    }
}
