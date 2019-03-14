package com.p1.genius.bluetoothconectionapp;
/**
 * {@link BluetoothController}
 * {@link MainActivity}
 */

public interface Constants
{
    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 1; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 2;  // now connected to a remote device
    public static final int STATE_CONNECT_FAILED = 3;
    public static final int STATE__RECONNECTED = 4;
    public static final int DESTINATION_REACHED = 5;
    public static final int NEW_RESULT = 6;
    public static final int GUIDING_ERROR = 7;
}
