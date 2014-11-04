SystemTray
==========

Cross-platform **SystemTray** and **AppIndicator** support for java applications.

This libraries only purpose is to show *reasonably* decent system-tray icons and app-indicators with a simple popup-menu.

There are a number of problems on Linux with the Swing (and SWT) system-tray icons, namely that:

1. Swing system-tray icons on linux **do not** support transparent backgrounds (they have a white background)
2. Swing/SWT **do not** support app-indicators, which are necessary on more recent versions of linux
3. Swing popup menus look like crap  
    - system-tray icons use a JMenuPopup, which looks nicer than the java 'regular' one.
    - app-indicators use native popups.


This is for cross-platform use, specifically - linux 32/64, mac 32/64, and windows 32/64. Java 6+



```
Note: This library does NOT uses SWT for system-tray support, only for the purpose
      of lessening the jar dependencies. Changing it to be SWT-based is not be 
      difficult, just remember SWT on linux *already* starts up the GTK main 
      event loop.

      This library needs additional testing in SWT environments.
```
```
Note: If you use the attached JNA libraries, you **MUST** load the respective
      native libraries yourself, especially with JNA (as the loading logic has
      been removed from the jar)
```
```
Note: This project was heavily influence by the excellent Lantern project,
      *Many* thanks to them for figuring out AppIndicators via JNA.
      https://github.com/getlantern/lantern
```

