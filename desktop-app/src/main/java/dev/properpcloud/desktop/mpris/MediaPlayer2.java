package dev.properpcloud.desktop.mpris;

import org.freedesktop.dbus.annotations.DBusInterfaceName;
import org.freedesktop.dbus.interfaces.DBusInterface;

@DBusInterfaceName("org.mpris.MediaPlayer2")
public interface MediaPlayer2 extends DBusInterface {
    void Raise();
    void Quit();
}
